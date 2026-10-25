package com.argus.rag.excel.collab;

import com.argus.rag.auth.CurrentUserService.CurrentUser;
import com.argus.rag.excel.model.dto.CollabMessage;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * 实时协作控制器（适配层）。
 * <p>仅负责 STOMP 参数解析、文档级权限放行、业务委托与消息广播：LWW 冲突解决、持久化、日志与房间维护
 * 全部下沉到 {@link CollabService}。edit 前需文档编辑权，join/leave 前需文档查看权；
 * 在线用户状态由 {@link OnlineUserTracker} 独立管理。</p>
 */
@Controller
public class CollabController {

    private static final Logger log = LoggerFactory.getLogger(CollabController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final OnlineUserTracker userTracker;
    private final CollabService collabService;
    private final ExcelPermissionService permissionService;

    public CollabController(SimpMessagingTemplate messagingTemplate,
                            OnlineUserTracker userTracker,
                            CollabService collabService,
                            ExcelPermissionService permissionService) {
        this.messagingTemplate = messagingTemplate;
        this.userTracker = userTracker;
        this.collabService = collabService;
        this.permissionService = permissionService;
    }

    /**
     * 接收单元格编辑事件 → 文档权限校验 → 委托业务层做 LWW/持久化 → 按结果广播或向发送者回错。
     */
    @MessageMapping("/excel/edit")
    public void handleEdit(@Payload CollabMessage msg,
                           SimpMessageHeaderAccessor headerAccessor) {
        Long userId = StompSessionAuth.userId(headerAccessor);
        if (userId == null) {
            log.warn("编辑消息缺少用户信息，忽略");
            return;
        }
        String sessionId = headerAccessor.getSessionId();
        // 文档级权限：必须用已认证会话用户（而非消息负载里的 userId）判断，未授权直接拒绝。
        CurrentUser user = StompSessionAuth.currentUser(headerAccessor);
        if (user == null || msg.documentId() == null || !permissionService.canEdit(msg.documentId(), user)) {
            sendError(sessionId, "PERMISSION_DENIED", null, "没有编辑该文档的权限", 0L);
            return;
        }
        String displayName = StompSessionAuth.displayName(headerAccessor);

        CollabService.HandleEditOutcome outcome =
                collabService.handleEdit(msg, userId, displayName, sessionId);

        if (!outcome.accepted()) {
            // LWW 冲突或保存失败：仅回执发送者。
            sendError(sessionId, outcome.rejectionReason(), outcome.cellKey(), outcome.message(),
                    outcome.serverRevision());
            return;
        }
        // 编辑被接受并已成功持久化：广播到所有在线用户（内容供客户端去重）。
        if (outcome.broadcast() != null) {
            messagingTemplate.convertAndSend("/topic/excel/" + msg.documentId(), outcome.broadcast());
        }
    }

    /**
     * 用户加入文档：校验查看权限后登记房间与在线名单。
     */
    @MessageMapping("/excel/join/{documentId}")
    public void handleJoin(@DestinationVariable Long documentId,
                           SimpMessageHeaderAccessor headerAccessor) {
        Long userId = StompSessionAuth.userId(headerAccessor);
        if (userId == null) {
            return;
        }
        String sessionId = headerAccessor.getSessionId();
        CurrentUser user = StompSessionAuth.currentUser(headerAccessor);
        if (user == null || documentId == null || !permissionService.canView(documentId, user)) {
            sendError(sessionId, "PERMISSION_DENIED", null, "没有查看该文档的权限", 0L);
            return;
        }
        String displayName = StompSessionAuth.displayName(headerAccessor);
        userTracker.userJoin(documentId, userId, displayName, sessionId);
        collabService.joinRoom(documentId, userId, displayName, sessionId,
                permissionService.canEdit(documentId, user));
    }

    /**
     * 用户离开文档。
     */
    @MessageMapping("/excel/leave/{documentId}")
    public void handleLeave(@DestinationVariable("documentId") Long documentId,
                            SimpMessageHeaderAccessor headerAccessor) {
        Long userId = StompSessionAuth.userId(headerAccessor);
        if (userId == null) {
            return;
        }
        userTracker.userLeave(documentId, userId);
        collabService.leaveRoom(headerAccessor.getSessionId());
    }

    /** 向发送者回执错误（复用 {@code /queue/error} 协议；cellKey 可为 null，故用可空 map）。 */
    private void sendError(String sessionId, String type, String cellKey, String message, long serverRevision) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("cellKey", cellKey);
        payload.put("message", message);
        payload.put("serverRevision", serverRevision);
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/error", payload);
    }
}
