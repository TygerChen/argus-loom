package com.argus.rag.excel.collab;

import com.argus.rag.auth.CurrentUserService.CurrentUser;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.excel.model.dto.CollabMessage;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CollabController} 实时协作权限放行（mock 4 协作方，纯 JVM）：
 * - edit 无文档编辑权（或未认证）→ 拒绝并回 {@code /queue/error} 的 {@code PERMISSION_DENIED}，
 *   不触达 {@link CollabService} 持久化；
 * - edit 有权限 → 委托 {@link CollabService} 并广播 {@code /topic/excel/{documentId}}；
 * - join 无查看权 → 拒绝，不登记在线名单、不建房间；
 * - join 有查看权 → 登记名单并建房间，把真实 {@code canEdit} 透传给 {@link CollabService.joinRoom}，不再硬编码。
 */
@DisplayName("实时协作权限放行（CollabController）")
class CollabControllerTest {

    private SimpMessagingTemplate messagingTemplate;
    private OnlineUserTracker userTracker;
    private CollabService collabService;
    private ExcelPermissionService permissionService;
    private CollabController controller;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        userTracker = mock(OnlineUserTracker.class);
        collabService = mock(CollabService.class);
        permissionService = mock(ExcelPermissionService.class);
        controller = new CollabController(messagingTemplate, userTracker, collabService, permissionService);
    }

    private static SimpMessageHeaderAccessor accessor(Long userId, String displayName,
                                                      SystemRole role, String sessionId) {
        SimpMessageHeaderAccessor a = SimpMessageHeaderAccessor.create();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompSessionAuth.USER_ID, userId);
        attrs.put(StompSessionAuth.DISPLAY_NAME, displayName);
        attrs.put(StompSessionAuth.SYSTEM_ROLE, role.name());
        a.setSessionAttributes(attrs);
        a.setSessionId(sessionId);
        return a;
    }

    private static CollabMessage editMsg(Long documentId) {
        Map<String, Object> v = new HashMap<>();
        v.put("v", "x");
        return CollabMessage.fromClient("CELL_EDIT", documentId, 20L, 1, 2, v, 7L, "U7");
    }

    // ---------- edit 权限 ---------------------------------------------------------------------

    @Test
    void editWithoutEditPermissionSendsPermissionDeniedAndSkipsPersistence() {
        when(permissionService.canEdit(anyLong(), any(CurrentUser.class))).thenReturn(false);
        controller.handleEdit(editMsg(10L), accessor(7L, "U7", SystemRole.USER, "S1"));

        verify(collabService, never()).handleEdit(any(CollabMessage.class), anyLong(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("S1"), eq("/queue/error"), cap.capture());
        Map<String, Object> err = cap.getValue();
        assertEquals("PERMISSION_DENIED", err.get("type"));
        assertEquals("没有编辑该文档的权限", err.get("message"));
    }

    @Test
    void editForUnauthenticatedSessionIsIgnored() {
        // 未认证：session 无 userId → 直接忽略，不触达业务层、不回执
        controller.handleEdit(editMsg(10L), SimpMessageHeaderAccessor.create());
        verify(collabService, never()).handleEdit(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void editWithEditPermissionDelegatesAndBroadcasts() {
        when(permissionService.canEdit(anyLong(), any(CurrentUser.class))).thenReturn(true);
        CollabService.HandleEditOutcome outcome =
                new CollabService.HandleEditOutcome(true, null, null, null, Map.of("type", "CELL_EDIT"), 0L);
        when(collabService.handleEdit(any(CollabMessage.class), anyLong(), anyString(), anyString()))
                .thenReturn(outcome);

        CollabMessage m = editMsg(10L);   // 复用同一实例：CollabMessage 时间戳随即构造，equals 需同实例
        controller.handleEdit(m, accessor(7L, "U7", SystemRole.USER, "S1"));

        verify(collabService).handleEdit(eq(m), eq(7L), eq("U7"), eq("S1"));
        verify(messagingTemplate).convertAndSend(eq("/topic/excel/10"), any(Map.class));
    }

    // ---------- join 权限 ------------------------------------------------------------------

    @Test
    void joinWithoutViewPermissionDeniedAndSkipsRoom() {
        when(permissionService.canView(anyLong(), any(CurrentUser.class))).thenReturn(false);
        controller.handleJoin(10L, accessor(7L, "U7", SystemRole.USER, "S1"));

        verify(userTracker, never()).userJoin(anyLong(), anyLong(), anyString(), anyString());
        verify(collabService, never()).joinRoom(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
        // 严重：即使无权限也要回执，避免前端静默失败
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("S1"), eq("/queue/error"), cap.capture());
        assertEquals("PERMISSION_DENIED", cap.getValue().get("type"));
    }

    @Test
    void joinWithViewPermissionRegistersRoomWithEffectiveCanEdit() {
        when(permissionService.canView(anyLong(), any(CurrentUser.class))).thenReturn(true);
        when(permissionService.canEdit(anyLong(), any(CurrentUser.class))).thenReturn(false); // 只读查看者：房间应记为不可编辑

        controller.handleJoin(10L, accessor(7L, "U7", SystemRole.USER, "S1"));

        verify(userTracker).userJoin(10L, 7L, "U7", "S1");
        verify(collabService).joinRoom(10L, 7L, "U7", "S1", false);
    }

    @Test
    void joinAsEditorRegistersEditableRoom() {
        when(permissionService.canView(anyLong(), any(CurrentUser.class))).thenReturn(true);
        when(permissionService.canEdit(anyLong(), any(CurrentUser.class))).thenReturn(true);
        controller.handleJoin(10L, accessor(7L, "U7", SystemRole.USER, "S1"));
        verify(collabService).joinRoom(10L, 7L, "U7", "S1", true);
    }

    @Test
    void leaveAlwaysDeregistersEvenIfNoView() {
        // leave 仅是移除自身占位，不做权限判断（无读取副作用）
        controller.handleLeave(10L, accessor(7L, "U7", SystemRole.USER, "S1"));
        verify(userTracker).userLeave(10L, 7L);
        verify(collabService).leaveRoom("S1");
    }

    @Test
    void leaveForUnauthenticatedSessionIsIgnored() {
        controller.handleLeave(10L, SimpMessageHeaderAccessor.create());
        verify(userTracker, never()).userLeave(anyLong(), anyLong());
    }
}
