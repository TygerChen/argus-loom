package com.argus.rag.assistant.memory;

import com.argus.rag.assistant.mapper.AssistantMessageMapper;
import com.argus.rag.assistant.mapper.AssistantSessionContextMapper;
import com.argus.rag.assistant.model.entity.AssistantMessageEntity;
import com.argus.rag.assistant.model.entity.AssistantSessionContextEntity;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantShortTermMemoryMaintenanceService} 短期记忆维护契约（mock mapper + summarizer，
 * 零 DB/零 LLM，纯 JVM）：
 * - estimateTokens：null/空→0、仅为空白→max(1,0)=1、总字符 ÷4（至少 1）、跨条求和；
 * - shouldMaintainSessionMemory：空/低量→false、新消息数≥sessionMemoryMessageTrigger 或
 *   估算 token≥sessionMemoryTokenTrigger→true；
 * - shouldCompactSession：总 token 未超阈值→false、超阈值但新量未达两者→false、
 *   新消息数/新 token 达触发词→true；
 * - maintain（经 maintainBeforeResponse/AfterResponse）：无足够新增→早退不写；首次（无存量记录）→
 *   upsert 写 contextVersion=1 + base/range 映射；存量→乐观锁 updateShortTermMemoryWithVersion(cxt, expectedVersion)
 *   且 contextVersion 递增；compact 分支在总 token 超阈值且新消息达标时生成紧凑摘要；
 *   upsert/update 返回 0→「短期记忆写回失败」。
 */
@DisplayName("助手短期记忆维护（AssistantShortTermMemoryMaintenanceService）")
class AssistantShortTermMemoryMaintenanceServiceTest {

    private AssistantMessageMapper messageMapper;
    private AssistantSessionContextMapper contextMapper;
    private AssistantMemorySummarizer summarizer;
    private AssistantShortTermMemoryMaintenanceService svc;

    @BeforeEach
    void setUp() {
        messageMapper = mock(AssistantMessageMapper.class);
        contextMapper = mock(AssistantSessionContextMapper.class);
        summarizer = mock(AssistantMemorySummarizer.class);
        svc = svc(2000, 4, 1200, 6, 1800);
        when(summarizer.summarizeSessionMemory(any(), anyList(), any(), any()))
                .thenReturn("MEM");
        when(summarizer.summarizeCompactSummary(any(), anyString(), anyList()))
                .thenReturn("COMPACT");
    }

    /** 按真实构造器顺序构建：消息 mapper、上下文 mapper、summarizer、随后 5 个 int 阈值。 */
    private AssistantShortTermMemoryMaintenanceService svc(
            int sessionTokenThreshold,
            int sessionMemoryMessageTrigger,
            int sessionMemoryTokenTrigger,
            int compactMessageTrigger,
            int compactTokenTrigger) {
        return new AssistantShortTermMemoryMaintenanceService(
                messageMapper, contextMapper, summarizer,
                sessionTokenThreshold, sessionMemoryMessageTrigger, sessionMemoryTokenTrigger,
                compactMessageTrigger, compactTokenTrigger);
    }

    // ---------- helpers ----------

    private static AssistantMessageEntity msg(Long id, String content) {
        AssistantMessageEntity e = new AssistantMessageEntity();
        e.setId(id);
        e.setContent(content);
        return e;
    }

    private static AssistantSessionContextEntity existing(
            Long sessionId, Long rangeEnd, Long version, String memory) {
        AssistantSessionContextEntity c = new AssistantSessionContextEntity();
        c.setSessionId(sessionId);
        c.setSessionMemoryRangeEndMessageId(rangeEnd);
        c.setContextVersion(version);
        c.setSessionMemory(memory);
        return c;
    }

    private void stubMessages(List<AssistantMessageEntity> messages) {
        when(messageMapper.selectBySessionIdOrderByCreatedAt(9L)).thenReturn(messages);
    }

    // ---------- estimateTokens ----------

    @Test
    void estimateTokensNullEmptyIsZero() {
        assertEquals(0, svc.estimateTokens(null));
        assertEquals(0, svc.estimateTokens(List.of()));
    }

    @Test
    void estimateTokensBlankOrNullContentCountsOne() {
        // 非空列表但对每条均为空白/null → totalChars=0 → Math.max(1, 0/4) = 1
        assertEquals(1, svc.estimateTokens(List.of(msg(1L, "  "), msg(2L, null))));
    }

