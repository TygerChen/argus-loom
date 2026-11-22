package com.argus.rag.assistant.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.argus.rag.assistant.loop.TaskLoopState;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.service.AssistantConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第四步组装器集成契约：Hook 在决策轮把任务状态块插到当前问题之前；
 * 工具轮透传但状态仍登记；预算用尽出现强制停止指令。
 */
@DisplayName("短期记忆 Hook 的循环状态组装")
class LoopStateAssemblyHookTest {

    private AssistantShortTermMemoryHook hook;
    private AssistantConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = mock(AssistantConversationService.class);
        when(conversationService.loadConversationContext(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new AssistantConversationService.AssistantConversationContext(
                        "", "", "", List.of()));
        hook = new AssistantShortTermMemoryHook(conversationService,
                mock(com.argus.rag.assistant.memory.longterm.MemoryRetriever.class),
                mock(com.argus.rag.assistant.memory.longterm.MemoryContextBuilder.class));
    }

    /** AgentCommand 的 messages 访问器是包私有——测试用反射读取。 */
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

    private RunnableConfig configWith(TaskLoopState state) {
        return RunnableConfig.builder()
                .threadId("user:7:session:9")
                .addMetadata("userId", 7L)
                .addMetadata("sessionId", 9L)
                .addMetadata("toolMode", AssistantToolMode.KB_SEARCH.name())
                .addMetadata("taskLoopState", state)
                .build();
    }

    @Test
    void stateBlockInsertedBeforeCurrentQuestion() {
        TaskLoopState state = TaskLoopState.defaults();
        var command = hook.beforeModel(List.of(new UserMessage("金蝶怎么反结账？")), configWith(state));

        List<org.springframework.ai.chat.messages.Message> out = messagesOf(command);
        int stateIndex = -1;
        int questionIndex = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof SystemMessage sys && sys.getText().contains("<task_loop_state>")) {
                stateIndex = i;
            }
            if (out.get(i) instanceof UserMessage) {
                questionIndex = i;
            }
        }
        assertTrue(stateIndex >= 0, "上下文必须包含任务状态块");
        assertTrue(questionIndex > stateIndex, "状态块必须位于当前问题之前");
        assertEquals(1, state.currentRound(), "决策轮登记一次");
    }

    @Test
    void toolRoundPassthroughStillRegistersState() {
        TaskLoopState state = TaskLoopState.defaults();
        state.nextRound();
        // 真实工具轮结构：用户消息 → 工具响应（触发透传分支）
        var toolResponse = org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call-1", "knowledgeBaseSearch", "命中两条证据片段")))
                .build();
        var command = hook.beforeModel(
                List.of(new UserMessage("问题"), toolResponse), configWith(state));
        assertEquals(2, state.currentRound(), "工具轮的模型调用同样登记轮次");
        assertTrue(state.asContextBlock().contains("命中两条证据片段"),
                "工具结果必须被增量摘要进状态");
        assertTrue(messagesOf(command).stream().noneMatch(m -> m instanceof SystemMessage sys
                        && sys.getText().contains("<task_loop_state>")),
                "工具轮透传不注入状态块（保持标准 tool message 结构）");
    }
}
