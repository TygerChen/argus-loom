package com.argus.rag.excel.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 在线用户追踪服务。
 * <p>
 * 按文档追踪当前在线的编辑用户。连接断开时自动移除并广播更新后的用户列表。
 * 使用 ConcurrentHashMap 保证线程安全。
 */
@Component
public class OnlineUserTracker {

    private static final Logger log = LoggerFactory.getLogger(OnlineUserTracker.class);

    /** 在线用户登记表：documentId → (userId → 该用户的在线信息) */
    private final ConcurrentHashMap<Long, Map<Long, UserInfo>> onlineUsers = new ConcurrentHashMap<>();

    /** sessionId → (documentId, userId) — 用于断线时反向查找 */
    private final ConcurrentHashMap<String, Map.Entry<Long, Long>> sessionToDocUser = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;

    public OnlineUserTracker(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 用户加入文档。
     *
     * @param documentId 文档 ID
     * @param userId     用户 ID
     * @param displayName 显示名
     * @param sessionId   STOMP session ID（用于断线时反向查找）
     */
    public void userJoin(Long documentId, Long userId, String displayName, String sessionId) {
        onlineUsers.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>())
                .put(userId, new UserInfo(userId, displayName, Instant.now()));
        sessionToDocUser.put(sessionId, new AbstractMap.SimpleEntry<>(documentId, userId));
        log.info("用户加入文档: documentId={}, userId={}, displayName={}", documentId, userId, displayName);
        broadcastUserList(documentId);
    }

    /**
     * 用户离开文档。
     */
    public void userLeave(Long documentId, Long userId) {
        Map<Long, UserInfo> users = onlineUsers.get(documentId);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) {
                onlineUsers.remove(documentId);
            }
        }
        log.info("用户离开文档: documentId={}, userId={}", documentId, userId);
        broadcastUserList(documentId);
    }

    /**
     * 获取文档的在线用户列表。
     */
    public List<UserInfo> getOnlineUsers(Long documentId) {
        Map<Long, UserInfo> users = onlineUsers.get(documentId);
        return users == null ? Collections.emptyList() : List.copyOf(users.values());
    }

    /**
     * STOMP 断线事件——用户关闭页面或网络断开。
     */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Map.Entry<Long, Long> entry = sessionToDocUser.remove(sessionId);
        if (entry != null) {
            userLeave(entry.getKey(), entry.getValue());
        }
    }

    private void broadcastUserList(Long documentId) {
        List<UserInfo> users = getOnlineUsers(documentId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "USER_LIST");
        payload.put("documentId", documentId);
        payload.put("users", users);
        payload.put("count", users.size());
        messagingTemplate.convertAndSend("/topic/excel/" + documentId + "/users", payload);
    }

    /**
     * 在线用户信息。
     */
    public record UserInfo(Long userId, String displayName, Instant joinedAt) {}
}