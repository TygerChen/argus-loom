package com.argus.rag.assistant.service;

import com.argus.rag.assistant.agent.AssistantAgentFacade;
import com.argus.rag.assistant.model.dto.chat.AssistantChatRequest;
import com.argus.rag.assistant.model.dto.message.AssistantMessageCreateDTO;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.model.vo.chat.AssistantChatResponse;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;
import com.argus.rag.assistant.model.vo.message.AssistantMessageVO;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.qa.route.KnowledgeGroupRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.argus.rag.metrics.LlmEndpoint;
import com.argus.rag.metrics.LlmModule;
import com.argus.rag.metrics.collector.LlmUsageCollector;
import com.argus.rag.metrics.cost.LlmCostCalculator;
import com.argus.rag.metrics.model.dto.LlmUsageRecordDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

/**
 * 助手核心服务。
 * <p>编排聊天流程，包括消息验证、用户消息持久化、Agent 调用、助手回复持久化等。</p>
 * <p>KB_SEARCH 模式支持手动选择多个知识库（SELECTED）或按问题自动路由（AUTO）。</p>
 *
 * <p><b>与 qa 模块的关系</b>：qa 是"单轮有据问答"（每次都检索、强制引用）；
 * assistant 是"多轮对话 + 工具"（Agent 模式，可以自主决定查不查知识库）。
 * 本类是 assistant 的编排中枢：校验请求 → 存用户消息 → 组装会话上下文 →
 * 调 AgentFacade（它再决定调模型还是调工具）→ 存助手回复 → 记 LLM 用量。</p>
 *
 * <p><b>用量记账就发生在这里</b>：每次调用后把 prompt/completion token 数
 * 交给 LlmUsageCollector（异步落库），再用 LlmCostCalculator 折算成本——
 * 09 篇监控大盘的"这个月花了多少钱"就是这么来的。TOKEN_ESTIMATE_DIVISOR=4
 * 是粗估公式（约 4 字符 = 1 token），估算值会打 isEstimated 标记，
 * 统计口径里"精确"和"估算"分开对待。</p>
 *
 * <p><b>流式输出</b>：streamChat 用 Consumer&lt;AssistantChatStreamEvent&gt;
 * 把生成过程逐段回调给上层（SSE 推给前端，打字机效果）。
 * 回调函数式接口而不是返回完整结果，是"生产者边产边交"的标准写法。</p>
 */
@Service
public class AssistantService {

    private final AssistantConversationService assistantConversationService;
    private final AssistantAgentFacade assistantAgentFacade;
    private final GroupMembershipService groupMembershipService;
    private final CurrentUserService currentUserService;
    private final KnowledgeGroupRouter knowledgeGroupRouter;
    private final ObjectMapper objectMapper;
    private final LlmUsageCollector llmUsageCollector;
    private final LlmCostCalculator llmCostCalculator;

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String MODEL_NAME = "qwen-plus";
    private static final int TOKEN_ESTIMATE_DIVISOR = 4;

    public AssistantService(
            AssistantConversationService assistantConversationService,
            AssistantAgentFacade assistantAgentFacade,
            GroupMembershipService groupMembershipService,
            CurrentUserService currentUserService,
            KnowledgeGroupRouter knowledgeGroupRouter,
            ObjectMapper objectMapper,
            LlmUsageCollector llmUsageCollector,
            LlmCostCalculator llmCostCalculator
    ) {
        this.assistantConversationService = assistantConversationService;
        this.assistantAgentFacade = assistantAgentFacade;
        this.groupMembershipService = groupMembershipService;
        this.currentUserService = currentUserService;
        this.knowledgeGroupRouter = knowledgeGroupRouter;
        this.objectMapper = objectMapper;
        this.llmUsageCollector = llmUsageCollector;
        this.llmCostCalculator = llmCostCalculator;
    }

    /**
     * 同步聊天入口。
     */
    @Transactional
    public AssistantChatResponse chat(HttpServletRequest request, AssistantChatRequest chatRequest) {
        long startTime = System.currentTimeMillis();
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        AssistantChatRequest safeRequest = requireChatRequest(chatRequest);
        List<Long> groupIds = resolveKnowledgeScope(safeRequest, currentUser.userId());

        String reply = null;
        boolean success = false;
        String errorMessage = null;
        try {
            saveUserMessage(currentUser.userId(), safeRequest, groupIds);
            AssistantExecutionResult executionResult = executeAssistant(
                    currentUser.userId(),
                    safeRequest,
                    groupIds
            );
            reply = executionResult.reply();
            AssistantMessageVO assistantMessage = saveAssistantMessage(
                    currentUser.userId(),
                    safeRequest,
                    groupIds,
                    executionResult
            );
            success = true;
            return new AssistantChatResponse(
                    safeRequest.sessionId(),
                    assistantMessage.messageId(),
                    assistantMessage.content(),
                    safeRequest.toolMode(),
                    firstGroupId(groupIds),
                    groupIds,
                    safeRequest.effectiveRouteStrategy(),
                    executionResult.citations()
            );
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("助手调用失败: {}", errorMessage, e);
            throw new BusinessException(ErrorCodes.MODEL_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE_MESSAGE, e);
        } finally {
            recordUsage(
                    currentUser.userId(),
                    firstGroupId(groupIds),
                    safeRequest.sessionId(),
                    LlmEndpoint.ASSISTANT_CHAT,
                    safeRequest.message(),
                    reply,
                    startTime,
                    success,
                    errorMessage
            );
        }
    }

