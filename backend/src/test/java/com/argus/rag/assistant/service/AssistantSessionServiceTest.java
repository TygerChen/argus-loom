package com.argus.rag.assistant.service;

import com.argus.rag.assistant.mapper.AssistantMessageMapper;
import com.argus.rag.assistant.mapper.AssistantSessionContextMapper;
import com.argus.rag.assistant.mapper.AssistantSessionMapper;
import com.argus.rag.assistant.model.dto.session.UpdateAssistantSessionRequest;
import com.argus.rag.assistant.model.entity.AssistantSessionEntity;
import com.argus.rag.assistant.model.vo.session.AssistantSessionDetailVO;
import com.argus.rag.assistant.model.vo.session.AssistantSessionListItemVO;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantSessionService} 助手会话管理契约（mock 3 mapper + currentUserService，纯 JVM/零 DB）：
 * - createSession：requireBusinessUser → buildNewSession("新会话"/ACTIVE) → insert；insert 影响行非 1 或 setId 缺失→"创建会话失败"；
 * - listSessions：按用户+includeArchived 委派查询 → ListItemVO 映射；
 * - archive/restore：requireOwnedSession（不存在→"会话不存在"）后 updateArchived true/false；
 * - getSessionDetail：requireSessionId（null/<=0→"sessionId 非法"）+ 归属校验 → DetailVO；
 * - renameSession：title null/空白→"title 不能为空"、>255→"title 长度不能超过 255"、normalize 折叠空白、
 *   updateTitle 非 1→"重命名会话失败"、成功→更新实体标题+时间；
 * - deleteSession：先删上下文与消息，再 deleteByIdAndUserId；非 1→"删除会话失败"；
 * - autoRenameSessionIfNeeded：非法 id 早退、非默认标题早退、生成标题为默认或空早退、成功按首条消息归一化自动命名。
 */
@DisplayName("助手会话（AssistantSessionService）")
class AssistantSessionServiceTest {

