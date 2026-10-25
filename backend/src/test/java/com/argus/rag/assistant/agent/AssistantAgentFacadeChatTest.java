package com.argus.rag.assistant.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.metrics.LlmDialogueMetrics;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantAgentFacade} 主编排面契约（mock 5 协作方 + ReactAgent，真实 Reactor Flux，
 * 零 DB/零 LLM/零真实网络）——锁定超时/角色门测试未覆盖的 chat 与 streamChat 正常路径：
 * - chat：成功 → 构造 reply+citations 并收尾 finish(sync,true)；空/空白回复 → 「助手返回内容为空」；
 *   底层 BusinessException 原样透传；普通图运行时错误 → 包装为可重试运行时错误（cause 透传）；
 * - streamChat：AGENT_MODEL_STREAMING 增量去重拼接（累计前缀被裁成真正 delta）、
 *   AGENT_MODEL_FINISHED 无增量时兜底补全、FINISHED 带 toolCall 不补发、非 Streaming 节点被忽略、
 *   空回复拒绝；非超时异常 → 「助手调用失败」，TimeoutException → MODEL_UNAVAILABLE「模型服务暂时不可用…」。
 */
@DisplayName("助手 Agent 门面主编排面（AssistantAgentFacade）")
class AssistantAgentFacadeChatTest {

