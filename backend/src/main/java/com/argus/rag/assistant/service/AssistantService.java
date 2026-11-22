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
    /** 第二步（理解任务）：语义理解产出注入 Agent 指令；失败 fail-soft。 */
    private final com.argus.rag.understanding.TaskUnderstandingService taskUnderstandingService;
    /** 第三步（制定方案）：经代码校验的执行计划注入 Agent 指令；失败走代码兜底方案。 */
    private final com.argus.rag.planning.TaskPlanningService taskPlanningService;
    /** 第八步（验证结果）：生成后独立校验（裁判与生成者两次独立 LLM 调用）；失败 fail-soft。 */
    private final com.argus.rag.verify.AnswerVerifier answerVerifier;
    /** 第十步（收尾与留痕）：任务轨迹落库（异步 fail-soft）。 */
    private final com.argus.rag.assistant.trace.TaskTraceRecorder taskTraceRecorder;

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
            LlmCostCalculator llmCostCalculator,
            com.argus.rag.understanding.TaskUnderstandingService taskUnderstandingService,
            com.argus.rag.planning.TaskPlanningService taskPlanningService,
            com.argus.rag.verify.AnswerVerifier answerVerifier,
            com.argus.rag.assistant.trace.TaskTraceRecorder taskTraceRecorder
    ) {
        this.assistantConversationService = assistantConversationService;
        this.assistantAgentFacade = assistantAgentFacade;
        this.groupMembershipService = groupMembershipService;
        this.currentUserService = currentUserService;
        this.knowledgeGroupRouter = knowledgeGroupRouter;
        this.objectMapper = objectMapper;
        this.llmUsageCollector = llmUsageCollector;
        this.llmCostCalculator = llmCostCalculator;
        this.taskUnderstandingService = taskUnderstandingService;
        this.taskPlanningService = taskPlanningService;
        this.answerVerifier = answerVerifier;
        this.taskTraceRecorder = taskTraceRecorder;
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
        // 入口段产出（第二步理解 + 第三步方案）：进入 Agent 前各计算一次（图重试不重复计算）。
        // 理解失败 fail-soft；方案失败走代码兜底计划书——第四步永远有可执行输入。
        com.argus.rag.understanding.TaskUnderstanding understanding =
                taskUnderstandingService.understand(safeRequest.message(), groupIds, safeRequest.toolMode());
        com.argus.rag.planning.ExecutionPlan plan = taskPlanningService.plan(
                safeRequest.message(), understanding, groupIds, safeRequest.toolMode());
        // 挂号台任务单贯穿：把入口票据快照（风险/动作/身份声明标记）收进任务上下文
        com.argus.rag.common.task.EntryAuditSupport.TicketSnapshot ticket =
                com.argus.rag.common.task.EntryAuditSupport.snapshot(request);
        com.argus.rag.assistant.model.AssistantTaskContext taskContext =
                new com.argus.rag.assistant.model.AssistantTaskContext(understanding, plan, ticket);
        try {
            saveUserMessage(currentUser.userId(), safeRequest, groupIds);
            AssistantExecutionResult executionResult = executeAssistant(
                    currentUser.userId(),
                    safeRequest,
                    groupIds,
                    taskContext
            );
            DeliveryOutcome outcome = annotateIfNeeded(safeRequest, executionResult, plan, understanding);
            reply = outcome.reply();
            recordTaskTrace(safeRequest, executionResult, outcome, startTime);
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
                    executionResult.citations(),
                    executionResult.pendingProposal()
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
        // 入口段产出（第二步理解 + 第三步方案）：HTTP 流式入口计算一次，随 lambda 捕获传给门面。
        com.argus.rag.understanding.TaskUnderstanding understanding =
                taskUnderstandingService.understand(safeRequest.message(), groupIds, safeRequest.toolMode());
        com.argus.rag.planning.ExecutionPlan plan = taskPlanningService.plan(
                safeRequest.message(), understanding, groupIds, safeRequest.toolMode());
        com.argus.rag.common.task.EntryAuditSupport.TicketSnapshot ticket =
                com.argus.rag.common.task.EntryAuditSupport.snapshot(request);
        com.argus.rag.assistant.model.AssistantTaskContext taskContext =
                new com.argus.rag.assistant.model.AssistantTaskContext(understanding, plan, ticket);
        streamChat(safeRequest, eventEmitter, deltaEmitter ->
                assistantAgentFacade.streamChat(
                        currentUser.userId(),
                        safeRequest.sessionId(),
                        safeRequest.toolMode(),
                        groupIds,
                        safeRequest.effectiveRouteStrategy(),
                        safeRequest.message(),
                        taskContext,
                        deltaEmitter,
                        toolEvent -> eventEmitter.emitToolEvent(toolEvent)
                ), taskContext);
    }

    /**
     * 流式聊天入口（完整版本）。
     */
    @Transactional
    public void streamChat(
            AssistantChatRequest chatRequest,
            AssistantStreamEventEmitter eventEmitter,
            ChatStreamExecutor streamExecutor,
            com.argus.rag.assistant.model.AssistantTaskContext taskContext
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
            DeliveryOutcome outcome = annotateIfNeeded(safeRequest, executionResult,
                    taskContext == null ? null : taskContext.plan(),
                    taskContext == null ? null : taskContext.understanding());
            reply = outcome.reply();
            recordTaskTrace(safeRequest, executionResult, outcome, startTime);
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
                    executionResult.citations(),
                    executionResult.pendingProposal()
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
            List<Long> groupIds,
            com.argus.rag.assistant.model.AssistantTaskContext taskContext
    ) {
        AssistantAgentResult agentResult = assistantAgentFacade.chat(
                userId,
                safeRequest.sessionId(),
                safeRequest.toolMode(),
                groupIds,
                safeRequest.effectiveRouteStrategy(),
                safeRequest.message(),
                taskContext
        );
        return new AssistantExecutionResult(agentResult.reply(), null, agentResult.citations(),
                agentResult.evidenceDigests(), agentResult.loopState(), agentResult.pendingProposal());
    }

    /**
     * 第八步（验证结果）+ 第九步（返回结果）：KB_SEARCH 模式对最终回复做独立校验
     * 与形态派生——未获支撑时降级标注；冲突/需确认形态追加友好提示。
     * <p>裁判与生成者是两次独立 LLM 调用（不自我背书）；校验失败/关闭→原样返回。
     * CHAT 模式无证据可对照，跳过。</p>
     */
    /** 交付产出：标注后回复 + 结果形态 + 校验结果（第十步留痕的素材）。 */
    private record DeliveryOutcome(
            String reply,
            com.argus.rag.assistant.model.ResultShape shape,
            com.argus.rag.verify.VerificationResult verification
    ) {
    }

    private DeliveryOutcome annotateIfNeeded(AssistantChatRequest safeRequest, AssistantExecutionResult result,
                                             com.argus.rag.planning.ExecutionPlan plan,
                                             com.argus.rag.understanding.TaskUnderstanding understanding) {
        if (safeRequest.toolMode() != AssistantToolMode.KB_SEARCH
                || result.evidenceDigests() == null || result.evidenceDigests().isEmpty()) {
            return new DeliveryOutcome(result.reply(),
                    com.argus.rag.assistant.model.ResultShape.DETERMINED, null);
        }
        com.argus.rag.verify.VerificationResult verification = answerVerifier.verify(
                safeRequest.message(), result.reply(), result.evidenceDigests());
        String reply = answerVerifier.annotate(result.reply(), verification);
        // 第九步（返回结果）：形态派生 + 友好提示（代码仲裁，模型无权决定形态）
        com.argus.rag.assistant.model.ResultShape shape = com.argus.rag.assistant.model.ResultShape.resolve(
                plan, verification, understanding == null ? List.of() : understanding.ambiguities());
        log.info("result.shape: {} (needConfirm={}, verificationEmpty={})",
                shape.name(), plan != null && plan.needConfirm(), verification.isEmpty());
        return new DeliveryOutcome(reply + shape.friendlyNotice(), shape, verification);
    }

    /** 第十步（收尾与留痕）：任务轨迹异步落库（traceId 串联 entry_log 与全链路日志）。 */
    private void recordTaskTrace(AssistantChatRequest safeRequest, AssistantExecutionResult result,
                                 DeliveryOutcome outcome, long startTime) {
        try {
            com.argus.rag.assistant.loop.TaskLoopState loopState = result.loopState();
            com.argus.rag.common.security.AuthenticatedUser user =
                    com.argus.rag.common.security.UserContext.get();
            taskTraceRecorder.record(new com.argus.rag.assistant.trace.TaskTraceRecorder.TaskTraceRecord(
                    org.slf4j.MDC.get(com.argus.rag.common.logging.TraceIdFilter.MDC_TRACE_ID),
                    safeRequest.sessionId(),
                    user != null ? user.userId() : null,
                    user != null && user.systemRole() != null ? user.systemRole().name() : null,
                    com.argus.rag.common.task.TaskChannel.ASSISTANT,
                    safeRequest.message() == null ? 0 : safeRequest.message().length(),
                    loopState,
                    outcome.verification(),
                    outcome.shape(),
                    outcome.reply() == null ? 0 : outcome.reply().length(),
                    System.currentTimeMillis() - startTime
            ));
        } catch (RuntimeException traceFailure) {
            log.warn("任务轨迹组装失败(不影响主流程): {}", traceFailure.getMessage());
        }
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
        return new AssistantExecutionResult(agentResult.reply(), null, agentResult.citations(),
                agentResult.evidenceDigests(), agentResult.loopState(), agentResult.pendingProposal());
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
            List<com.argus.rag.qa.model.vo.AskQuestionResponse.Citation> citations,
            List<String> evidenceDigests,
            com.argus.rag.assistant.loop.TaskLoopState loopState,
            com.argus.rag.assistant.model.vo.chat.PendingProposalVO pendingProposal
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
