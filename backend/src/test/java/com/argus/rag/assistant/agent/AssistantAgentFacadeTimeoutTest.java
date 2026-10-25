package com.argus.rag.assistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.metrics.LlmDialogueMetrics;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 对话生产级补齐：助手流式对话总超时。
 * <p>模型侧挂起时，{@link AssistantAgentFacade#streamChat} 必须在配置周期后终止，
 * 而不是让 SSE 永久僵住。超时映射为统一可读降级文案而非裸堆栈。</p>
 */
@ExtendWith(MockitoExtension.class)
class AssistantAgentFacadeTimeoutTest {

    @Mock
    private AssistantReactAgentFactory agentFactory;
    @Mock
    private AssistantRunnableConfigFactory runnableConfigFactory;
    @Mock
    private AssistantPromptContextBuilder promptContextBuilder;
    @Mock
    private ReactAgent reactAgent;

    @Test
    void streamChatTimesOutInsteadOfHanging() throws Exception {
        // 模型侧永远不产出任何 token → 触发总超时
        when(agentFactory.createAgent(any(), any(), any(), any(), any())).thenReturn(reactAgent);
        when(runnableConfigFactory.create(any(), any(), any(), any(), any()))
                .thenReturn(RunnableConfig.builder().build());
        when(promptContextBuilder.buildChatInstruction(any(), any(), any(), any(), any())).thenReturn("instruction");
        when(reactAgent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.never());

        AssistantAgentFacade facade = new AssistantAgentFacade(
                agentFactory, runnableConfigFactory, promptContextBuilder, new ToolEventEmitter(),
                new LlmDialogueMetrics(new SimpleMeterRegistry()));

        // llmTimeoutSeconds 由 @Value 注入，单测下为 0 → Math.max(1, 0)=1s，超时后抛可读降级文案
        assertThatThrownBy(() -> facade.streamChat(
                1L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO, "你好",
                text -> {
                }, event -> {
                }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂时不可用");
    }

    /**
     * 同步 {@link AssistantAgentFacade#chat} 同样不能因模型挂起而永久阻塞。
     * <p>模型侧同步调用永不返回时，{@code callWithTimeout} 应在配置周期后降级为可读文案，
     * 而非让 HTTP 请求一直挂着。</p>
     */
    @Test
    void syncChatTimesOutInsteadOfHanging() throws Exception {
        when(agentFactory.createAgent(any(), any(), any(), any(), any())).thenReturn(reactAgent);
        when(runnableConfigFactory.create(any(), any(), any(), any(), any()))
                .thenReturn(RunnableConfig.builder().build());
        when(promptContextBuilder.buildChatInstruction(any(), any(), any(), any(), any()))
                .thenReturn("instruction");
        // 同步执行永不返回 → 竞时触发超时（立即能识别为“暂时不可用”）
        when(reactAgent.call(anyString(), any(RunnableConfig.class))).thenAnswer(invocation -> {
            // 阻塞远超 1s 的竞时窗口；测试环境 Math.max(1, 0)=1s 超时
            Thread.sleep(120_000);
            return null;
        });

        AssistantAgentFacade facade = new AssistantAgentFacade(
                agentFactory, runnableConfigFactory, promptContextBuilder, new ToolEventEmitter(),
                new LlmDialogueMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> facade.chat(
                1L, 10L, AssistantToolMode.CHAT, List.of(), KbRouteStrategy.AUTO, "你好"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂时不可用");
    }
}