    @Test
    void estimateTokensDividesTotalCharsByFour() {
        // 2 条各 8 字符 → 16/4 = 4
        List<AssistantMessageEntity> messages = List.of(
                msg(1L, "12345678"),
                msg(2L, "abcdefgh"));
        assertEquals(4, svc.estimateTokens(messages));
    }

    @Test
    void estimateTokensFloorToAtLeastOne() {
        // 2 字符 → 2/4=0 → 上限到 1
        assertEquals(1, svc.estimateTokens(List.of(msg(1L, "ab"))));
    }

    // ---------- shouldMaintainSessionMemory ----------

    @Test
    void shouldMaintainSessionMemoryEmptyIsFalse() {
        assertFalse(svc.shouldMaintainSessionMemory(List.of(), 0L));
        assertFalse(svc.shouldMaintainSessionMemory(null, 5L));
    }

    @Test
    void shouldMaintainSessionMemoryBelowBothThresholdsIsFalse() {
        List<AssistantMessageEntity> three = List.of(
                msg(1L, "a"), msg(2L, "b"), msg(3L, "c"));
        assertFalse(svc.shouldMaintainSessionMemory(three, 0L));
    }

    @Test
    void shouldMaintainSessionMemoryMessageTriggerTrue() {
        List<AssistantMessageEntity> msgs = List.of(
                msg(1L, "a"), msg(2L, "b"), msg(3L, "c"), msg(4L, "d"));
        assertTrue(svc.shouldMaintainSessionMemory(msgs, 0L));
    }

    @Test
    void shouldMaintainSessionMemoryTokenTriggerTrue() {
        // 1 条 4800 字符 → estimateTokens=1200 ≥ 1200 → true（消息数 1 < 4）
        String longContent = "x".repeat(4800);
        assertTrue(svc.shouldMaintainSessionMemory(List.of(msg(1L, longContent)), 0L));
    }

    // ---------- shouldCompactSession ----------

    @Test
    void shouldCompactBelowTotalTokenThresholdIsFalse() {
        assertFalse(svc.shouldCompactSession(100, 100, 2000));
    }

    @Test
    void shouldCompactOverTotalButNewMissesBothIsFalse() {
        assertFalse(svc.shouldCompactSession(5000, 1, 0));
    }

    @Test
    void shouldCompactNewMessageTriggerTrue() {
        assertTrue(svc.shouldCompactSession(5000, 6, 0));
    }

    @Test
    void shouldCompactNewTokenTriggerTrue() {
        assertTrue(svc.shouldCompactSession(5000, 0, 1800));
    }

    // ---------- maintain 编排 ----------

    @Test
    void maintainNoOpWhenNotEnoughNewMessages() {
        AssistantSessionContextEntity ctx = existing(9L, 100L, 1L, "OLD");
        when(contextMapper.selectBySessionId(9L)).thenReturn(ctx);
        stubMessages(List.of(msg(101L, "x"))); // 仅 1 条新消息 < 4，且 token 不足
        svc.maintainBeforeResponse(9L, AssistantToolMode.CHAT, null, 101L);
        verify(summarizer, never()).summarizeSessionMemory(any(), any(), any(), any());
        verify(summarizer, never()).summarizeCompactSummary(any(), any(), any());
        verify(contextMapper, never()).upsert(any());
        verify(contextMapper, never()).updateShortTermMemoryWithVersion(any(), anyLong());
    }

    @Test
    void maintainFirstTimeWritesUpsertWithVersionOne() {
        when(contextMapper.selectBySessionId(9L)).thenReturn(null);
        stubMessages(List.of(msg(10L, "a"), msg(11L, "b"), msg(12L, "c"), msg(13L, "d")));
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(1);

        svc.maintainBeforeResponse(9L, AssistantToolMode.CHAT, 3L, 13L);

        ArgumentCaptor<AssistantSessionContextEntity> cap =
                ArgumentCaptor.forClass(AssistantSessionContextEntity.class);
        verify(contextMapper).upsert(cap.capture());
        AssistantSessionContextEntity written = cap.getValue();
        assertEquals(9L, written.getSessionId());
        assertEquals("MEM", written.getSessionMemory());
        assertEquals(10L, written.getSessionMemoryBaseMessageId());
        assertEquals(13L, written.getSessionMemoryRangeEndMessageId());
        assertEquals(1L, written.getContextVersion());
        assertNotNull(written.getUpdatedAt());
        verify(contextMapper, never()).updateShortTermMemoryWithVersion(any(), anyLong());
    }

