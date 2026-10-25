package com.argus.rag.auth;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.user.service.UserQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CurrentUserService} 当前用户契约（mock {@link UserQueryService}，纯 JVM、零 DB；经静态
 * {@link UserContext} 注入请求级用户，测试后 {@code clear()}）：
 * - getRequiredCurrentUser：无上下文 → 401；有上下文 → 经 {@code userQueryService.findById} 映射全字段；
 *   用户不存在 → AUTH_USER_NOT_FOUND；DISABLED → 403「账号已被禁用」；
 * - requireSystemAdmin：非 ADMIN → 403；ADMIN → 返回；
 * - requireFinance：非 FINANCE → 403；FINANCE → 返回；
 * - requireBusinessUser：ADMIN → 403；业务角色放行；
 * - isFinanceUser（静态）：null 安全 + 仅 FINANCE 为 true。
 * 不触碰财务判定实现——本类即财务角色来源之一，锁的是「角色 enum → 判定」的投影。
 */
@DisplayName("当前用户（CurrentUserService）")
class CurrentUserServiceTest {

    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final CurrentUserService svc = new CurrentUserService(userQueryService);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- helpers ----------

    private static void login(Long userId, SystemRole role) {
        UserContext.set(new AuthenticatedUser(userId, "U" + userId, "会计", role, false));
    }

    private static UserQueryService.UserRecord rec(Long id, SystemRole role, UserStatus status) {
        return new UserQueryService.UserRecord(id, "U" + id, "会计", role, status, false);
    }

    private static UserQueryService.UserRecord activeUser(Long id, SystemRole role) {
        return rec(id, role, UserStatus.ACTIVE);
    }

    // ---------- getRequiredCurrentUser ----------

    @Test
    void getRequiredCurrentUserNoContextThrows401() {
        UnauthorizedException e = assertThrows(UnauthorizedException.class, () -> svc.getRequiredCurrentUser());
        assertEquals("当前请求未登录", e.getMessage());
    }

    @Test
    void getRequiredCurrentUserMapsAllFieldsFromRecord() {
        login(7L, SystemRole.FINANCE);
        when(userQueryService.findById(7L)).thenReturn(activeUser(7L, SystemRole.FINANCE));
        CurrentUserService.CurrentUser u = svc.getRequiredCurrentUser();
        assertEquals(7L, u.userId());
        assertEquals("U7", u.userCode());
        assertEquals("会计", u.displayName());
        assertEquals(SystemRole.FINANCE, u.systemRole());
        assertFalse(u.mustChangePassword());
    }

    @Test
    void getRequiredCurrentUserUserNotFoundThrows() {
        login(9L, SystemRole.USER);
        when(userQueryService.findById(9L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.getRequiredCurrentUser());
        assertEquals(ErrorCodes.AUTH_USER_NOT_FOUND, e.getCode());
    }

    @Test
    void getRequiredCurrentUserDisabledThrows403() {
        login(9L, SystemRole.USER);
        when(userQueryService.findById(9L)).thenReturn(rec(9L, SystemRole.USER, UserStatus.DISABLED));
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> svc.getRequiredCurrentUser());
        assertEquals("账号已被禁用", e.getMessage());
    }

    // ---------- requireSystemAdmin ----------

    @Test
    void requireSystemAdminAllowsAdmin() {
        login(1L, SystemRole.ADMIN);
        when(userQueryService.findById(1L)).thenReturn(activeUser(1L, SystemRole.ADMIN));
        assertEquals(1L, svc.requireSystemAdmin().userId());
    }

    @Test
    void requireSystemAdminRejectsNonAdmin() {
        login(7L, SystemRole.USER);
        when(userQueryService.findById(7L)).thenReturn(activeUser(7L, SystemRole.USER));
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> svc.requireSystemAdmin());
        assertEquals("当前用户不是系统管理员", e.getMessage());
    }

    // ---------- requireFinance / isFinance ----------

    @Test
    void requireFinanceAllowsFinance() {
        login(8L, SystemRole.FINANCE);
        when(userQueryService.findById(8L)).thenReturn(activeUser(8L, SystemRole.FINANCE));
        assertEquals(8L, svc.requireFinance().userId());
    }

    @Test
    void requireFinanceRejectsNonFinance() {
        login(7L, SystemRole.USER);
        when(userQueryService.findById(7L)).thenReturn(activeUser(7L, SystemRole.USER));
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> svc.requireFinance());
        assertEquals("财务数据仅限财务/领导角色访问", e.getMessage());
    }

    // ---------- requireBusinessUser ----------

    @Test
    void requireBusinessUserRejectsAdmin() {
        login(1L, SystemRole.ADMIN);
        when(userQueryService.findById(1L)).thenReturn(activeUser(1L, SystemRole.ADMIN));
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> svc.requireBusinessUser());
        assertEquals("系统管理员不能访问普通业务区", e.getMessage());
    }

    @Test
    void requireBusinessUserAllowsUserAndFinance() {
        login(7L, SystemRole.USER);
        when(userQueryService.findById(7L)).thenReturn(activeUser(7L, SystemRole.USER));
        assertEquals(SystemRole.USER, svc.requireBusinessUser().systemRole());

        login(8L, SystemRole.FINANCE);
        when(userQueryService.findById(8L)).thenReturn(activeUser(8L, SystemRole.FINANCE));
        assertEquals(SystemRole.FINANCE, svc.requireBusinessUser().systemRole());
    }

    // ---------- isFinanceUser（静态，不触碰判定实现） ----------

    @Test
    void isFinanceUserIsNullSafeAndRoleBased() {
        assertFalse(CurrentUserService.isFinanceUser(null));
        assertFalse(CurrentUserService.isFinanceUser(
                new CurrentUserService.CurrentUser(7L, "U7", "普通", SystemRole.USER, false)));
        assertTrue(CurrentUserService.isFinanceUser(
                new CurrentUserService.CurrentUser(8L, "F8", "会计", SystemRole.FINANCE, false)));
        assertFalse(CurrentUserService.isFinanceUser(
                new CurrentUserService.CurrentUser(9L, "A9", "admin", SystemRole.ADMIN, false)));
    }
}