package com.argus.rag.excel.config;

import com.argus.rag.auth.CurrentUserService.CurrentUser;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelSubscribeInterceptor#assertAuthorized} 文档频道订阅鉴权决策（纯函数，零 STOMP plumbing）：
 * - Excel 频道 /topic/excel/{id} 与 /topic/excel/{id}/users：无权/未认证 → {MessageDeliveryException}；
 * - 非 Excel 目标（私有队列等）一律放行，不进入权限判定；
 * - 有查看权限 → 放行。
 */
@DisplayName("Excel 文档频道订阅权限（ExcelSubscribeInterceptor）")
class ExcelSubscribeInterceptorTest {

    private ExcelPermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = mock(ExcelPermissionService.class);
    }

    private static CurrentUser user(Long id, SystemRole role) {
        return new CurrentUser(id, "U", "U", role, false);
    }

    @Test
    void allowsAuthorizedViewerOnMainChannel() {
        when(permissionService.canView(eq(10L), any())).thenReturn(true);
        assertDoesNotThrow(() -> ExcelSubscribeInterceptor.assertAuthorized(
                "/topic/excel/10", user(7L, SystemRole.USER), permissionService));
    }

    @Test
    void allowsAuthorizedViewerOnUsersChannel() {
        when(permissionService.canView(eq(10L), any())).thenReturn(true);
        assertDoesNotThrow(() -> ExcelSubscribeInterceptor.assertAuthorized(
                "/topic/excel/10/users", user(7L, SystemRole.USER), permissionService));
    }

    @Test
    void rejectsUnAuthorizedUserOnMainChannel() {
        when(permissionService.canView(eq(10L), any())).thenReturn(false);
        MessageDeliveryException ex = assertThrows(MessageDeliveryException.class,
                () -> ExcelSubscribeInterceptor.assertAuthorized(
                        "/topic/excel/10", user(7L, SystemRole.USER), permissionService));
        assertEquals("没有查看该文档的权限", ex.getMessage());
    }

    @Test
    void rejectsUnAuthenticatedUser() {
        // 未认证（user==null）：即便 documentId 合法也拒绝
        MessageDeliveryException ex = assertThrows(MessageDeliveryException.class,
                () -> ExcelSubscribeInterceptor.assertAuthorized(
                        "/topic/excel/10", null, permissionService));
        assertEquals("没有查看该文档的权限", ex.getMessage());
    }

    @Test
    void rejectsWrongDocumentIdPassed() {
        // 校验目标用 10，但判定请求 99（canView(99)=false）→ 拒绝
        when(permissionService.canView(eq(99L), any())).thenReturn(false);
        assertThrows(MessageDeliveryException.class,
                () -> ExcelSubscribeInterceptor.assertAuthorized(
                        "/topic/excel/99", user(7L, SystemRole.USER), permissionService));
    }

    @Test
    void passesThroughNonExcelDestinations() {
        // 非 Excel 目标（用户私有队列、无关主题）一律放行，甚至不做权限判定
        assertDoesNotThrow(() -> ExcelSubscribeInterceptor.assertAuthorized(
                "/user/queue/error", null, null));
        assertDoesNotThrow(() -> ExcelSubscribeInterceptor.assertAuthorized(
                "/topic/other", user(7L, SystemRole.USER), permissionService));
    }

    @Test
    void rejectsMalformedDocumentIdInExcelPath() {
        // documentId 非数字 → 不匹配正则 → 放行（不误伤），合法路径保证数字
        assertDoesNotThrow(() -> ExcelSubscribeInterceptor.assertAuthorized(
                "/topic/excel/abc", user(7L, SystemRole.USER), permissionService));
    }

    @Test
    void nullDestinationPasses() {
        assertDoesNotThrow(() -> ExcelSubscribeInterceptor.assertAuthorized(
                null, user(7L, SystemRole.USER), permissionService));
    }
}