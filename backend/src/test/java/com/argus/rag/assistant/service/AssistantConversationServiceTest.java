package com.argus.rag.assistant.service;

import com.argus.rag.assistant.mapper.AssistantMessageMapper;
import com.argus.rag.assistant.mapper.AssistantSessionContextMapper;
import com.argus.rag.assistant.mapper.AssistantSessionMapper;
import com.argus.rag.assistant.memory.AssistantSessionSummaryService;
import com.argus.rag.assistant.memory.AssistantShortTermMemoryMaintenanceService;
import com.argus.rag.assistant.memory.longterm.MemoryExtractionEvent;
import com.argus.rag.assistant.model.dto.message.AssistantMessageCreateDTO;
import com.argus.rag.assistant.model.entity.AssistantMessageEntity;
import com.argus.rag.assistant.model.entity.AssistantSessionContextEntity;
import com.argus.rag.assistant.model.entity.AssistantSessionEntity;
import com.argus.rag.assistant.model.enums.AssistantMessageRole;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.assistant.model.vo.message.AssistantMessageVO;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantConversationService} 助手对话编排契约（mock 3 mapper + 4 服务 + eventPublisher，
 * 用真实 ObjectMapper，纯 JVM/零 DB）：
 * - 入参短路：requireUserId/requireSessionId/requireCreateDTO(dto null、toolMode null)/requireContent(空白)/
 *   normalizeLimit(<=0)/normalizeGroupId(主 id<=0、CHAT 归空)；
 * - saveMessage：insert 非 1→「消息保存失败」、updateLastMessageAt 非 1→「会话更新时间刷新失败」、
 *   首条 USER 触发 autoRenameSessionIfNeeded、USER→maintainBeforeResponse / ASSISTANT→maintainAfterResponse、
 *   ASSISTANT 发布 MemoryExtractionEvent 且异步发布异常被吞；
 * - structuredPayload 富化：KB_SEARCH 合并 groupIds+routeStrategy、CHAT 下 groupId 归 null、既有 JSON 保留合并；
 * - loadRecentMessages/Before：归属校验 → 排序映射 → limit 封顶 100 → 实体 structuredPayload 回读 groupIds/route；
 * - loadConversationContext：复用既有摘要、应再生成时走 summarizeAndPersist、量不足时无摘要。
 */
