package com.argus.rag.assistant.model.vo.chat;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.qa.model.vo.AskQuestionResponse.Citation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantChatStreamEvent} 助手流式事件 record 契约（纯 JVM/零 mock）。
 * 五个事件工厂（start/delta/done/error/toolCall）各自设定 event 名，并只填充该事件语义字段
 * （其余置 null/空引用）；done 的 citations 为 null 时归一空列表；shortcut 与 full 重载等价。
 */
@DisplayName("助手流式事件（AssistantChatStreamEvent）")
class AssistantChatStreamEventTest {

    private static final Citation CITATION =
            new Citation(1L, 2L, 0, "src.docx", 0.9, "摘要", 5L);

    @Test
    void startEventSetsEventNameAndClearsPayload() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.start(7L, AssistantToolMode.CHAT, 3L);
        assertEquals("start", e.event());
        assertEquals(7L, e.sessionId());
        assertEquals(AssistantToolMode.CHAT, e.toolMode());
        assertEquals(3L, e.groupId());
        assertNull(e.delta());
        assertNull(e.messageId());
        assertNull(e.reply());
        assertNull(e.error());
        assertNull(e.toolName());
        assertEquals(List.of(), e.citations());
    }

    @Test
    void startFullCarriesGroupIdsAndRoute() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.start(
                1L, AssistantToolMode.KB_SEARCH, 3L, List.of(3L, 4L), KbRouteStrategy.AUTO);
        assertEquals("start", e.event());
        assertEquals(List.of(3L, 4L), e.groupIds());
        assertEquals(KbRouteStrategy.AUTO, e.routeStrategy());
    }

    @Test
    void deltaEventKeepsTextAndClearsControls() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.delta(2L, AssistantToolMode.CHAT, 3L, "增量");
        assertEquals("delta", e.event());
        assertEquals("增量", e.delta());
        assertNull(e.messageId());
        assertNull(e.reply());
        assertNull(e.error());
        assertEquals(List.of(), e.citations());
    }

    @Test
    void doneEventCarriesResultAndNormalizesNullCitations() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.done(
                2L, AssistantToolMode.KB_SEARCH, 3L, 9L, "最终回答", List.of(CITATION));
        assertEquals("done", e.event());
        assertEquals(9L, e.messageId());
        assertEquals("最终回答", e.reply());
        assertEquals(List.of(CITATION), e.citations());
        // null citations → 空列表
        AssistantChatStreamEvent noCites = AssistantChatStreamEvent.done(
                2L, AssistantToolMode.CHAT, 3L, 9L, "无引用", null);
        assertEquals(List.of(), noCites.citations());
    }

    @Test
    void errorEventCarriesMessageOnly() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.error(2L, AssistantToolMode.CHAT, 3L, "模型不可用");
        assertEquals("error", e.event());
        assertEquals("模型不可用", e.error());
        assertNull(e.delta());
        assertNull(e.reply());
        assertNull(e.messageId());
    }

    @Test
    void toolCallEventCarriesToolNameAndStage() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.toolCall(
                2L, AssistantToolMode.CHAT, 3L, "query_structured_data", "start");
        assertEquals("tool_call", e.event());
        assertEquals("query_structured_data", e.toolName());
        assertEquals("start", e.toolStage());
        assertNull(e.delta());
        assertNull(e.reply());
        assertNull(e.error());
        assertEquals(List.of(), e.citations());
    }

    @Test
    void toolCallFullVariantCarriesGroupIdsAndRoute() {
        AssistantChatStreamEvent e = AssistantChatStreamEvent.toolCall(
                2L, AssistantToolMode.CHAT, 3L, List.of(3L, 4L), KbRouteStrategy.AUTO, "tool_x", "result");
        assertEquals("tool_call", e.event());
        assertEquals(List.of(3L, 4L), e.groupIds());
        assertEquals(KbRouteStrategy.AUTO, e.routeStrategy());
        assertEquals("tool_x", e.toolName());
        assertEquals("result", e.toolStage());
    }

    @Test
    void shortcutEqualsFullWithNullRange() {
        AssistantChatStreamEvent shortcut = AssistantChatStreamEvent.done(1L, AssistantToolMode.CHAT, 3L, 5L, "r", null);
        AssistantChatStreamEvent full = AssistantChatStreamEvent.done(1L, AssistantToolMode.CHAT, 3L, null, null, 5L, "r", null);
        assertEquals(shortcut, full);
    }
}