    private AssistantSessionMapper sessionMapper;
    private AssistantMessageMapper messageMapper;
    private AssistantSessionContextMapper ctxMapper;
    private CurrentUserService currentUserService;
    private AssistantSessionService svc;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AssistantSessionMapper.class);
        messageMapper = mock(AssistantMessageMapper.class);
        ctxMapper = mock(AssistantSessionContextMapper.class);
        currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireBusinessUser()).thenReturn(
                new CurrentUserService.CurrentUser(7L, "u7", "显示名"));
        svc = new AssistantSessionService(sessionMapper, messageMapper, ctxMapper, currentUserService);
    }

    private static AssistantSessionEntity session(Long id, String title) {
        AssistantSessionEntity e = new AssistantSessionEntity();
        e.setId(id);
        e.setUserId(7L);
        e.setTitle(title);
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0);
        e.setLastMessageAt(now);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    // ---------- createSession ----------

    @Test
    void createSessionInsertsNewActiveSession() {
        when(sessionMapper.insert(any(AssistantSessionEntity.class))).thenAnswer(inv -> {
            ((AssistantSessionEntity) inv.getArgument(0)).setId(5L);
            return 1;
        });
        AssistantSessionDetailVO vo = svc.createSession();
        assertEquals(5L, vo.sessionId());
        assertEquals("新会话", vo.title());
        assertEquals("ACTIVE", vo.status());
        verify(sessionMapper).insert(any(AssistantSessionEntity.class));
    }

    @Test
    void createSessionRejectsWhenInsertFails() {
        // 未 stub insert → 0 行，且实体无 id → 抛"创建会话失败"
        BusinessException e = assertThrows(BusinessException.class, svc::createSession);
        assertEquals("创建会话失败", e.getMessage());
    }

    // ---------- listSessions ----------

    @Test
    void listSessionsMapsItemsInDescOrder() {
        when(sessionMapper.selectByUserIdOrderByLastMessageAtDesc(7L, true)).thenReturn(
                List.of(session(2L, "会话B"), session(1L, "会话A")));
        List<AssistantSessionListItemVO> list = svc.listSessions(true);
        assertEquals(2, list.size());
        assertEquals(2L, list.get(0).sessionId());
        assertEquals("会话B", list.get(0).title());
        assertEquals(1L, list.get(1).sessionId());
    }

    // ---------- archive / restore ----------

    @Test
    void archiveRequiresOwnershipBeforeUpdate() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.archiveSession(7L, 9L));
        assertEquals("会话不存在", e.getMessage());
        verify(sessionMapper, never()).updateArchived(any(), any());
    }

    @Test
    void archiveUpdatesArchivedFlag() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "t"));
        svc.archiveSession(7L, 9L);
        verify(sessionMapper).updateArchived(9L, true);
    }

    @Test
    void restoreUpdatesArchivedFlag() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "t"));
        svc.restoreSession(7L, 9L);
        verify(sessionMapper).updateArchived(9L, false);
    }

    // ---------- getSessionDetail ----------

    @Test
    void getSessionDetailRejectsInvalidId() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.getSessionDetail(0L));
        assertEquals("sessionId 非法", e.getMessage());
    }

    @Test
    void getSessionDetailRejectsNonOwned() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.getSessionDetail(9L));
        assertEquals("会话不存在", e.getMessage());
    }

    @Test
    void getSessionDetailReturnsOwnedEntity() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "t"));
        AssistantSessionDetailVO vo = svc.getSessionDetail(9L);
        assertEquals(9L, vo.sessionId());
        assertEquals("t", vo.title());
    }

    // ---------- rename ----------

    @Test
    void renameNormalizesWhitespaceAndUpdates() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "old"));
        when(sessionMapper.updateTitle(eq(9L), eq(7L), any(), any())).thenReturn(1);
        svc.renameSession(9L, new UpdateAssistantSessionRequest("  打包  数据  "));
        ArgumentCaptor<String> titleCap = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateTitle(eq(9L), eq(7L), titleCap.capture(), any());
        assertEquals("打包 数据", titleCap.getValue());
    }

    @Test
    void renameRejectsBlankTitle() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "old"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.renameSession(9L, null));
        assertEquals("title 不能为空", e.getMessage());
    }

    @Test
    void renameRejectsOverlongTitle() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "old"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.renameSession(9L, new UpdateAssistantSessionRequest("a".repeat(256))));
        assertEquals("title 长度不能超过 255", e.getMessage());
    }

    @Test
    void renameRejectsWhenUpdateFails() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "old"));
        when(sessionMapper.updateTitle(any(), any(), any(), any())).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.renameSession(9L, new UpdateAssistantSessionRequest("新标题")));
        assertEquals("重命名会话失败", e.getMessage());
    }

    // ---------- delete ----------

    @Test
    void deleteCascadesAndDeletesSelf() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "t"));
        when(sessionMapper.deleteByIdAndUserId(9L, 7L)).thenReturn(1);
        svc.deleteSession(9L);
        verify(ctxMapper).deleteBySessionId(9L);
        verify(messageMapper).deleteBySessionId(9L);
        verify(sessionMapper).deleteByIdAndUserId(9L, 7L);
    }

    @Test
    void deleteRejectsWhenNotOwned() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> svc.deleteSession(9L));
        verify(ctxMapper, never()).deleteBySessionId(any());
    }

    // ---------- autoRename ----------

    @Test
    void autoRenameSkipsInvalidIds() {
        svc.autoRenameSessionIfNeeded(null, 9L, "msg");
        svc.autoRenameSessionIfNeeded(7L, -1L, "msg");
        verify(sessionMapper, never()).selectByIdAndUserId(any(), any());
    }

    @Test
    void autoRenameSkipsWhenTitleAlreadyCustom() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "自定义标题"));
        svc.autoRenameSessionIfNeeded(7L, 9L, "新消息内容");
        verify(sessionMapper, never()).updateTitle(any(), any(), any(), any());
    }

    @Test
    void autoRenameSkipsWhenGeneratedEmpty() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "新会话"));
        svc.autoRenameSessionIfNeeded(7L, 9L, "   ");   // 空白 → 默认标题 → 不更新
        verify(sessionMapper, never()).updateTitle(any(), any(), any(), any());
    }

    @Test
    void autoRenameUsesFirstMessagePrefix() {
        when(sessionMapper.selectByIdAndUserId(9L, 7L)).thenReturn(session(9L, "新会话"));
        when(sessionMapper.updateTitle(any(), any(), any(), any())).thenReturn(1);
        svc.autoRenameSessionIfNeeded(7L, 9L, "  请\n  帮我查报表 数据  ");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateTitle(eq(9L), eq(7L), cap.capture(), any());
        assertEquals("请 帮我查报表 数据", cap.getValue());
    }
}