@DisplayName("助手对话（AssistantConversationService）")
class AssistantConversationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 12, 0);

    private AssistantMessageMapper messageMapper;
    private AssistantSessionContextMapper contextMapper;
    private AssistantSessionMapper sessionMapper;
    private AssistantSessionSummaryService summaryService;
    private AssistantShortTermMemoryMaintenanceService shortTermService;
    private AssistantSessionService sessionService;
    private CurrentUserService currentUserService;
    private ApplicationEventPublisher eventPublisher;
    private ObjectMapper objectMapper;
    private AssistantConversationService svc;

    @BeforeEach
    void setUp() {
        messageMapper = mock(AssistantMessageMapper.class);
        contextMapper = mock(AssistantSessionContextMapper.class);
        sessionMapper = mock(AssistantSessionMapper.class);
        summaryService = mock(AssistantSessionSummaryService.class);
        shortTermService = mock(AssistantShortTermMemoryMaintenanceService.class);
        sessionService = mock(AssistantSessionService.class);
        currentUserService = mock(CurrentUserService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();
        svc = new AssistantConversationService(
                messageMapper, contextMapper, sessionMapper,
                summaryService, shortTermService, sessionService,
                currentUserService, objectMapper, eventPublisher);
        when(currentUserService.requireBusinessUser()).thenReturn(
                new CurrentUserService.CurrentUser(7L, "u7", "显示名"));
    }

    // ---------- helpers ----------

    private static AssistantSessionEntity session() {
        AssistantSessionEntity e = new AssistantSessionEntity();
        e.setId(9L);
        e.setUserId(7L);
        e.setTitle("t");
        e.setLastMessageAt(NOW.minusMinutes(5));
        e.setCreatedAt(NOW.minusHours(1));
        e.setUpdatedAt(NOW);
        return e;
    }

    private static AssistantMessageEntity msg(Long id, AssistantMessageRole role,
                                              String content, String payload, LocalDateTime createdAt) {
        AssistantMessageEntity e = new AssistantMessageEntity();
        e.setId(id);
        e.setSessionId(9L);
        e.setRole(role.name());
        e.setToolMode(AssistantToolMode.CHAT.name());
        e.setContent(content);
        e.setStructuredPayload(payload);
        e.setCreatedAt(createdAt);
        return e;
    }

    private static AssistantMessageCreateDTO dto(Long sessionId, AssistantToolMode mode, Long groupId,
            List<Long> groupIds, KbRouteStrategy route, String content, String payload) {
        return new AssistantMessageCreateDTO(sessionId, mode, groupId, groupIds, route, content, payload);
    }

    private void stubOwnedSession() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session());
    }

    private void stubInsertSuccess(long id) {
        when(messageMapper.insert(any(AssistantMessageEntity.class))).thenAnswer(inv -> {
            ((AssistantMessageEntity) inv.getArgument(0)).setId(id);
            return 1;
        });
    }

    private void stubSessionTouchOk() {
        when(sessionMapper.updateLastMessageAt(any(), any(), any())).thenReturn(1);
    }

    // ---------- 入参校验短路 ----------

    @Test
    void userIdNullOrNonPositiveRejectedWithoutQuery() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(null,
                        dto(9L, AssistantToolMode.CHAT, null, null, null, "hi", null)));
        assertEquals("userId 非法", e.getMessage());
        assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(0L,
                        dto(9L, AssistantToolMode.CHAT, null, null, null, "hi", null)));
        verify(sessionMapper, never()).selectByIdAndUserId(any(), any());
    }

    @Test
    void invalidSessionIdRejectedWithoutQuery() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(7L,
                        dto(0L, AssistantToolMode.CHAT, null, null, null, "hi", null)));
        assertEquals("sessionId 非法", e.getMessage());
        assertThrows(BusinessException.class, () -> svc.loadRecentMessages(7L, null, 10));
        verify(sessionMapper, never()).selectByIdAndUserId(any(), any());
    }

    @Test
    void nullDtoRejected() {
        assertThrows(BusinessException.class, () -> svc.saveAssistantMessage(7L, null));
    }

    @Test
    void nullToolModeRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(7L,
                        new AssistantMessageCreateDTO(9L, null, null, null, null, "hi", null)));
        assertEquals("toolMode 不能为空", e.getMessage());
    }

    @Test
    void blankContentRejectedAfterOwnership() {
        stubOwnedSession();
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(7L,
                        dto(9L, AssistantToolMode.CHAT, null, null, null, "   ", null)));
        assertEquals("content 不能为空", e.getMessage());
        verify(messageMapper, never()).insert(any());
    }

    @Test
    void nonPositiveGroupIdsAreFilteredOut() throws Exception {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        when(messageMapper.countBySessionId(9L)).thenReturn(2L);
        svc.saveUserMessage(7L,
                dto(9L, AssistantToolMode.KB_SEARCH, -5L, List.of(3L, -1L, 0L), null, "查", null));
        ArgumentCaptor<AssistantMessageEntity> cap = ArgumentCaptor.forClass(AssistantMessageEntity.class);
        verify(messageMapper).insert(cap.capture());
        // 负数/0 的 groupId 与 groupIds 项都被过滤，仅保留正 id 3 为主 groupId
        assertEquals(3L, cap.getValue().getGroupId());
        JsonNode root = objectMapper.readTree(cap.getValue().getStructuredPayload());
        assertEquals("[3]", root.path("groupIds").toString());
    }

    @Test
    void messagesForUnOwnedSessionRejected() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(7L,
                        dto(9L, AssistantToolMode.CHAT, null, null, null, "hi", null)));
        assertEquals("会话不存在", e.getMessage());
    }

    // ---------- 消息保存 ----------

    @Test
    void saveInsertFailureRejects() {
        stubOwnedSession();
        // 未 stub insert → 0 行 + 无 id
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(7L,
                        dto(9L, AssistantToolMode.CHAT, null, null, null, "hi", null)));
        assertEquals("消息保存失败", e.getMessage());
        verify(sessionMapper, never()).updateLastMessageAt(any(), any(), any());
    }

    @Test
    void saveInsertOkButSessionTouchFailsRejects() {
        stubOwnedSession();
        stubInsertSuccess(11L);
        when(sessionMapper.updateLastMessageAt(any(), any(), any())).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.saveUserMessage(7L,
                        dto(9L, AssistantToolMode.CHAT, null, null, null, "hi", null)));
        assertEquals("会话更新时间刷新失败", e.getMessage());
    }

    @Test
    void saveFirstUserMessageAutoRenamesAndBeforeMaintenance() {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        when(messageMapper.countBySessionId(9L)).thenReturn(1L);
        AssistantMessageVO vo = svc.saveUserMessage(7L,
                dto(9L, AssistantToolMode.KB_SEARCH, 3L, List.of(1L, 2L, 3L),
                        KbRouteStrategy.AUTO, "报表数据", null));
        assertEquals(11L, vo.messageId());
        assertEquals(AssistantMessageRole.USER, vo.role());
        assertEquals("报表数据", vo.content());
        verify(sessionService).autoRenameSessionIfNeeded(7L, 9L, "报表数据");
        // 主 groupId = 最小正 id（1），USER 路径走 before 维护
        verify(shortTermService).maintainBeforeResponse(9L, AssistantToolMode.KB_SEARCH, 1L, 11L);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void saveNonFirstMessageSkipsAutoRename() {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        when(messageMapper.countBySessionId(9L)).thenReturn(2L);
        svc.saveUserMessage(7L,
                dto(9L, AssistantToolMode.CHAT, null, null, null, "hi", null));
        verify(sessionService, never()).autoRenameSessionIfNeeded(any(), any(), any());
    }

    @Test
    void saveAssistantMessageAfterMaintenanceAndPublishesEvent() {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        AssistantMessageVO vo = svc.saveAssistantMessage(7L,
                dto(9L, AssistantToolMode.KB_SEARCH, 2L, null, null, "回复内容", null));
        assertEquals(AssistantMessageRole.ASSISTANT, vo.role());
        verify(shortTermService).maintainAfterResponse(9L, AssistantToolMode.KB_SEARCH, 2L, 11L);
        ArgumentCaptor<MemoryExtractionEvent> cap = ArgumentCaptor.forClass(MemoryExtractionEvent.class);
        verify(eventPublisher).publishEvent(cap.capture());
        MemoryExtractionEvent ev = cap.getValue();
        assertEquals(7L, ev.userId());
        assertEquals(2L, ev.tenantId()); // 主 groupId 作为组维度传入
        assertEquals(9L, ev.sessionId());
        assertEquals("回复内容", ev.reply());
        verify(messageMapper, never()).countBySessionId(any());
    }

    @Test
    void eventPublishExceptionSwallowed() {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        doThrow(new RuntimeException("async down")).when(eventPublisher).publishEvent(any());
        AssistantMessageVO vo = svc.saveAssistantMessage(7L,
                dto(9L, AssistantToolMode.CHAT, null, null, null, "R", null));
        assertEquals(11L, vo.messageId());
        assertEquals("R", vo.content());
    }

    // ---------- structuredPayload 富化 ----------

    @Test
    void kBSearchEnrichesPayloadWithGroupsAndRoute() throws Exception {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        when(messageMapper.countBySessionId(9L)).thenReturn(2L);
        svc.saveUserMessage(7L,
                dto(9L, AssistantToolMode.KB_SEARCH, 3L, List.of(1L, 2L, 3L),
                        KbRouteStrategy.AUTO, "hi", null));
        ArgumentCaptor<AssistantMessageEntity> cap = ArgumentCaptor.forClass(AssistantMessageEntity.class);
        verify(messageMapper).insert(cap.capture());
        AssistantMessageEntity e = cap.getValue();
        assertEquals(1L, e.getGroupId()); // 最低正 id 为主 groupId，KB_SEARCH 保留
        JsonNode root = objectMapper.readTree(e.getStructuredPayload());
        assertEquals("AUTO", root.path("routeStrategy").asText());
        assertEquals("[1,2,3]", root.path("groupIds").toString());
    }

    @Test
    void chatModeZeroesGroupIdButKeepsPayload() throws Exception {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        when(messageMapper.countBySessionId(9L)).thenReturn(2L);
        svc.saveUserMessage(7L,
                dto(9L, AssistantToolMode.CHAT, null, List.of(7L, 8L), null, "hi", null));
        ArgumentCaptor<AssistantMessageEntity> cap = ArgumentCaptor.forClass(AssistantMessageEntity.class);
        verify(messageMapper).insert(cap.capture());
        assertNull(cap.getValue().getGroupId());
        JsonNode root = objectMapper.readTree(cap.getValue().getStructuredPayload());
        assertEquals("[7,8]", root.path("groupIds").toString());
    }

    @Test
    void enrichPreservesExistingPayloadFields() throws Exception {
        stubOwnedSession();
        stubInsertSuccess(11L);
        stubSessionTouchOk();
        when(messageMapper.countBySessionId(9L)).thenReturn(2L);
        String original = "{\"mode\":\"fast\",\"tags\":[\"x\"]}";
        svc.saveUserMessage(7L,
                dto(9L, AssistantToolMode.KB_SEARCH, 1L, List.of(1L), KbRouteStrategy.SELECTED, "hi", original));
        ArgumentCaptor<AssistantMessageEntity> cap = ArgumentCaptor.forClass(AssistantMessageEntity.class);
        verify(messageMapper).insert(cap.capture());
        JsonNode root = objectMapper.readTree(cap.getValue().getStructuredPayload());
        assertEquals("fast", root.path("mode").asText());
        assertEquals("x", root.path("tags").get(0).asText());
        assertEquals("SELECTED", root.path("routeStrategy").asText());
    }

    // ---------- loadRecentMessages ----------

    @Test
    void loadRecentMessagesRejectsInvalidLimit() {
        stubOwnedSession();
        assertThrows(BusinessException.class, () -> svc.loadRecentMessages(7L, 9L, 0));
        verify(messageMapper, never()).selectRecentBySessionId(any(), anyInt());
    }

    @Test
    void loadRecentMessagesSortsAscAndCapsLimit() {
        stubOwnedSession();
        when(messageMapper.selectRecentBySessionId(9L, 100)).thenReturn(List.of(
                msg(2L, AssistantMessageRole.USER, "A", null, NOW.plusMinutes(2)),
                msg(1L, AssistantMessageRole.USER, "B", null, NOW.minusMinutes(10)),
                msg(3L, AssistantMessageRole.ASSISTANT, "C", null, NOW.plusMinutes(1))
        ));
        List<AssistantMessageVO> list = svc.loadRecentMessages(7L, 9L, 150);
        verify(messageMapper).selectRecentBySessionId(9L, 100); // 150 → 封顶 100
        assertEquals(3, list.size());
        // 升序：最早(B id1) → C id3 → 最新 A id2
        assertEquals("B", list.get(0).content());
        assertEquals("A", list.get(2).content());
        assertEquals(AssistantMessageRole.ASSISTANT, list.get(1).role());
    }

    @Test
    void loadRecentMessagesBeforeMapsCursor() {
        stubOwnedSession();
        when(messageMapper.selectRecentBySessionIdBefore(9L, 50L, 20)).thenReturn(List.of(
                msg(40L, AssistantMessageRole.USER, "较早", null, NOW.minusHours(2))
        ));
        List<AssistantMessageVO> list = svc.loadRecentMessagesBefore(7L, 9L, 50L, 20);
        verify(messageMapper).selectRecentBySessionIdBefore(9L, 50L, 20);
        assertEquals(1, list.size());
        assertEquals(40L, list.get(0).messageId());
    }

    @Test
    void loadRecentMessageRoundTripsStoredPayload() {
        stubOwnedSession();
        String payload = "{\"groupIds\":[1,2],\"routeStrategy\":\"AUTO\"}";
        when(messageMapper.selectRecentBySessionId(9L, 10)).thenReturn(List.of(
                msg(5L, AssistantMessageRole.ASSISTANT, "R", payload, NOW)
        ));
        List<AssistantMessageVO> list = svc.loadRecentMessages(7L, 9L, 10);
        AssistantMessageVO vo = list.get(0);
        assertEquals(List.of(1L, 2L), vo.groupIds());
        assertEquals(KbRouteStrategy.AUTO, vo.routeStrategy());
        assertEquals(payload, vo.structuredPayload());
    }

    // ---------- getConversationContext ----------

    @Test
    void getConversationContextReusesLatestReusableSummary() {
        stubOwnedSession();
        AssistantSessionContextEntity context = new AssistantSessionContextEntity();
        context.setCompactSummary("  紧凑  ");
        context.setSessionMemory("记忆");
        when(contextMapper.selectBySessionId(9L)).thenReturn(context);
        when(summaryService.loadReusableSummary(9L, NOW.minusMinutes(5))).thenReturn("可复用摘要");
        when(messageMapper.selectRecentBySessionId(9L, 5)).thenReturn(List.of(
                msg(1L, AssistantMessageRole.USER, "hi", null, NOW.minusMinutes(4))
        ));
        var vo = svc.getConversationContext(9L, 5);
        assertEquals("可复用摘要", vo.summaryText());
        assertEquals("紧凑", vo.compactSummary()); // 两端空白被去除
        assertEquals("记忆", vo.sessionMemory());
        assertEquals(1, vo.recentMessages().size());
    }

    @Test
    void loadConversationContextReusesExistingSummary() {
        stubOwnedSession();
        when(summaryService.loadReusableSummary(9L, NOW.minusMinutes(5))).thenReturn("S");
        var ctx = svc.loadConversationContext(7L, 9L, 10);
        assertEquals("S", ctx.summaryText());
        verify(summaryService, never()).summarizeAndPersist(any(), any(), anyInt());
    }

    @Test
    void loadConversationContextTriggersSummarizeWhenNeeded() {
        stubOwnedSession();
        when(summaryService.loadReusableSummary(any(), any())).thenReturn(null);
        when(messageMapper.countBySessionId(9L)).thenReturn(25L);
        when(messageMapper.selectBySessionIdOrderByCreatedAt(9L)).thenReturn(List.of(
                msg(1L, AssistantMessageRole.USER, "q", null, NOW)
        ));
        when(summaryService.estimateTokens(any())).thenReturn(9000);
        when(summaryService.shouldSummarize(anyLong(), anyInt(), any())).thenReturn(true);
        when(summaryService.summarizeAndPersist(any(), any(), anyInt())).thenReturn("GEN");
        var ctx = svc.loadConversationContext(7L, 9L, 10);
        verify(messageMapper).countBySessionId(9L);
        verify(messageMapper).selectBySessionIdOrderByCreatedAt(9L);
        assertEquals("GEN", ctx.summaryText());
    }
}