package com.argus.rag.assistant.service;

import com.argus.rag.assistant.agent.ToolEventEmitter;
import com.argus.rag.assistant.agent.ToolEventEmitter.ToolEvent;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AssistantStreamEventEmitter} 默认方法契约（纯 JVM/零 mock）。
 * {@code emitToolEvent} 将 {@link ToolEventEmitter.ToolEvent} 委托为
 * {@code "tool_call"} 流式事件（toolName/toolStage 透传；sessionId/toolMode/groupId 为空）。
 */
@DisplayName("流式聊天事件发射器默认方法（AssistantStreamEventEmitter）")
class AssistantStreamEventEmitterDefaultTest {

    /** 记录性桩：把每次 emit 追加进列表，供断言。 */
    private static final class Recording implements AssistantStreamEventEmitter {
        final List<AssistantChatStreamEvent> events = new ArrayList<>();

        @Override
        public void emit(AssistantChatStreamEvent event) {
            events.add(event);
        }
    }

    @Test
    void emitToolEventForStartEmitsToolCallStageStart() {
        Recording rec = new Recording();
        rec.emitToolEvent(ToolEvent.start("search"));
        assertEquals(1, rec.events.size());
        AssistantChatStreamEvent e = rec.events.get(0);
        assertEquals("tool_call", e.event());
        assertEquals("search", e.toolName());
        assertEquals("START", e.toolStage());
        assertNull(e.sessionId());
        assertNull(e.toolMode());
        assertNull(e.groupId());
    }

    @Test
    void emitToolEventForResultEmitsToolCallResult() {
        Recording rec = new Recording();
        rec.emitToolEvent(ToolEvent.result("search", "摘要"));
        AssistantChatStreamEvent e = rec.events.get(0);
        assertEquals("tool_call", e.event());
        assertEquals("search", e.toolName());
        assertEquals("RESULT", e.toolStage());
    }
}