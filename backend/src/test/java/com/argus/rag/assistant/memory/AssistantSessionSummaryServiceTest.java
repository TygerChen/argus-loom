package com.argus.rag.assistant.memory;

import com.argus.rag.assistant.mapper.AssistantSessionContextMapper;
import com.argus.rag.assistant.model.entity.AssistantMessageEntity;
import com.argus.rag.assistant.model.entity.AssistantSessionContextEntity;
import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantSessionSummaryService} 会话摘要契约（注入固定 Clock + mock 上下文 mapper，
 * 零 DB/零 LLM，纯 JVM）：
 * - loadReusableSummary：无记录/空摘要/null updatedAt→null、updatedAt 早于最后消息→过期、
 *   距今超 staleDays 天→过期、有效→返回摘要文本；
 * - shouldSummarize：消息数>messageThreshold 或 token>tokenThreshold→true、饥饿/null 时间→false、
 *   最后消息距今>staleDays 天→true；
 * - estimateTokens：null/空→0、全空白→1、总字符÷4（至少 1）、跨条求和；
 * - summarizeAndPersist：null/空→null、无可压缩旧消息→null 不写、保留最近 recentMessageLimit 条
 *   （至少 1）→ 压缩较早消息为「历史摘要:」文本 + upsert 写 summaryText/sourceMessageId/updatedAt、
 *   upsert 非 1→「保存会话摘要失败」。
 */
