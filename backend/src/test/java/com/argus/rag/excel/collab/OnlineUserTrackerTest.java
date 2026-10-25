package com.argus.rag.excel.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OnlineUserTracker} 在线用户追踪契约（纯内存 + mock {@link SimpMessagingTemplate}。
 * 零 DB/零真实 WebSocket）：join 加入、leave 离开且末位清 key、断线反向移除、
 * 每次变更向 `/topic/excel/{id}/users` 广播 USER_LIST 载荷（含 users 与 count）。
 */
@DisplayName("在线用户追踪（OnlineUserTracker）")
class OnlineUserTrackerTest {

    private SimpMessagingTemplate messaging;
    private OnlineUserTracker tracker;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        tracker = new OnlineUserTracker(messaging);
    }

    @Test
    void joinAddsUserAndBroadcasts() {
        tracker.userJoin(10L, 1L, "小明", "sess-1");
        assertEquals(1, tracker.getOnlineUsers(10L).size());
        verify(messaging).convertAndSend(eq("/topic/excel/10/users"), any(Map.class));
    }

    @Test
    void joinStoresDisplayNameAndUserId() {
        tracker.userJoin(10L, 7L, "小红", "s");
        OnlineUserTracker.UserInfo user = tracker.getOnlineUsers(10L).get(0);
        assertEquals(7L, user.userId());
        assertEquals("小红", user.displayName());
    }

    @Test
    void getOnlineUsersForUnknownDocEmpty() {
        assertTrue(tracker.getOnlineUsers(999L).isEmpty());
    }

    @Test
    void leaveRemovesUserAndClearsPrivatelyWhenLast() {
        tracker.userJoin(10L, 1L, "a", "s1");
        tracker.userJoin(10L, 2L, "b", "s2");
        tracker.userLeave(10L, 1L);
        assertEquals(1, tracker.getOnlineUsers(10L).size());
        tracker.userLeave(10L, 2L);
        // 最后一个离开 → 文档键移除
        assertTrue(tracker.getOnlineUsers(10L).isEmpty());
    }

    @Test
    void disconnectEventRemovesUsersAndClearsWhenLast() {
        tracker.userJoin(10L, 1L, "a", "sess-leave");
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("sess-leave");
        tracker.onSessionDisconnect(event);
        assertTrue(tracker.getOnlineUsers(10L).isEmpty());
        // join 广播一次、断开广播一次；末条载荷应反映文档已清空
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/excel/10/users"), payloadCaptor.capture());
        List<Map> all = payloadCaptor.getAllValues();
        assertEquals(0, ((Number) all.get(all.size() - 1).get("count")).intValue());
    }

    @Test
    void disconnectKeepsOtherUsers() {
        tracker.userJoin(10L, 1L, "a", "s-a");
        tracker.userJoin(10L, 2L, "b", "s-b");
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("s-a");
        tracker.onSessionDisconnect(event);
        assertEquals(1, tracker.getOnlineUsers(10L).size());
        assertEquals(2L, tracker.getOnlineUsers(10L).get(0).userId());
    }

    @Test
    void disconnectWithoutKnownSessionIsNoOp() {
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("no-such-session");
        tracker.onSessionDisconnect(event);
        verify(messaging, never()).convertAndSend(any(), any(Map.class));
    }

    @Test
    void broadcastPayloadCarriesCountAndType() {
        tracker.userJoin(10L, 1L, "a", "s1");
        tracker.userJoin(10L, 2L, "b", "s2");

        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/excel/10/users"), payloadCaptor.capture());
        Map payload = payloadCaptor.getAllValues().get(payloadCaptor.getAllValues().size() - 1);
        assertEquals("USER_LIST", payload.get("type"));
        assertEquals(10L, ((Number) payload.get("documentId")).longValue());
        assertEquals(2, ((Number) payload.get("count")).intValue());
    }
}