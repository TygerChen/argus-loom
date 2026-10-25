package com.argus.rag.excel.collab;

import com.argus.rag.auth.CurrentUserService.CurrentUser;
import com.argus.rag.common.enums.SystemRole;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

/**
 * STOMP 会话鉴权辅助：集中管理 session attributes 的认证键与把会话还原为
 * {@link CurrentUser}。WebSocket 消息处理线程没有 SecurityContext（JWT 已在
 * CONNECT 拦截器解析进 session attributes），协作/订阅权限必须先还原出用户。
 */
public final class StompSessionAuth {

    public static final String USER_ID = "userId";
    public static final String DISPLAY_NAME = "displayName";
    public static final String SYSTEM_ROLE = "systemRole";

    private StompSessionAuth() {
    }

    /** 会话中的已认证用户 ID；未认证或未登录返回 {@code null}。 */
    public static Long userId(SimpMessageHeaderAccessor accessor) {
        if (accessor == null || accessor.getSessionAttributes() == null) {
            return null;
        }
        Object val = accessor.getSessionAttributes().get(USER_ID);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 会话中的显示名；缺省返回“未知用户”。 */
    public static String displayName(SimpMessageHeaderAccessor accessor) {
        if (accessor == null || accessor.getSessionAttributes() == null) {
            return "未知用户";
        }
        Object val = accessor.getSessionAttributes().get(DISPLAY_NAME);
        return val != null ? val.toString() : "未知用户";
    }

    /** 会话中的系统角色；缺失或非法时回退 {@link SystemRole#USER}。 */
    public static SystemRole systemRole(SimpMessageHeaderAccessor accessor) {
        if (accessor == null || accessor.getSessionAttributes() == null) {
            return SystemRole.USER;
        }
        Object val = accessor.getSessionAttributes().get(SYSTEM_ROLE);
        if (val instanceof SystemRole role) {
            return role;
        }
        if (val instanceof String s) {
            try {
                return SystemRole.valueOf(s);
            } catch (IllegalArgumentException ignored) {
                return SystemRole.USER;
            }
        }
        return SystemRole.USER;
    }

    /**
     * 还原当前已认证用户；未认证返回 {@code null}。userCode 未知（REST 由 DB 加载，
     * WS 认证时未冗余），权限判定仅依赖 userId/systemRole/displayName，故可置空。
     */
    public static CurrentUser currentUser(SimpMessageHeaderAccessor accessor) {
        Long userId = userId(accessor);
        if (userId == null) {
            return null;
        }
        return new CurrentUser(userId, null, displayName(accessor), systemRole(accessor), false);
    }
}