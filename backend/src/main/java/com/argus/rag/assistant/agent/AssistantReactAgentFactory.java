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
    private static final int AGENT_RECURSION_LIMIT = 10;

    private final ChatModel chatModel;
    private final AssistantShortTermMemoryHook assistantShortTermMemoryHook;
    /** Spring 自动收集所有声明了 @Tool 方法的 Bean */
    private final List<Object> availableTools;
    /** CHAT 模式可用工具：仅实时搜索与时间，不触碰知识库/结构化数据上下文 */
    private final List<Object> chatTools;

    public AssistantReactAgentFactory(
            ChatModel chatModel,
            AssistantShortTermMemoryHook assistantShortTermMemoryHook,
            AssistantKnowledgeBaseTool assistantKnowledgeBaseTool,
            StructuredDataQueryTool structuredDataQueryTool,
            CurrentTimeTool currentTimeTool,
            WebSearchTool webSearchTool
    ) {
        this.chatModel = chatModel;
        this.assistantShortTermMemoryHook = assistantShortTermMemoryHook;
        this.availableTools = List.of(assistantKnowledgeBaseTool, structuredDataQueryTool, currentTimeTool, webSearchTool);
        this.chatTools = List.of(currentTimeTool, webSearchTool);
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
        com.alibaba.cloud.ai.graph.agent.Builder builder = ReactAgent.builder()
                .name("assistant_chat_agent")
                .model(chatModel)
                .instruction(instruction)
                .hooks(assistantShortTermMemoryHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(AGENT_RECURSION_LIMIT)
                        .build())
                // MemorySaver 只负责图运行态的 checkpoint，不是正式业务记忆的事实源。
                .saver(new MemorySaver());
        if (toolMode == AssistantToolMode.KB_SEARCH) {
            Map<String, Object> context = new HashMap<>();
            context.put(AssistantKnowledgeBaseTool.GROUP_IDS_CONTEXT_KEY, groupIds);
            if (groupIds != null && !groupIds.isEmpty()) {
                context.put(AssistantKnowledgeBaseTool.GROUP_ID_CONTEXT_KEY, groupIds.get(0));
            }
            context.put(AssistantKnowledgeBaseTool.RESULT_HOLDER_CONTEXT_KEY, resultHolder);
            context.put(AssistantKnowledgeBaseTool.USER_ID_CONTEXT_KEY, currentUserId);
            builder.methodTools(availableTools.toArray())
                    .toolContext(context);
        } else {
            builder.methodTools(chatTools.toArray())
                    .toolContext(new HashMap<>());
        }
        return builder.build();
    }
}