    private AssistantReactAgentFactory agentFactory;
    private AssistantRunnableConfigFactory runnableConfigFactory;
    private AssistantPromptContextBuilder promptContextBuilder;
    private LlmDialogueMetrics metrics;
    private ReactAgent reactAgent;
    private AssistantAgentFacade facade;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AssistantReactAgentFactory.class);
        runnableConfigFactory = mock(AssistantRunnableConfigFactory.class);
        promptContextBuilder = mock(AssistantPromptContextBuilder.class);
        metrics = mock(LlmDialogueMetrics.class);
        reactAgent = mock(ReactAgent.class);

        when(agentFactory.createAgent(any(), any(), any(), any(), any())).thenReturn(reactAgent);
        when(runnableConfigFactory.create(any(), any(), any(), any(), any()))
                .thenReturn(RunnableConfig.builder().build());
        when(promptContextBuilder.buildChatInstruction(any(), any(), any(), any(), any()))
                .thenReturn("instruction");
        when(metrics.start()).thenReturn(mock(Timer.Sample.class));

        facade = new AssistantAgentFacade(agentFactory, runnableConfigFactory, promptContextBuilder,
                new ToolEventEmitter(), metrics);
    }

    // ---------- helpers ----------

    /**
     * ReactAgent 的 call/stream 声明了 checked {@code GraphRunnerException}，故 stub 需包在
     * {@code assertDoesNotThrow} 里（生产的 try/catch 或 Callable 同理）。断言类本身不必声明 throws。
     */
    private static StreamingOutput<?> streamingNode(String text, OutputType type) {
        return new StreamingOutput<>(new AssistantMessage(text), "agent", "context", null, type);
    }

    private static List<String> collectDeltas() {
        return new ArrayList<>();
    }

    private void mockCall(AssistantMessage reply) {
        assertDoesNotThrow(() -> when(reactAgent.call(anyString(), any(RunnableConfig.class))).thenReturn(reply));
    }

    private void mockCallThrows(Throwable t) {
        assertDoesNotThrow(() -> when(reactAgent.call(anyString(), any(RunnableConfig.class))).thenThrow(t));
    }

    private void mockStreamReturns(Flux<NodeOutput> flux) {
        assertDoesNotThrow(() -> when(reactAgent.stream(anyString(), any(RunnableConfig.class))).thenReturn(flux));
    }

    // ---------- chat（同步） ----------

    @Test
    void chatSuccessReturnsReplyAndMarksMetrics() {
        mockCall(new AssistantMessage("回答文本"));
        AssistantAgentResult res = facade.chat(7L, 10L, AssistantToolMode.CHAT,
                List.of(), KbRouteStrategy.AUTO, "你好");
        assertEquals("回答文本", res.reply());
        assertEquals(List.of(), res.citations());
        verify(metrics).finish(any(Timer.Sample.class), eq("assistant"), eq("sync"), eq(true));
    }

    @Test
    void chatNullReplyThrowsAssistantEmpty() {
        mockCall(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.chat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO, "你好"));
        assertEquals("助手返回内容为空", e.getMessage());
    }

    @Test
    void chatWhitespaceReplyThrowsAssistantEmpty() {
        mockCall(new AssistantMessage("   "));
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.chat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO, "你好"));
        assertEquals("助手返回内容为空", e.getMessage());
    }

    @Test
    void chatPropagatesUnderlyingBusinessException() {
        BusinessException original = new BusinessException("原错误");
        mockCallThrows(original);
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.chat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO, "你好"));
        assertEquals("原错误", e.getMessage());
    }

    @Test
    void chatPlainGraphErrorWrappedAsRetryable() {
        mockCallThrows(new RuntimeException("boom"));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> facade.chat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO, "你好"));
        assertNotNull(e.getCause());
        assertEquals("boom", e.getCause().getMessage());
    }

    // ---------- streamChat（流式） ----------

    @Test
    void streamChatAssemblesDeltasAndTrimsCumulativePrefix() {
        // 增量节点：先 "你" 再累计全文 "你好" → 裁掉已有前缀得到真正的增量 "好"
        mockStreamReturns(Flux.<NodeOutput>just(
                streamingNode("你", OutputType.AGENT_MODEL_STREAMING),
                streamingNode("你好", OutputType.AGENT_MODEL_STREAMING)));

        List<String> deltas = collectDeltas();
        AssistantAgentResult res = facade.streamChat(7L, 10L, AssistantToolMode.CHAT, List.of(),
                KbRouteStrategy.AUTO, "你好", text -> deltas.add(text), ev -> {
                });
        assertEquals(List.of("你", "好"), deltas);
        assertEquals("你好", res.reply());
        assertEquals(List.of(), res.citations());
        verify(metrics).finish(any(Timer.Sample.class), eq("assistant"), eq("stream"), eq(true));
    }

    @Test
    void streamChatFinishedNodeFallsBackWhenNoDeltas() {
        mockStreamReturns(Flux.<NodeOutput>just(
                streamingNode("全文答复", OutputType.AGENT_MODEL_FINISHED)));
        List<String> deltas = collectDeltas();
        AssistantAgentResult res = facade.streamChat(7L, 10L, AssistantToolMode.CHAT, List.of(),
                KbRouteStrategy.AUTO, "你好", deltas::add, ev -> {
        });
        assertEquals(List.of("全文答复"), deltas);
        assertEquals("全文答复", res.reply());
    }

    @Test
    void streamChatFinishedNodeWithToolCallIgnoresDelta() {
        // 兜底补发只在「无 toolCall 且无增量」时发生；带 toolCall 的 finished 节点不补发 → 回复为空 → 拒绝。
        AssistantMessage withToolCalls = mock(AssistantMessage.class);
        when(withToolCalls.hasToolCalls()).thenReturn(true);
        when(withToolCalls.getText()).thenReturn("不应补发");
        mockStreamReturns(Flux.<NodeOutput>just(
                new StreamingOutput<>(withToolCalls, "agent", "ctx", null, OutputType.AGENT_MODEL_FINISHED)));
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.streamChat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO,
                        "你好", s -> { }, ev -> { }));
        assertEquals("助手返回内容为空", e.getMessage());
    }

    @Test
    void streamChatIgnoresNonStreamingNode() {
        mockStreamReturns(Flux.<NodeOutput>just(mock(NodeOutput.class)));
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.streamChat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO,
                        "你好", s -> { }, ev -> { }));
        assertEquals("助手返回内容为空", e.getMessage());
    }

    @Test
    void streamChatNonTimeoutExceptionWrappedAsAssistantFailure() {
        mockStreamReturns(Flux.<NodeOutput>error(new RuntimeException("graph boom")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.streamChat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO,
                        "你好", s -> { }, ev -> { }));
        assertEquals("助手调用失败", e.getMessage());
        assertEquals("graph boom", e.getCause().getMessage());
    }

    @Test
    void streamChatTimeoutExceptionMappedToModelUnavailable() {
        mockStreamReturns(Flux.<NodeOutput>error(new TimeoutException("model hang")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> facade.streamChat(7L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO,
                        "你好", s -> { }, ev -> { }));
        assertEquals("模型服务暂时不可用，请稍后重试。", e.getMessage());
    }
}