    @Test
    void maintainExistingWritesOptimisticLockWithExpectedVersion() {
        AssistantSessionContextEntity existing = existing(9L, 10L, 5L, null);
        when(contextMapper.selectBySessionId(9L)).thenReturn(existing);
        stubMessages(List.of(
                msg(11L, "a"), msg(12L, "b"), msg(13L, "c"), msg(14L, "d")));
        when(contextMapper.updateShortTermMemoryWithVersion(any(), eq(5L))).thenReturn(1);

        svc.maintainAfterResponse(9L, AssistantToolMode.CHAT, null, 14L);

        verify(contextMapper).updateShortTermMemoryWithVersion(
                any(AssistantSessionContextEntity.class), eq(5L));
        // 存量写回走乐观锁，绝不 upsert
        verify(contextMapper, never()).upsert(any());
        ArgumentCaptor<AssistantSessionContextEntity> cap =
                ArgumentCaptor.forClass(AssistantSessionContextEntity.class);
        verify(contextMapper).updateShortTermMemoryWithVersion(cap.capture(), eq(5L));
        assertEquals(6L, cap.getValue().getContextVersion());
        assertEquals(14L, cap.getValue().getSessionMemoryRangeEndMessageId());
    }

    @Test
    void maintainFirstTimeUpdaterFailureThrowsBusinessException() {
        when(contextMapper.selectBySessionId(9L)).thenReturn(null);
        stubMessages(List.of(msg(1L, "a"), msg(2L, "b"), msg(3L, "c"), msg(4L, "d")));
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.maintainBeforeResponse(9L, AssistantToolMode.CHAT, null, 4L));
        assertEquals("短期记忆写回失败", e.getMessage());
    }

    @Test
    void maintainExistingUpdateFailureThrowsBusinessException() {
        AssistantSessionContextEntity existing = existing(9L, 10L, 2L, null);
        when(contextMapper.selectBySessionId(9L)).thenReturn(existing);
        stubMessages(List.of(
                msg(11L, "a"), msg(12L, "b"), msg(13L, "c"), msg(14L, "d")));
        when(contextMapper.updateShortTermMemoryWithVersion(any(), eq(2L))).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.maintainAfterResponse(9L, AssistantToolMode.CHAT, null, 14L));
        assertEquals("短期记忆写回失败", e.getMessage());
    }

    @Test
    void maintainCompactsWhenTotalTokensOverAndNewMessageCountHit() {
        // compact 专用阈值：总 token 阈 0 → 任何非空即超；compact 触发按新消息数 2
        AssistantShortTermMemoryMaintenanceService compactSvc =
                svc(0, 4, 1200, 2, 1800);
        when(contextMapper.selectBySessionId(9L)).thenReturn(null);
        // 4 条新消息（11..14）→ shouldMaintain true（size 4）；compact 分支 newMessageCount=4 ≥ 2
        stubMessages(List.of(
                msg(11L, "a"), msg(12L, "b"), msg(13L, "c"), msg(14L, "d")));
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(1);

        compactSvc.maintainBeforeResponse(9L, AssistantToolMode.CHAT, null, 13L);

        verify(summarizer).summarizeCompactSummary(any(), eq("MEM"), anyList());
        ArgumentCaptor<AssistantSessionContextEntity> cap =
                ArgumentCaptor.forClass(AssistantSessionContextEntity.class);
        verify(contextMapper).upsert(cap.capture());
        AssistantSessionContextEntity written = cap.getValue();
        assertEquals("COMPACT", written.getCompactSummary());
        assertEquals(11L, written.getCompactSummaryBaseMessageId());
        // newMessages 的 last id 才是 range end（当前消息 13ID 不拦压缩记录）
        assertEquals(14L, written.getCompactSummaryRangeEndMessageId());
    }

    @Test
    void maintainAfterResponseSameWritePathAsBefore() {
        when(contextMapper.selectBySessionId(9L)).thenReturn(null);
        stubMessages(List.of(msg(1L, "a"), msg(2L, "b"), msg(3L, "c"), msg(4L, "d")));
        when(contextMapper.upsert(any(AssistantSessionContextEntity.class))).thenReturn(1);
        svc.maintainAfterResponse(9L, AssistantToolMode.CHAT, null, 4L);
        verify(contextMapper, times(1)).upsert(any(AssistantSessionContextEntity.class));
    }
}