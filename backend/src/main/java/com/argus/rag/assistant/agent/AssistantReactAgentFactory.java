package com.argus.rag.assistant.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手 React Agent 工厂。
 * <p>负责创建配置好的 {@link ReactAgent} 实例。通过 Spring 的自动集合注入
 * 发现所有声明了 {@code @Tool} 方法的 Bean，新增工具只需添加 {@code @Component} 类即可自动注册。</p>
 */
@Component
public class AssistantReactAgentFactory {

    // KB_SEARCH 至少需要经历"模型决定调工具 -> 工具执行 -> 模型基于工具结果生成最终回答"，
    // 递归上限过小会导致图在最终回答前被截断，返回空 AssistantMessage。
    // 第七步（更新状态）：递归上限不再用固定常量——由方案预算派生（loopState.graphRecursionLimit()），
    // 无状态时回落常量。循环长度由运行器按预算控制，模型无权决定循环何时停。
    private static final int AGENT_RECURSION_LIMIT = 10;

    private final ChatModel chatModel;
    private final AssistantShortTermMemoryHook assistantShortTermMemoryHook;
    /** 第五步（模型决策）：AFTER_MODEL 决策审计 Hook——工具越界 WARN + 结构化留痕。 */
    private final com.argus.rag.assistant.loop.DecisionAuditHook decisionAuditHook;
    /** Spring 自动收集所有声明了 @Tool 方法的 Bean */
    private final List<Object> availableTools;
    /** CHAT 模式可用工具：仅实时搜索与时间，不触碰知识库/结构化数据上下文 */
    private final List<Object> chatTools;

    public AssistantReactAgentFactory(
            ChatModel chatModel,
            AssistantShortTermMemoryHook assistantShortTermMemoryHook,
            com.argus.rag.assistant.loop.DecisionAuditHook decisionAuditHook,
            AssistantKnowledgeBaseTool assistantKnowledgeBaseTool,
            StructuredDataQueryTool structuredDataQueryTool,
            CurrentTimeTool currentTimeTool,
            WebSearchTool webSearchTool,
            AssistantTaskProgressWriteTool assistantTaskProgressWriteTool
    ) {
        this.chatModel = chatModel;
        this.assistantShortTermMemoryHook = assistantShortTermMemoryHook;
        this.decisionAuditHook = decisionAuditHook;
        // 第十一步：写工具两种模式都可用（任务进度更新是日常对话里的高频业务动作）
        this.availableTools = List.of(assistantKnowledgeBaseTool, structuredDataQueryTool, currentTimeTool,
                webSearchTool, assistantTaskProgressWriteTool);
        this.chatTools = List.of(currentTimeTool, webSearchTool, assistantTaskProgressWriteTool);
    }

    /**
     * 创建配置好的 ReactAgent 实例。
     * <p>根据指定的 instruction、工具模式和知识库组 ID 构建 Agent，
     * CHAT 模式下仅配置对话能力，KB_SEARCH 模式下额外注入知识库检索工具及其上下文。</p>
     * <p>Agent 配置了短期记忆 Hook、检查点保存器和递归上限，
     * 确保单次调用能完成"模型推理 → 工具执行 → 最终回复"的完整链路。</p>
     *
     * @param instruction  系统提示词，设定 Agent 的角色和行为规范
     * @param toolMode     工具模式（CHAT 仅对话 / KB_SEARCH 知识库检索）
     * @param groupIds    知识库组 ID 列表，仅 KB_SEARCH 模式生效，CHAT 模式可传空列表
     * @param resultHolder 知识库检索结果持有器，用于跨步骤传递检索引用
     * @return 构建完成的 ReactAgent 实例
     */
    public ReactAgent createAgent(
            String instruction,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            AssistantKnowledgeBaseToolResultHolder resultHolder,
            Long currentUserId
    ) {
        return createAgent(instruction, toolMode, groupIds, resultHolder, currentUserId, null, null, null, 0L);
    }

    /** 模型覆盖 + 循环状态的旧入口（无写提案上下文——测试/历史路径兼容）。 */
    public ReactAgent createAgent(
            String instruction,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            AssistantKnowledgeBaseToolResultHolder resultHolder,
            Long currentUserId,
            ChatModel modelOverride,
            com.argus.rag.assistant.loop.TaskLoopState loopState
    ) {
        return createAgent(instruction, toolMode, groupIds, resultHolder, currentUserId,
                modelOverride, loopState, null, 0L);
    }

    /**
     * 支持模型覆盖的创建入口：{@code modelOverride} 非空时使用
     * 用户级/平台级 BYOK 模型（UserChatModelService 解析），否则用全局 Bean。
     *
     * @param proposalHolder 写提案持有器（第十一步：写工具登记提案 → 响应装配读取）
     * @param sessionId      会话 ID（提案关联展示用；0=无会话）
     */
    public ReactAgent createAgent(
            String instruction,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            AssistantKnowledgeBaseToolResultHolder resultHolder,
            Long currentUserId,
            ChatModel modelOverride,
            com.argus.rag.assistant.loop.TaskLoopState loopState,
            AssistantProposalHolder proposalHolder,
            Long sessionId
    ) {
        ChatModel effectiveModel = modelOverride != null ? modelOverride : chatModel;
        com.alibaba.cloud.ai.graph.agent.Builder builder = ReactAgent.builder()
                .name("assistant_chat_agent")
                .model(effectiveModel)
                // AMD 等严格网关兼容：不设 systemPrompt/instruction（框架会把 instruction 转 UserMessage
                // 前插、systemPrompt 转 SystemMessage，但两者与 Hook 的 System/User 块交错时顺序不可控）。
                // 改为零 System 策略：instruction 全文由 Hook 以首条 UserMessage 注入（见 below metadata），
                // 所有消息都是 User/Assistant 交替，任何网关都能接受。
                .hooks(assistantShortTermMemoryHook, decisionAuditHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(loopState != null
                                ? loopState.graphRecursionLimit()
                                : AGENT_RECURSION_LIMIT)
                        .build())
                // MemorySaver 只负责图运行态的 checkpoint，不是正式业务记忆的事实源。
                .saver(new MemorySaver());
        // 两种模式共用的服务端锚定上下文：身份/循环状态/提案持有器——模型不可伪造
        Map<String, Object> context = new HashMap<>();
        context.put(AssistantKnowledgeBaseTool.USER_ID_CONTEXT_KEY, currentUserId);
        context.put(com.argus.rag.assistant.agent.ToolExecutionGuard.LOOP_STATE_CONTEXT_KEY, loopState);
        context.put("proposalHolder", proposalHolder);
        context.put("sessionId", sessionId == null ? 0L : sessionId);
        if (toolMode == AssistantToolMode.KB_SEARCH) {
            context.put(AssistantKnowledgeBaseTool.GROUP_IDS_CONTEXT_KEY, groupIds);
            if (groupIds != null && !groupIds.isEmpty()) {
                context.put(AssistantKnowledgeBaseTool.GROUP_ID_CONTEXT_KEY, groupIds.get(0));
            }
            context.put(AssistantKnowledgeBaseTool.RESULT_HOLDER_CONTEXT_KEY, resultHolder);
            builder.methodTools(availableTools.toArray())
                    .toolContext(context);
        } else {
            builder.methodTools(chatTools.toArray())
                    .toolContext(context);
        }
        return builder.build();
    }
}
