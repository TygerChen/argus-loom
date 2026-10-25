package com.argus.rag.assistant.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.metrics.LlmDialogueMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 助手 Agent 门面服务。
 * <p>封装 ReactAgent 的调用逻辑，提供同步聊天和流式聊天的统一入口。</p>
 * <p>负责构建运行时 instruction 和 RunnableConfig，处理 Agent 调用结果，
 * 并在调用失败时转换为业务异常抛出。</p>
 */
@Component
public class AssistantAgentFacade {

    private static final Logger log = LoggerFactory.getLogger(AssistantAgentFacade.class);

    /** 助手对话总超时（秒），与 QA 共用全局 LLM 超时配置。 */
    @Value("${argus.rag.llm.timeout-seconds:45}")
    private int llmTimeoutSeconds;

    /**
     * 执行同步 {@code chat()} 的专用线程池（守护线程，不阻止 JVM 退出）。
     * <p>同步 graph 调用是阻塞 IO，无法原生设置超时；用固定小池运行调用并通过
     * {@code Future.get(timeout,...)} 竞时，超时即降级为可读文案，避免模型挂起时
     * 请求永久卡死。调用不可中断时线程会继续在后台运行，但上层不再等待。</p>
     */
    private final ExecutorService chatTimeoutExecutor =
            Executors.newFixedThreadPool(4, task -> {
                Thread thread = new Thread(task, "assistant-sync-chat-timeout");
                thread.setDaemon(true);
                return thread;
            });

    private final AssistantReactAgentFactory assistantReactAgentFactory;
    private final AssistantRunnableConfigFactory assistantRunnableConfigFactory;
    private final AssistantPromptContextBuilder assistantPromptContextBuilder;
    private final ToolEventEmitter toolEventEmitter;
    private final LlmDialogueMetrics dialogueMetrics;

    public AssistantAgentFacade(
            AssistantReactAgentFactory assistantReactAgentFactory,
            AssistantRunnableConfigFactory assistantRunnableConfigFactory,
            AssistantPromptContextBuilder assistantPromptContextBuilder,
            ToolEventEmitter toolEventEmitter,
            LlmDialogueMetrics dialogueMetrics
    ) {
        this.assistantReactAgentFactory = assistantReactAgentFactory;
        this.assistantRunnableConfigFactory = assistantRunnableConfigFactory;
        this.assistantPromptContextBuilder = assistantPromptContextBuilder;
        this.toolEventEmitter = toolEventEmitter;
        this.dialogueMetrics = dialogueMetrics;
    }