@DisplayName("助手会话摘要（AssistantSessionSummaryService）")
class AssistantSessionSummaryServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-09-18T18:00:00Z"), ZoneOffset.UTC);

    private AssistantSessionContextMapper contextMapper;
    private AssistantSessionSummaryService svc;

    @BeforeEach
    void setUp() {
        contextMapper = mock(AssistantSessionContextMapper.class);
        svc = new AssistantSessionSummaryService(
                contextMapper, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), 20, 8000, 7);
    }

    // ---------- helpers ----------

    private static AssistantMessageEntity msg(Long id, String role, String content) {
        AssistantMessageEntity e = new AssistantMessageEntity();
        e.setId(id);
        e.setRole(role);
        e.setContent(content);
        return e;
    }

    private static AssistantSessionContextEntity context(String summary, LocalDateTime updatedAt) {
        AssistantSessionContextEntity c = new AssistantSessionContextEntity();
        c.setSessionId(9L);
        c.setSummaryText(summary);
        c.setUpdatedAt(updatedAt);
        return c;
    }

    // ---------- loadReusableSummary ----------

    @Test
    void loadReusableSummaryMissingOrBlankReturnsNull() {
        when(contextMapper.selectBySessionId(9L)).thenReturn(null);
        assertNull(svc.loadReusableSummary(9L, NOW));

        when(contextMapper.selectBySessionId(9L)).thenReturn(context("   ", NOW));
        assertNull(svc.loadReusableSummary(9L, NOW));
    }

    @Test
    void loadReusableSummaryStaleByAgeReturnsNull() {
        // updatedAt 距今 8 天 > staleDays 7 → 过期
        when(contextMapper.selectBySessionId(9L)).thenReturn(context("SUM", NOW.minusDays(8)));
        assertNull(svc.loadReusableSummary(9L, NOW.minusDays(9)));
    }

    @Test
    void loadReusableSummaryNullUpdatedAtIsStale() {
        when(contextMapper.selectBySessionId(9L)).thenReturn(context("SUM", null));
        assertNull(svc.loadReusableSummary(9L, NOW));
    }

    @Test
    void loadReusableSummaryUpdatedBeforeLastMessageIsStale() {
        // updatedAt 早于最后消息时间 → 会话又新增了内容，摘要过期
        when(contextMapper.selectBySessionId(9L)).thenReturn(context("SUM", NOW.minusMinutes(5)));
        assertNull(svc.loadReusableSummary(9L, NOW));
    }

    @Test
    void loadReusableSummaryValidReturnsText() {
        when(contextMapper.selectBySessionId(9L)).thenReturn(context("SUM", NOW));
        assertEquals("SUM", svc.loadReusableSummary(9L, NOW.minusMinutes(1)));
    }

    // ---------- shouldSummarize ----------

    @Test
    void shouldSummarizeOverMessageThresholdTrue() {
        assertTrue(svc.shouldSummarize(21, 10, NOW));
    }

    @Test
    void shouldSummarizeOverTokenThresholdTrue() {
        assertTrue(svc.shouldSummarize(3, 8001, NOW));
    }

    @Test
    void shouldSummarizeInactiveSessionTrue() {
        // 低量但最后消息距今 8 天 > 7 → true
        assertTrue(svc.shouldSummarize(3, 100, NOW.minusDays(8)));
    }

    @Test
    void shouldSummarizeNullLastTimeFalse() {
        assertFalse(svc.shouldSummarize(3, 100, null));
    }

    @Test
    void shouldSummarizeActiveSmallSessionFalse() {
        assertFalse(svc.shouldSummarize(3, 100, NOW));
    }

    // ---------- estimateTokens ----------

    @Test
    void estimateTokensNullEmptyIsZero() {
        assertEquals(0, svc.estimateTokens(null));
        assertEquals(0, svc.estimateTokens(List.of()));
    }

    @Test
    void estimateTokensBlankOrNullContentIsOne() {
        assertEquals(1, svc.estimateTokens(List.of(msg(1L, "USER", "  "), msg(2L, "USER", null))));
    }

    @Test
    void estimateTokensSumsCharsDividedByFour() {
        // 8 + 8 = 16 → 16/4 = 4
        assertEquals(4, svc.estimateTokens(List.of(
                msg(1L, "USER", "12345678"),
                msg(2L, "USER", "abcdefgh"))));
    }

    // ---------- summarizeAndPersist ----------

    @Test
    void summarizeAndPersistNullOrEmptyReturnsNull() {
        assertNull(svc.summarizeAndPersist(9L, null, 2));
        assertNull(svc.summarizeAndPersist(9L, List.of(), 2));
        verify(contextMapper, never()).upsert(any());
    }

    @Test
    void summarizeAndPersistNoOlderMessagesReturnsNull() {
        // 3 条、保留最近 3 → 无可压缩旧消息 → null
        assertNull(svc.summarizeAndPersist(9L, messages(3), 3));
        verify(contextMapper, never()).upsert(any());
    }

    @Test
    void summarizeAndPersistBuildsTextAndWritesUpsert() {
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(1);
        String summary = svc.summarizeAndPersist(9L, messages(5), 2);
        assertTrue(summary.startsWith("历史摘要:"));

        ArgumentCaptor<AssistantSessionContextEntity> cap =
                ArgumentCaptor.forClass(AssistantSessionContextEntity.class);
        verify(contextMapper).upsert(cap.capture());
        AssistantSessionContextEntity written = cap.getValue();
        assertEquals(9L, written.getSessionId());
        assertEquals(3L, written.getSourceMessageId()); // 保留最近 2 条 → 压缩前 3 条，最后压缩 id=3
        assertEquals(NOW, written.getUpdatedAt());
    }

    @Test
    void summarizeAndPersistUpsertFailureThrowsBusinessException() {
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.summarizeAndPersist(9L, messages(3), 1));
        assertEquals("保存会话摘要失败", e.getMessage());
    }

    @Test
    void summarizeAndPersistRecentLimitFlooredToOne() {
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(1);
        // 3 条、recent limit 0 → 弹为 1 → 压缩 2 条
        svc.summarizeAndPersist(9L, messages(3), 0);
        ArgumentCaptor<AssistantSessionContextEntity> cap =
                ArgumentCaptor.forClass(AssistantSessionContextEntity.class);
        verify(contextMapper).upsert(cap.capture());
        assertEquals(2L, cap.getValue().getSourceMessageId());
    }

    // ---------- 摘要文本形态 ----------

    @Test
    void summaryTextLabelsAndTruncatesLongContent() {
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(1);
        // 4 条、保留最近 2 → 压缩前 2 条：用户 + 助手（长文本助手落在压缩区内 → 触发 160 截断）
        String longContent = "x".repeat(200);
        String summary = svc.summarizeAndPersist(9L, List.of(
                msg(1L, "USER", "你好"),
                msg(2L, "ASSISTANT", longContent),
                msg(3L, "USER", "保留A"),
                msg(4L, "USER", "保留B")), 2);
        assertTrue(summary.startsWith("历史摘要:"));
        assertTrue(summary.contains("- 用户：你好"));
        assertTrue(summary.contains("- 助手："));
        assertTrue(summary.contains("..."));
    }

    private static List<AssistantMessageEntity> messages(int n) {
        java.util.ArrayList<AssistantMessageEntity> list = new java.util.ArrayList<>();
        for (long i = 1; i <= n; i++) {
            list.add(msg(i, "USER", "消息" + i));
        }
        return list;
    }
}