    /**
     * 流式聊天入口（HTTP 版本）。
     */
    @Transactional
    public void streamChat(
            HttpServletRequest request,
            AssistantChatRequest chatRequest,
            AssistantStreamEventEmitter eventEmitter
    ) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        AssistantChatRequest safeRequest = requireChatRequest(chatRequest);
        List<Long> groupIds = resolveKnowledgeScope(safeRequest, currentUser.userId());
        streamChat(safeRequest, eventEmitter, deltaEmitter ->
                assistantAgentFacade.streamChat(
                        currentUser.userId(),
                        safeRequest.sessionId(),
                        safeRequest.toolMode(),
                        groupIds,
                        safeRequest.effectiveRouteStrategy(),
                        safeRequest.message(),
                        deltaEmitter,
                        toolEvent -> eventEmitter.emitToolEvent(toolEvent)
                ));
    }

    /**
     * 流式聊天入口（完整版本）。
     */
    @Transactional
    public void streamChat(
            AssistantChatRequest chatRequest,
            AssistantStreamEventEmitter eventEmitter,
            ChatStreamExecutor streamExecutor
    ) {
        long startTime = System.currentTimeMillis();
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        AssistantChatRequest safeRequest = requireChatRequest(chatRequest);
        List<Long> groupIds = resolveKnowledgeScope(safeRequest, currentUser.userId());

        String reply = null;
        boolean success = false;
        String errorMessage = null;
        try {
            saveUserMessage(currentUser.userId(), safeRequest, groupIds);

            eventEmitter.emit(AssistantChatStreamEvent.start(
                    safeRequest.sessionId(),
                    safeRequest.toolMode(),
                    firstGroupId(groupIds),
                    groupIds,
                    safeRequest.effectiveRouteStrategy()
            ));

            AssistantExecutionResult executionResult = executeAssistantStreaming(
                    currentUser.userId(),
                    safeRequest,
                    groupIds,
                    delta -> eventEmitter.emit(AssistantChatStreamEvent.delta(
                            safeRequest.sessionId(),
                            safeRequest.toolMode(),
                            firstGroupId(groupIds),
                            groupIds,
                            safeRequest.effectiveRouteStrategy(),
                            delta
                    )),
                    streamExecutor
            );
            reply = executionResult.reply();
            AssistantMessageVO assistantMessage = saveAssistantMessage(
                    currentUser.userId(),
                    safeRequest,
                    groupIds,
                    executionResult
            );

            eventEmitter.emit(AssistantChatStreamEvent.done(
                    safeRequest.sessionId(),
                    safeRequest.toolMode(),
                    firstGroupId(groupIds),
                    groupIds,
                    safeRequest.effectiveRouteStrategy(),
                    assistantMessage.messageId(),
                    executionResult.reply(),
                    executionResult.citations()
            ));
            success = true;
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("助手调用失败: {}", errorMessage, e);
            throw new BusinessException(ErrorCodes.MODEL_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE_MESSAGE, e);
        } finally {
            recordUsage(
                    currentUser.userId(),
                    firstGroupId(groupIds),
                    safeRequest.sessionId(),
                    LlmEndpoint.ASSISTANT_CHAT_STREAM,
                    safeRequest.message(),
                    reply,
                    startTime,
                    success,
                    errorMessage
            );
        }
    }

    /**
     * 校验聊天请求参数的合法性。
     */
    private static AssistantChatRequest requireChatRequest(AssistantChatRequest chatRequest) {
        if (chatRequest == null) {
            throw new BusinessException("聊天请求不能为空");
        }
        if (chatRequest.toolMode() == AssistantToolMode.CHAT
                && (!chatRequest.effectiveGroupIds().isEmpty() || chatRequest.groupId() != null)) {
            throw new BusinessException("CHAT 模式不允许传 groupId");
        }
        if (chatRequest.toolMode() == AssistantToolMode.KB_SEARCH
                && chatRequest.effectiveRouteStrategy() == KbRouteStrategy.SELECTED
                && chatRequest.effectiveGroupIds().isEmpty()) {
            throw new BusinessException("KB_SEARCH 手动模式必须至少传一个 groupId");
        }
        return chatRequest;
    }

    /**
     * 解析本次请求实际检索的知识库范围：SELECTED 直接使用显式范围，AUTO 调用轻量路由。
     */
    private List<Long> resolveKnowledgeScope(AssistantChatRequest safeRequest, Long userId) {
        if (safeRequest.toolMode() != AssistantToolMode.KB_SEARCH) {
            return List.of();
        }
        requireKnowledgeBaseReadableIfNeeded(safeRequest);
        List<Long> explicitGroupIds = safeRequest.effectiveGroupIds();
        if (safeRequest.effectiveRouteStrategy() != KbRouteStrategy.AUTO) {
            return explicitGroupIds;
        }
        List<Long> routedGroupIds = knowledgeGroupRouter.route(userId, safeRequest.message(), explicitGroupIds).stream()
                .map(KnowledgeGroupRouter.RoutedKnowledgeGroup::groupId)
                .toList();
        if (routedGroupIds.isEmpty()) {
            throw new BusinessException("当前没有可用于检索的知识库，请先在知识库管理中上传文档。");
        }
        return routedGroupIds;
    }

    /**
     * 执行同步 Agent 调用。
     */
    private AssistantExecutionResult executeAssistant(
            Long userId,
            AssistantChatRequest safeRequest,
            List<Long> groupIds
    ) {
        AssistantAgentResult agentResult = assistantAgentFacade.chat(
                userId,
                safeRequest.sessionId(),
                safeRequest.toolMode(),
                groupIds,
                safeRequest.effectiveRouteStrategy(),
                safeRequest.message()
        );
        return new AssistantExecutionResult(agentResult.reply(), null, agentResult.citations());
    }

    /**
     * 执行流式 Agent 调用。
     */
    private AssistantExecutionResult executeAssistantStreaming(
            Long userId,
            AssistantChatRequest safeRequest,
            List<Long> groupIds,
            Consumer<String> deltaConsumer,
            ChatStreamExecutor streamExecutor
    ) {
        AssistantAgentResult agentResult = streamExecutor.execute(deltaConsumer);
        return new AssistantExecutionResult(agentResult.reply(), null, agentResult.citations());
    }

    private void saveUserMessage(Long userId, AssistantChatRequest safeRequest, List<Long> groupIds) {
        assistantConversationService.saveUserMessage(
                userId,
                new AssistantMessageCreateDTO(
                        safeRequest.sessionId(),
                        safeRequest.toolMode(),
                        firstGroupId(groupIds),
                        groupIds,
                        safeRequest.effectiveRouteStrategy(),
                        safeRequest.message(),
                        null
                )
        );
    }

    private AssistantMessageVO saveAssistantMessage(
            Long userId,
            AssistantChatRequest safeRequest,
            List<Long> groupIds,
            AssistantExecutionResult executionResult
    ) {
        return assistantConversationService.saveAssistantMessage(
                userId,
                new AssistantMessageCreateDTO(
                        safeRequest.sessionId(),
                        safeRequest.toolMode(),
                        firstGroupId(groupIds),
                        groupIds,
                        safeRequest.effectiveRouteStrategy(),
                        executionResult.reply(),
                        executionResult.structuredPayload()
                )
        );
    }

    /**
     * 显式指定范围时校验可读权限。
     */
    private void requireKnowledgeBaseReadableIfNeeded(AssistantChatRequest safeRequest) {
        if (safeRequest.toolMode() != AssistantToolMode.KB_SEARCH) {
            return;
        }
        for (Long groupId : safeRequest.effectiveGroupIds()) {
            groupMembershipService.requireGroupReadable(groupId);
        }
    }

    private static Long firstGroupId(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return null;
        }
        return groupIds.get(0);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / TOKEN_ESTIMATE_DIVISOR;
    }

    private void recordUsage(
            Long userId,
            Long groupId,
            Long sessionId,
            String endpoint,
            String userMessage,
            String reply,
            long startTime,
            boolean success,
            String errorMessage
    ) {
        try {
            long latencyMs = System.currentTimeMillis() - startTime;
            int promptTokens = estimateTokens(userMessage);
            int completionTokens = estimateTokens(reply);
            int totalTokens = promptTokens + completionTokens;

            BigDecimal costAmount = llmCostCalculator.calculate(MODEL_NAME, promptTokens, completionTokens);

            LlmUsageRecordDTO record = LlmUsageRecordDTO.builder()
                    .userId(userId)
                    .groupId(groupId)
                    .module(LlmModule.ASSISTANT)
                    .endpoint(endpoint)
                    .sessionId(sessionId != null ? sessionId.toString() : null)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .isEstimated(true)
                    .costAmount(costAmount)
                    .latencyMs(latencyMs)
                    .success(success)
                    .errorMessage(errorMessage)
                    .modelName(MODEL_NAME)
                    .build();

            llmUsageCollector.record(record);

            if (log.isDebugEnabled()) {
                log.debug("Assistant LLM 用量已记录: endpoint={}, success={}, promptTokens={}, completionTokens={}, totalTokens={}, latencyMs={}",
                        endpoint, success, promptTokens, completionTokens, totalTokens, latencyMs);
            }
        } catch (Exception e) {
            log.warn("Assistant LLM 用量记录失败，不影响主流程: {}", e.getMessage(), e);
        }
    }

    private record AssistantExecutionResult(
            String reply,
            String structuredPayload,
            List<com.argus.rag.qa.model.vo.AskQuestionResponse.Citation> citations
    ) {
    }

    @FunctionalInterface
    public interface ChatStreamExecutor {

        /**
         * 执行流式 Agent 调用。
         */
        AssistantAgentResult execute(Consumer<String> deltaConsumer);
    }
}
