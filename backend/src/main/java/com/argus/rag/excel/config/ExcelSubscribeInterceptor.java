package com.argus.rag.excel.config;

import com.argus.rag.auth.CurrentUserService.CurrentUser;
import com.argus.rag.excel.collab.StompSessionAuth;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Excel 文档频道订阅权限拦截器。
 * <p>对 STOMP SUBSCRIBE 帧做文档级查看权限校验：目标为
 * {@code /topic/excel/{documentId}} 或 {@code /topic/excel/{documentId}/users} 时，
 * 无查看权限的用户订阅被拒绝（抛 {@link MessageDeliveryException}，由 broker 转成 STOMP ERROR 帧）。
 * 非 Excel 频道（如用户私有队列）不受影响。</p>
 * <p>权限判定沉淀在 {@link #assertAuthorized}，便于单元测试拒绝/放行两条路径。</p>
 */
public class ExcelSubscribeInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ExcelSubscribeInterceptor.class);

    /** Excel 文档频道：/topic/excel/{documentId} 与 /topic/excel/{documentId}/users。第 1 分组为文档 ID。 */
    private static final Pattern EXCEL_TOPIC_PATTERN = Pattern.compile("^/topic/excel/(\\d+)(/users)?$");

    private final ExcelPermissionService permissionService;

    public ExcelSubscribeInterceptor(ExcelPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 订阅鉴权决策：目标为 Excel 文档频道且无权查看（或未认证）时抛 {@link MessageDeliveryException}，
     * 否则直接返回（放行）。非 Excel 目标不做校验。
     */
    public static void assertAuthorized(String destination, CurrentUser user,
                                        ExcelPermissionService permissionService) {
        Matcher matcher = EXCEL_TOPIC_PATTERN.matcher(destination == null ? "" : destination);
        if (!matcher.matches()) {
            return; // 非 Excel 文档频道，交由 broker 默认处理
        }
        Long documentId = Long.parseLong(matcher.group(1));
        if (user == null || permissionService == null
                || !permissionService.canView(documentId, user)) {
            throw new MessageDeliveryException("没有查看该文档的权限");
        }
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        var user = StompSessionAuth.currentUser(accessor);
        try {
            assertAuthorized(destination, user, permissionService);
        } catch (MessageDeliveryException e) {
            log.warn("拒绝无权限订阅 Excel 频道: dest={}, user={}",
                    destination, user == null ? null : user.userId());
            throw e;
        }
        return message;
    }
}