    /**
     * 同步聊天。
     * <p>基于用户输入、会话上下文和工具模式构建 instruction，通过 ReactAgent
     * 同步执行对话，返回助手的文本回复和引用列表。</p>
     *
     * @param userId      当前用户 ID
     * @param sessionId   当前会话 ID
     * @param toolMode    工具模式（CHAT 仅对话 / KB_SEARCH 知识库检索）
     * @param groupIds     知识库组 ID 列表，CHAT 模式下为空
     * @param routeStrategy 知识库路由策略
     * @param userMessage 用户输入的文本
     * @return 包含助手回复文本和引用列表的结果对象
     * @throws com.argus.rag.common.exception.BusinessException 当 Agent 调用失败或返回内容为空时
     */
    @Retryable(
                maxAttempts = 3,
                backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000),
                retryFor = {RetryableGraphException.class}
        )
    public AssistantAgentResult chat(
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            String userMessage
    ) {
        Timer.Sample sample = dialogueMetrics.start();
        boolean success = false;
        try {
        // 这里把“仅对话模式”的运行时输入收口成两部分：
        // 1) instruction：系统提示词
        // 2) runnableConfig：session/user/toolMode 等 metadata，供 hooks 在 BEFORE_MODEL 阶段读取
        String instruction = assistantPromptContextBuilder.buildChatInstruction(
                userId,
                sessionId,
                toolMode,
                groupIds,
                routeStrategy
        );
        RunnableConfig runnableConfig = assistantRunnableConfigFactory.create(
                userId,
                sessionId,
                toolMode,
                groupIds,
                routeStrategy
        );
        // 当前虽然是“仅对话模式”，底层仍然使用 ReactAgent，只是 system prompt 已改成纯对话风格。
        AssistantKnowledgeBaseToolResultHolder resultHolder = new AssistantKnowledgeBaseToolResultHolder();
        ReactAgent agent = assistantReactAgentFactory.createAgent(instruction, toolMode, groupIds, resultHolder, userId);
        // 把 checked 的图执行错误包装为可重试的运行时异常（可直接抛出、不向调用方传编译负担），
        // 让 @Retryable 能捕获并按退避策略重试；原先在此转成 BusinessException 会让重试永不走不到。
        // 同步调用经由 callWithTimeout 竞时，模型挂起/超全网卡时降级为统一可读文案，而非永远阻塞。
        AssistantMessage assistantMessage = callWithTimeout(() -> agent.call(userMessage, runnableConfig));
        if (log.isDebugEnabled()) {
            log.debug(
                    "AssistantAgentFacade.chat result. userId={}, sessionId={}, toolMode={}, groupIds={}, text={}, hasToolCalls={}, toolCallCount={}",
                    userId,
                    sessionId,
                    toolMode,
                    groupIds,
                    assistantMessage == null ? null : abbreviate(assistantMessage.getText()),
                    assistantMessage != null && assistantMessage.hasToolCalls(),
                    assistantMessage == null || assistantMessage.getToolCalls() == null ? 0 : assistantMessage.getToolCalls().size()
            );
        }
        if (assistantMessage == null || assistantMessage.getText() == null || assistantMessage.getText().isBlank()) {
            throw new BusinessException("助手返回内容为空");
        }
        success = true;
        return new AssistantAgentResult(assistantMessage.getText(), resultHolder.currentCitations());
        } finally {
            dialogueMetrics.finish(sample, "assistant", "sync", success);
        }
    }

    /**
     * 同步对话重试耗尽后的兜底。
     * <p>与 {@link #chat} 同形参（异常类型置于首位），由 spring-retry 在 {@code @Retryable}
     * 声明中毒耗尽后自动回调；这里把「多次尝试仍失败的图执行错误」转为面向用户的业务异常，
     * 避免 GraphRunnerException 直接冒到全局兜底(500)且丢失业务文案。</p>
     */
    @Recover
    public AssistantAgentResult recoverFailedChat(
            RetryableGraphException exception,
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            String userMessage
    ) {
        log.warn("同步对话在重试后仍失败，降级为业务异常。userId={}, sessionId={}", userId, sessionId, exception);
        throw new BusinessException("助手调用失败", exception);
    }

    /**
     * 流式对话入口：把回复拆成一个个增量（delta）实时推给前端，并返回最终完整回复。
     * <p>与同步的 {@link #chat} 的同形参大体一致，多出两个回调：
     * {@code deltaConsumer} 每收到一段文本就推给前端（SSE 逐字输出），{@code toolEventConsumer}
     * 用于把工具调用过程（检索/搜索等）作为事件透传给前端展示。</p>
     * <p>关键保障：
     * <ul>
     *   <li><b>总超时</b>：流式挂起会卡死前端，故加了 {@code llmTimeoutSeconds} 硬护栏，超时降级为
     *       可读文案，与 QA 的 {@code MODEL_UNAVAILABLE} 口径一致；</li>
     *   <li><b>不对外把堆栈暴露给前端</b>：GraphRunnerException 等统一映射为业务失败文案；</li>
     *   <li><b>根部不做重试</b>：失败后整体重试会重复推送 token、重复执行已产生的工具副作用
     *   （检索/知识库落库），故交由上层（SSE）做降级而非这里傻重试。</li>
     * </ul>
     * 文本累积在 {@code finalReply}，若模型只在 finished 节点一次性返回全文则兜底补发，保证不丢字。</p>
     *
     * @param userId            当前用户 ID
     * @param sessionId         当前会话 ID
     * @param toolMode          工具模式（CHAT 仅对话 / KB_SEARCH 知识库检索）
     * @param groupIds          知识库组 ID 列表，CHAT 模式下为空
     * @param routeStrategy     知识库路由策略
     * @param userMessage       用户输入的文本
     * @param deltaConsumer     每段增量文本的回调（推给前端渲染）
     * @param toolEventConsumer 工具阶段事件的回调（推给前端展示工具调用过程）
     * @return 包含最终完整回复和引用列表的结果对象
     */
    public AssistantAgentResult streamChat(
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            String userMessage,
            Consumer<String> deltaConsumer,
            Consumer<ToolEventEmitter.ToolEvent> toolEventConsumer
    ) {
        Timer.Sample streamSample = dialogueMetrics.start();
        boolean streamSuccess = false;
        try {
        String instruction = assistantPromptContextBuilder.buildChatInstruction(
                userId,
                sessionId,
                toolMode,
                groupIds,
                routeStrategy
        );
        RunnableConfig runnableConfig = assistantRunnableConfigFactory.create(
                userId,
                sessionId,
                toolMode,
                groupIds,
                routeStrategy
        );
        AssistantKnowledgeBaseToolResultHolder resultHolder = new AssistantKnowledgeBaseToolResultHolder();
        toolEventEmitter.register(toolEventConsumer);
        ReactAgent agent = assistantReactAgentFactory.createAgent(instruction, toolMode, groupIds, resultHolder, userId);
        StringBuilder finalReply = new StringBuilder();

        // 生产级硬护栏：流式对话加总超时，防止模型侧挂起导致 SSE 永久僵住。
        long timeoutSeconds = Math.max(1, llmTimeoutSeconds);
        try {
            // stream() 返回的是图执行过程中的节点输出，这里只抽取模型实际产出的文本 delta。
            Flux<NodeOutput> stream = agent.stream(userMessage, runnableConfig)
                    .timeout(Duration.ofSeconds(timeoutSeconds));
            stream.doOnNext(output -> handleStreamingOutput(output, deltaConsumer, finalReply))
                    .blockLast();
        } catch (Exception exception) {
            if (isTimeoutException(exception)) {
                // 超时 ≠ 明确失败，降级为可读文案，与 QA 的 MODEL_UNAVAILABLE 口径一致。
                log.warn("Assistant stream timed out after {}s. userId={}, sessionId={}",
                        timeoutSeconds, userId, sessionId, exception);
                throw new BusinessException(ErrorCodes.MODEL_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE_MESSAGE, exception);
            }
            // GraphRunnerException 及其它运行异常统一映射为业务失败，避免把堆栈细节暴露给前端。
            log.warn("Assistant stream failed. userId={}, sessionId={}",
                    userId, sessionId, exception);
            throw new BusinessException("助手调用失败", exception);
        } finally {
            toolEventEmitter.clear();
        }
        String reply = finalReply.toString().trim();
        if (log.isDebugEnabled()) {
            log.debug(
                    "AssistantAgentFacade.streamChat result. userId={}, sessionId={}, toolMode={}, groupIds={}, reply={}, citationCount={}",
                    userId,
                    sessionId,
                    toolMode,
                    groupIds,
                    abbreviate(reply),
                    resultHolder.currentCitations().size()
            );
        }
        if (reply.isBlank()) {
            throw new BusinessException("助手返回内容为空");
        }
        streamSuccess = true;
        return new AssistantAgentResult(reply, resultHolder.currentCitations());
        } finally {
            dialogueMetrics.finish(streamSample, "assistant", "stream", streamSuccess);
        }
    }

    private void handleStreamingOutput(
            NodeOutput output,
            Consumer<String> deltaConsumer,
            StringBuilder finalReply
    ) {
        if (!(output instanceof StreamingOutput streamingOutput)) {
            return;
        }
        OutputType type = streamingOutput.getOutputType();
        Message message = streamingOutput.message();
        if (!(message instanceof AssistantMessage assistantMessage)) {
            return;
        }
        if (type == OutputType.AGENT_MODEL_STREAMING) {
            // 正常流式路径下，模型边生成边向前端透传文本。
            String delta = normalizeStreamingDelta(assistantMessage.getText(), finalReply.toString());
            if (delta.isBlank()) {
                return;
            }
            finalReply.append(delta);
            deltaConsumer.accept(delta);
            return;
        }
        if (type == OutputType.AGENT_MODEL_FINISHED
                && !assistantMessage.hasToolCalls()
                && finalReply.isEmpty()) {
            // 某些情况下模型不会走 streaming delta，而是一次性在 finished 节点返回完整文本，这里兜底补发。
            String fullText = assistantMessage.getText();
            if (fullText == null || fullText.isBlank()) {
                return;
            }
            finalReply.append(fullText);
            deltaConsumer.accept(fullText);
        }
    }

    static String normalizeStreamingDelta(String candidateText, String accumulatedReply) {
        if (candidateText == null || candidateText.isBlank()) {
            return "";
        }
        if (accumulatedReply.isEmpty()) {
            return candidateText;
        }
        // Spring AI Alibaba 的流式节点在某些模型下会反复返回“截至当前的全文”，
        // 这里需要把累计前缀裁掉，转换成真正的增量 delta，再交给前端做 append。
        if (candidateText.startsWith(accumulatedReply)) {
            return candidateText.substring(accumulatedReply.length());
        }
        return candidateText;
    }

    /**
     * 以竞时方式运行同步 Agent 调用。
     * <p>同步 {@code agent.call} 是阻塞 IO 且无法原生设置超时，故放入守护线程池并通过
     * {@code Future.get(timeout,...)} 竞时；超时/中断记为「模型暂不可用」降级为统一可读文案
     * （与流式路径一致），不抛可重试异常；其余异常还原为 {@link RetryableGraphException} 或业务异常，
     * 保持原有语义。</p>
     */
    private AssistantMessage callWithTimeout(Callable<AssistantMessage> agentCall) {
        long timeoutSeconds = Math.max(1, llmTimeoutSeconds);
        try {
            return chatTimeoutExecutor.submit(agentCall).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            log.warn("Assistant 同步对话超过 {}s 未完成，降级为超时文案。", timeoutSeconds);
            throw new BusinessException(ErrorCodes.MODEL_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE_MESSAGE, timeoutException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCodes.MODEL_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE_MESSAGE, interruptedException);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            if (cause instanceof RetryableGraphException retryableGraphException) {
                throw retryableGraphException;
            }
            throw new RetryableGraphException(cause == null ? executionException : cause);
        }
    }

    @PreDestroy
    void shutdownChatTimeoutExecutor() {
        chatTimeoutExecutor.shutdownNow();
    }

    /**
     * 判断根因是否为超时。Reactor 会以 {@code ReactiveException} 包裹超时信号，
     * 直接 instanceof 不可靠，因此沿 cause 链向下识别。
     */
    static boolean isTimeoutException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\n', ' ').trim();
        if (normalized.length() > 200) {
            return normalized.substring(0, 200) + "...";
        }
        return normalized;
    }

    /**
     * 可重试的图执行错误包装。
     * <p>将 checked 的 {@link GraphRunnerException} 转为一个仅用于触发 {@code @Retryable}
     * 的运行时异常（可直接抛出、不向调用方传染编译负担），而不会误触发对不可重试业务异常的重试。</p>
     */
    private static final class RetryableGraphException extends RuntimeException {
        RetryableGraphException(Throwable cause) {
            super(cause);
        }
    }
}
