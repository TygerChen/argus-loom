package com.argus.rag.assistant.loop;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.argus.rag.planning.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 决策审计 Hook 契约（第五步：模型提议，运行器把关）：
 * 方案内工具审计通过、越界工具标记并计数、纯文本识别为 ANSWER、全程透传不改消息。
 */
@DisplayName("决策审计 Hook（DecisionAuditHook）")
class DecisionAuditHookTest {

    private final DecisionAuditHook hook = new DecisionAuditHook();

    private RunnableConfig configWith(TaskLoopState state) {
        return RunnableConfig.builder()
                .threadId("user:7:session:9")
                .addMetadata("userId", 7L)
                .addMetadata("sessionId", 9L)
                .addMetadata("taskLoopState", state)
                .build();
    }

    /** AssistantMessage 带工具调用的构造器是 protected——测试用 mock。 */
    private AssistantMessage toolCallMessage(String tool, String args) {
        AssistantMessage message = mock(AssistantMessage.class);
        when(message.hasToolCalls()).thenReturn(true);
        when(message.getToolCalls()).thenReturn(List.of(
                new AssistantMessage.ToolCall("call-1", "function", tool, args)));
        when(message.getText()).thenReturn("");
        return message;
    }

    @BeforeEach
    void seedRound() {
        // 轮次来自 beforeModel 的登记；审计测试直接预置一轮
    }

    @Test
    void inPlanToolCallAudited() {
        TaskLoopState state = TaskLoopState.from(new ExecutionPlan("查", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
                List.of(), 3, List.of(), "找到即停", false));
        state.nextRound();

        AgentCommand command = hook.afterModel(
                List.of(new UserMessage("问题"), toolCallMessage("knowledgeBaseSearch", "{\"query\":\"反结账\"}")),
                configWith(state));

        assertEquals(1, state.decisions().size());
        TaskLoopState.DecisionRecord record = state.decisions().get(0);
        assertEquals("CALL_TOOL", record.action());
        assertEquals("knowledgeBaseSearch", record.toolName());
        assertTrue(record.inWhitelist(), "方案清单内工具应通过校验");
        assertTrue(record.argsDigest().contains("反结账"), "参数摘要进审计记录");
        // 透传语义：消息列表原样返回
        assertEquals(2, messagesOf(command).size());
    }

    @Test
    void outOfPlanToolCallIsFlaggedAndCounted() {
        TaskLoopState state = TaskLoopState.from(new ExecutionPlan("查", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
                List.of(), 3, List.of(), "找到即停", false));
        state.nextRound();

        hook.afterModel(List.of(toolCallMessage("executeSql", "{\"sql\":\"DROP TABLE\"}")), configWith(state));

        assertEquals(1, state.decisions().size());
        assertFalseInline(state.decisions().get(0).inWhitelist());
        assertEquals(1, state.outOfPlanDecisionCount());
        assertTrue(state.asContextBlock().contains("越界记录"), "越界必须回压到下一轮上下文");
    }

    @Test
    void plainTextResponseIsAuditedAsAnswer() {
        TaskLoopState state = TaskLoopState.defaults();
        state.nextRound();

        hook.afterModel(List.of(new AssistantMessage("反结账路径如下……")), configWith(state));

        assertEquals(1, state.decisions().size());
        assertEquals("ANSWER", state.decisions().get(0).action());
    }

    @Test
    void noStateMetadataSkipsAudit() {
        TaskLoopState state = TaskLoopState.defaults();
        AgentCommand command = hook.afterModel(
                List.of(toolCallMessage("knowledgeBaseSearch", "{}")),
                RunnableConfig.builder().build());
        assertEquals(0, state.decisions().size(), "无状态不审计（透传）");
        assertEquals(1, messagesOf(command).size());
    }

    private void assertFalseInline(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }

    @SuppressWarnings("unchecked")
    private static List<org.springframework.ai.chat.messages.Message> messagesOf(Object command) {
        try {
            var field = command.getClass().getDeclaredField("messages");
            field.setAccessible(true);
            return (List<org.springframework.ai.chat.messages.Message>) field.get(command);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
