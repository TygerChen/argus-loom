package com.argus.rag.assistant.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.argus.rag.assistant.memory.longterm.MemoryContextBuilder;
import com.argus.rag.assistant.memory.longterm.MemoryRetriever;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.message.AssistantMessageVO;
import com.argus.rag.assistant.service.AssistantConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 助手短期记忆 Hook。
 * <p>作为 ReactAgent 的 BEFORE_MODEL Hook，在每次模型调用前组装上下文消息。</p>
 * <p>通过从数据库中加载会话的紧凑摘要、会话记忆和最近消息，构建发送给 LLM 的消息列表，
 * 实现短期记忆的自动注入。</p>
 */
@Component
@HookPositions({HookPosition.BEFORE_MODEL})
public class AssistantShortTermMemoryHook extends MessagesModelHook {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AssistantShortTermMemoryHook.class);
    /** 加载最近消息的最大条数 */
    private static final int RECENT_MESSAGE_LIMIT = 10;
    /** 触发运行时压缩的 token 阈值 */
    private static final int RUNTIME_TOKEN_THRESHOLD = 50000;

    /** 第四步（组装上下文）：任务循环状态注入开关（argus.rag.assistant.loop.enabled）。
     *  默认 true：纯单元测试无 Spring 容器时 @Value 不生效，需保证缺省行为可用。 */
    @org.springframework.beans.factory.annotation.Value("${argus.rag.assistant.loop.enabled:true}")
    private boolean loopEnabled = true;

    private final AssistantConversationService assistantConversationService;
    private final MemoryRetriever memoryRetriever;
    private final MemoryContextBuilder memoryContextBuilder;

    public AssistantShortTermMemoryHook(
            AssistantConversationService assistantConversationService,
            MemoryRetriever memoryRetriever,
            MemoryContextBuilder memoryContextBuilder
    ) {
        this.assistantConversationService = assistantConversationService;
        this.memoryRetriever = memoryRetriever;
        this.memoryContextBuilder = memoryContextBuilder;
    }

    @Override
    public String getName() {
        return "assistant_short_term_memory_hook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        if (previousMessages == null || previousMessages.isEmpty() || config == null) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        // 第四步（组装上下文）：任务循环状态——每轮模型调用前先登记轮次、增量摘要工具结果。
        // 状态登记不受后续任何透传分支影响（预算统计必须覆盖每一轮模型调用）。
        com.argus.rag.assistant.loop.TaskLoopState loopState = metadataAsLoopState(config);
        if (loopState != null && loopEnabled) {
            digestToolResponses(previousMessages, loopState);
            loopState.nextRound();
        }
        // 工具调用后的轮次必须保持标准 tool message 结构
        // （用户消息 UserMessage → 助手消息 AssistantMessage(tool_calls) → 工具返回 ToolResponseMessage），
        // 直接透传，避免重建后丢失 tool_calls 关联导致模型反复调用工具。
        // 状态已在上面的 digest 中更新；状态块将在下一轮（决策轮）随重建上下文注入。
        if (hasNonBlankToolResponse(previousMessages)) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        Long userId = metadataAsLong(config, "userId").orElse(null);
        Long sessionId = metadataAsLong(config, "sessionId").orElse(null);
        AssistantToolMode toolMode = metadataAsToolMode(config, "toolMode").orElse(null);
        if (userId == null || sessionId == null || toolMode == null) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        String currentQuestion = extractCurrentQuestion(previousMessages);
        if (currentQuestion == null) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        // AMD 等严格网关兼容：instruction 以首条 UserMessage 注入（零 System 策略）。
        String agentInstruction = metadataAsString(config, "agentInstruction");
        Long groupId = metadataAsLong(config, "groupId").orElse(null);
        if (log.isDebugEnabled()) {
            log.debug(
                    "ShortTermMemoryHook.beforeModel input. userId={}, sessionId={}, toolMode={}, groupId={}, loopRound={}, previousMessages={}",
                    userId,
                    sessionId,
                    toolMode,
                    groupId,
                    loopState == null ? -1 : loopState.currentRound(),
                    summarizeMessages(previousMessages)
            );
        }
        // 这里不是在原 messages 上做增量补丁，而是直接重建一份真正送进模型的上下文：
        // 组装顺序：紧凑摘要(compact summary) -> 会话记忆(session memory) -> 近期消息(recent messages) -> 当前问题(current question)。
        List<Message> assembledMessages = assembleBeforeModelMessages(
                userId,
                sessionId,
                toolMode,
                groupId,
                currentQuestion,
                previousMessages
        );
        // AMD 等严格网关兼容（零 System 策略）：instruction 以首条 UserMessage 注入。
        if (agentInstruction != null && !agentInstruction.isBlank()) {
            assembledMessages.add(0, new UserMessage(
                    ("【系统指令·请严格遵循】" + System.lineSeparator() + agentInstruction).trim()));
        }
        // 第四步（组装上下文）：任务状态与预算块注入——在当前问题之前插入，
        // 让模型在每次决策轮都看到轮次/剩余预算/已执行工具摘要/退出条件。
        if (loopState != null && loopEnabled) {
            insertStateBlock(assembledMessages, loopState);
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "ShortTermMemoryHook.beforeModel output. userId={}, sessionId={}, toolMode={}, groupId={}, currentQuestion={}, assembledMessages={}",
                    userId,
                    sessionId,
                    toolMode,
                    groupId,
                    currentQuestion,
                    summarizeMessages(assembledMessages)
            );
        }
        return new AgentCommand(
                assembledMessages,
                UpdatePolicy.REPLACE
        );
    }

    private static boolean hasNonBlankToolResponse(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            if (toolResponseMessage.getResponses() == null || toolResponseMessage.getResponses().isEmpty()) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (response != null && response.responseData() != null && !response.responseData().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 读取运行配置中的任务循环状态（第四步；未注入返回 null）。 */
    private String metadataAsString(RunnableConfig config, String key) {
        try {
            Object value = config.metadata(key).orElse("");
            return value instanceof String text ? text : String.valueOf(value);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private com.argus.rag.assistant.loop.TaskLoopState metadataAsLoopState(RunnableConfig config) {
        try {
            Object value = config.metadata("taskLoopState").orElse(null);
            return value instanceof com.argus.rag.assistant.loop.TaskLoopState state ? state : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 增量摘要工具结果：把 previousMessages 中尚未登记的 ToolResponse 摘要进状态。
     * <p>用状态里已登记条数做游标——同一批响应只在首次出现的那一轮登记一次。</p>
     */
    private void digestToolResponses(List<Message> messages, com.argus.rag.assistant.loop.TaskLoopState loopState) {
        int total = 0;
        java.util.List<String> digests = new java.util.ArrayList<>();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            if (toolResponseMessage.getResponses() == null) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (response == null || response.responseData() == null || response.responseData().isBlank()) {
                    continue;
                }
                total++;
                if (total > loopState.recordedResponseCount()) {
                    digests.add(response.responseData());
                }
            }
        }
        if (total > loopState.recordedResponseCount()) {
            // 工具名不可从 ToolResponse 稳定取得，统一记为 tool（摘要保内容）
            for (String digest : digests) {
                loopState.recordToolResult("tool", digest);
            }
            loopState.markResponsesRecorded(total);
        }
    }

    /**
     * 在当前问题（第一条 UserMessage）之前插入任务状态块。
     * <p>插入而非追加到末尾：状态是"决策前的桌面信息"，与问题相邻模型注意力最集中。</p>
     */
    private static void insertStateBlock(List<Message> messages, com.argus.rag.assistant.loop.TaskLoopState loopState) {
        String block = loopState.asContextBlock();
        if (block == null || block.isBlank()) {
            return;
        }
        SystemMessage stateMessage = new SystemMessage(block);
        int insertAt = messages.size();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                insertAt = i;
                break;
            }
        }
        messages.add(insertAt, stateMessage);
    }

    /**
     * 兼容旧版 Agent 框架的 beforeModel 重载。
     * <p>该方法仅透传输入，不做任何处理，确保与旧版 API 兼容。</p>
     *
     * @param ignored 输入对象，直接返回
     * @return 与输入相同的对象
     */
    public Object beforeModel(Object ignored) {
        return ignored;
    }

    /**
     * 组装模型调用前的消息列表（无运行时消息的便捷方法）。
     * <p>等价于 {@code assembleBeforeModelMessages(userId, sessionId, toolMode, groupId, currentQuestion, List.of())}。</p>
     *
     * @param userId          用户 ID
     * @param sessionId       会话 ID
     * @param toolMode        当前工具模式
     * @param groupId         知识库组 ID
     * @param currentQuestion 当前用户问题
     * @return 组装后的消息列表
     */
    public List<Message> assembleBeforeModelMessages(
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            String currentQuestion
    ) {
        return assembleBeforeModelMessages(
                userId,
                sessionId,
                toolMode,
                groupId,
                currentQuestion,
                List.of()
        );
    }

    /**
     * 组装模型调用前的消息列表（完整参数版本）。
     * <p>按以下顺序构建发送给 LLM 的消息列表：</p>
     * <ol>
     *   <li>紧凑摘要（作为系统消息注入）</li>
     *   <li>会话记忆（作为系统消息注入）</li>
     *   <li>最近的对话历史</li>
     *   <li>运行时工具消息</li>
     *   <li>当前用户问题</li>
     * </ol>
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param toolMode         当前工具模式
     * @param groupId          知识库组 ID
     * @param currentQuestion  当前用户问题
     * @param runtimeMessages  运行时工具返回的消息列表
     * @return 组装后的消息列表
     */
    public List<Message> assembleBeforeModelMessages(
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            String currentQuestion,
            List<Message> runtimeMessages
    ) {
        List<Message> messages = new ArrayList<>();
        AssistantConversationService.AssistantConversationContext conversationContext =
                assistantConversationService.loadConversationContext(userId, sessionId, RECENT_MESSAGE_LIMIT);
        // 0. 长期记忆（跨会话，优先注入）
        String longTermContext = buildLongTermMemoryContext(userId, groupId, currentQuestion);
        if (longTermContext != null && !longTermContext.isBlank()) {
            // 同上：System→User 兼容严格网关（AMD 等 System 只能在第 0 位的限制）
            messages.add(new UserMessage(("【长期记忆·系统注入】" + System.lineSeparator()
                    + longTermContext).trim()));
        }
        // compact summary 和 session memory 被降级成系统消息注入，
        // 目的是让模型先看到压缩后的会话状态，再看最近几轮原始消息。
        // 1. 紧凑摘要（作为系统消息）
        addSystemMemory(messages, "compact summary", conversationContext.compactSummary());
        // 2. 会话记忆（作为系统消息）
        addSystemMemory(messages, "session memory", conversationContext.sessionMemory());
        // 3. 最近的对话历史
        appendRecentMessages(messages, conversationContext.recentMessages(), currentQuestion);
        // 4. 运行时工具消息（Agent 图执行过程中产生的 ToolResponseMessage）
        appendRuntimeToolMessages(messages, runtimeMessages);
        // 5. 当前用户问题
        messages.add(new UserMessage(currentQuestion));
        return messages;
    }

    /**
     * 判断是否需要运行时压缩。
     * <p>当估算 token 数超过阈值 {@value #RUNTIME_TOKEN_THRESHOLD} 时，认为需要触发运行时压缩。</p>
     *
     * @param estimatedTokens 估算的 token 数
     * @return 需要运行时压缩时返回 {@code true}
     */
    public boolean shouldRuntimeCompact(int estimatedTokens) {
        return estimatedTokens > RUNTIME_TOKEN_THRESHOLD;
    }

    /**
     * 执行运行时压缩。
     * <p>保留消息列表末尾最多 3 条消息，丢弃更早的消息以控制 token 数量。</p>
     *
     * @param messages 待压缩的消息列表
     * @return 压缩后的消息列表，若输入为空则返回空列表
     */
    public List<Message> runtimeCompact(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int keepCount = Math.min(3, messages.size());
        return new ArrayList<>(messages.subList(messages.size() - keepCount, messages.size()));
    }

    private void addSystemMemory(List<Message> messages, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        // AMD 等严格网关要求 System 消息只能在第 0 位；框架把 instruction 转成 UserMessage
        // 前插后，这里的 System 块会落在 User 之后触发 400。统一改为带标签的 UserMessage——
        // 语义不变（模型照样遵循），对宽松/严格网关都兼容。
        messages.add(new UserMessage(("【" + label + "·系统注入，请遵循】" + System.lineSeparator()
                + content).trim()));
    }

    private void appendRecentMessages(
            List<Message> messages,
            List<AssistantMessageVO> recentMessages,
            String currentQuestion
    ) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return;
        }
        int lastIndex = recentMessages.size() - 1;
        for (int index = 0; index < recentMessages.size(); index++) {
            AssistantMessageVO recentMessage = recentMessages.get(index);
            if (recentMessage == null || recentMessage.content() == null || recentMessage.content().isBlank()) {
                continue;
            }
            boolean isCurrentQuestionEcho = index == lastIndex
                    && recentMessage.role() == com.argus.rag.assistant.model.enums.AssistantMessageRole.USER
                    && currentQuestion.equals(recentMessage.content().trim());
            if (isCurrentQuestionEcho) {
                // 当前问题会在 assembleBeforeModelMessages 的最后重新追加，这里跳过数据库里的回显，避免重复喂给模型。
                continue;
            }
            switch (recentMessage.role()) {
                case USER -> messages.add(new UserMessage(formatRecentMessage(recentMessage)));
                case ASSISTANT -> messages.add(new AssistantMessage(formatRecentMessage(recentMessage)));
                case TOOL -> messages.add(new AssistantMessage(formatToolMessage(recentMessage.content())));
            }
        }
    }

    private void appendRuntimeToolMessages(List<Message> messages, List<Message> runtimeMessages) {
        if (runtimeMessages == null || runtimeMessages.isEmpty()) {
            return;
        }
        for (Message runtimeMessage : runtimeMessages) {
            if (!(runtimeMessage instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            if (toolResponseMessage.getResponses() == null || toolResponseMessage.getResponses().isEmpty()) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (response == null || response.responseData() == null || response.responseData().isBlank()) {
                    continue;
                }
                messages.add(new AssistantMessage(formatToolMessage(response.responseData())));
            }
        }
    }

    private static String formatRecentMessage(AssistantMessageVO message) {
        String mode = message.toolMode() == null ? "UNKNOWN" : message.toolMode().name();
        return ("[历史消息 | 模式：" + mode + "]" + System.lineSeparator() + message.content()).trim();
    }

    private static String formatToolMessage(String content) {
        return ("[工具观察]" + System.lineSeparator() + content).trim();
    }

    private Optional<Long> metadataAsLong(RunnableConfig config, String key) {
        try {
            return config.metadata(key)
                    .map(value -> {
                    if (value instanceof Number number) {
                        return number.longValue();
                    }
                    if (value instanceof String stringValue && !stringValue.isBlank()) {
                        return Long.parseLong(stringValue);
                    }
                    return null;
                });
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<AssistantToolMode> metadataAsToolMode(RunnableConfig config, String key) {
        try {
            return config.metadata(key)
                    .map(Object::toString)
                    .filter(value -> !value.isBlank())
                    .map(AssistantToolMode::valueOf);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String extractCurrentQuestion(List<Message> previousMessages) {
        for (int index = previousMessages.size() - 1; index >= 0; index--) {
            Message message = previousMessages.get(index);
            if (message instanceof UserMessage) {
                UserMessage userMessage = (UserMessage) message;
                String text = userMessage.getText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private static String summarizeMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<String> summaries = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message == null) {
                summaries.add(index + ":null");
                continue;
            }
            String type = message.getClass().getSimpleName();
            String text = message.getText();
            String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\n', ' ').trim();
            if (normalized.length() > 200) {
                normalized = normalized.substring(0, 200) + "...";
            }
            summaries.add(index + ":" + type + "[" + normalized + "]");
        }
        return summaries.toString();
    }

    /**
     * 构建长期记忆上下文。
     *
     * <p>检索与当前问题相关的跨会话用户记忆，并构建为可注入模型的上下文文本。
     * 使用 MemoryRetriever 进行多因子排序检索，MemoryContextBuilder 格式化输出。</p>
     *
     * <p>注意：tenantId 使用 groupId 作为多租户隔离标识，groupId 为空时跳过检索。</p>
     *
     * @param userId          用户 ID
     * @param groupId         知识库组 ID（用作 tenantId）
     * @param currentQuestion 当前用户问题
     * @return 格式化的记忆上下文文本，若无可检索记忆则返回空字符串
     */
    private String buildLongTermMemoryContext(Long userId, Long groupId, String currentQuestion) {
        if (userId == null || groupId == null || currentQuestion == null || currentQuestion.isBlank()) {
            return "";
        }
        try {
            var scoredMemories = memoryRetriever.search(userId, groupId, currentQuestion, 5);
            return memoryContextBuilder.build(scoredMemories);
        } catch (Exception e) {
            log.warn("长期记忆检索失败（不影响主流程）: userId={}, groupId={}, error={}",
                    userId, groupId, e.getMessage());
            return "";
        }
    }
}
