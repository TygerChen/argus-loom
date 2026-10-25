package com.argus.rag.user.service;

import com.argus.rag.auth.security.RefreshTokenService;
import com.argus.rag.auth.service.PasswordHasher;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.user.mapper.UserMapper;
import com.argus.rag.user.model.dto.ResetUserPasswordRequest;
import com.argus.rag.user.model.dto.UpdateUserStatusRequest;
import com.argus.rag.user.model.entity.User;
import com.argus.rag.user.model.vo.AdminUserItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminUserService} 管理员用户管理契约（mock 4 协作方：userMapper/refreshTokenService/userQueryService/
 * passwordHasher，纯 JVM/零 DB）：
 * - requireUserId：null/<=0 → BusinessException("用户ID非法")，所有入口生效；
 * - getUser/listUsers 纯委托 userQueryService；updateUserStatus：用户缺失→USER_NOT_FOUND、
 *   置状态 updateById、DISABLED 时吊销 active token（ACTIVE 不吊销）；
 * - updateUserRole：null 角色→"系统角色不能为空"、用户缺失→USER_NOT_FOUND、同角色→USER_ROLE_ALREADY_SET、
 *   成功置角色 + updateById + 强制吊销 token 重新登录；
 * - resetUserPassword：空/空白新密码→"新密码不能为空"、用户缺失→USER_NOT_FOUND、
 *   成功→落新哈希 + mustChangePassword=true + updateById + 吊销 token。
 */
@DisplayName("管理员用户管理（AdminUserService）")
class AdminUserServiceTest {

    private UserMapper userMapper;
    private RefreshTokenService refreshTokenService;
    private UserQueryService userQueryService;
    private PasswordHasher passwordHasher;
    private AdminUserService svc;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        refreshTokenService = mock(RefreshTokenService.class);
        userQueryService = mock(UserQueryService.class);
        passwordHasher = mock(PasswordHasher.class);
        svc = new AdminUserService(userMapper, refreshTokenService, userQueryService, passwordHasher,
                new com.argus.rag.auth.service.PasswordPolicyValidator());
    }

    private static User user(Long id, SystemRole role, String hash) {
        User u = new User();
        u.setId(id);
        u.setUserCode("u" + id);
        u.setUsername("u" + id);
        u.setPasswordHash(hash);
        u.setSystemRole(role);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    // ---------- 委托 ----------

    @Test
    void listUsersDelegatesToUserQueryService() {
        when(userQueryService.listUsers()).thenReturn(List.of());
        svc.listUsers();
        verify(userQueryService).listUsers();
    }

    @Test
    void getUserDelegatesAfterValidation() {
        when(userQueryService.getUser(7L)).thenReturn(new AdminUserItemResponse(
                7L, "u7", "u7", "e", "d", SystemRole.USER, UserStatus.ACTIVE, false, null));
        AdminUserItemResponse vo = svc.getUser(7L);
        assertEquals(7L, vo.userId());
        verify(userQueryService).getUser(7L);
    }

    @Test
    void invalidUserIdRejectedEverywhere() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.getUser(0L));
        assertEquals("用户ID非法", e.getMessage());
        assertThrows(BusinessException.class,
                () -> svc.updateUserStatus(null, new UpdateUserStatusRequest(UserStatus.DISABLED)));
        assertThrows(BusinessException.class,
                () -> svc.updateUserRole(-1L, SystemRole.ADMIN));
        assertThrows(BusinessException.class,
                () -> svc.resetUserPassword(0L, new ResetUserPasswordRequest("x")));
        verify(userMapper, never()).selectById(any());
    }

    // ---------- updateUserStatus ----------

    @Test
    void updateUserStatusRejectsMissingUser() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.updateUserStatus(7L, new UpdateUserStatusRequest(UserStatus.DISABLED)));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
    }

    @Test
    void updateUserStatusToDisabledRevokesTokens() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, SystemRole.USER, "h"));
        svc.updateUserStatus(7L, new UpdateUserStatusRequest(UserStatus.DISABLED));
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(cap.capture());
        assertEquals(UserStatus.DISABLED, cap.getValue().getStatus());
        verify(refreshTokenService).revokeActiveTokens(7L);
    }

    @Test
    void updateUserStatusToActiveDoesNotRevoke() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, SystemRole.USER, "h"));
        svc.updateUserStatus(7L, new UpdateUserStatusRequest(UserStatus.ACTIVE));
        verify(userMapper).updateById(any(User.class));
        verify(refreshTokenService, never()).revokeActiveTokens(any());
    }

    // ---------- updateUserRole ----------

    @Test
    void updateUserRoleRejectsNullRole() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.updateUserRole(7L, null));
        assertEquals("系统角色不能为空", e.getMessage());
    }

    @Test
    void updateUserRoleRejectsMissingUser() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.updateUserRole(7L, SystemRole.ADMIN));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
    }

    @Test
    void updateUserRoleRejectsSameRole() {
        User u = user(7L, SystemRole.USER, "h");
        u.setSystemRole(SystemRole.ADMIN);
        when(userMapper.selectById(7L)).thenReturn(u);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.updateUserRole(7L, SystemRole.ADMIN));
        assertEquals(ErrorCodes.USER_ROLE_ALREADY_SET, e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void updateUserRoleChangesAndRevokesTokens() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, SystemRole.USER, "h"));
        svc.updateUserRole(7L, SystemRole.ADMIN);
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(cap.capture());
        assertEquals(SystemRole.ADMIN, cap.getValue().getSystemRole());
        verify(refreshTokenService).revokeActiveTokens(7L);
    }

    // ---------- resetUserPassword ----------

    @Test
    void resetUserPasswordRejectsBlankOrNull() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.resetUserPassword(7L, new ResetUserPasswordRequest("  ")));
        assertEquals("新密码不能为空", e.getMessage());
        assertThrows(BusinessException.class, () -> svc.resetUserPassword(7L, null));
    }

    @Test
    void resetUserPasswordEnforcesPasswordPolicy() {
        // 管理员重置也必须过密码策略：曾绕过校验，重置出 "abc" 这类弱密码（对抗性审查发现）
        when(userMapper.selectById(7L)).thenReturn(user(7L, SystemRole.USER, "old"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.resetUserPassword(7L, new ResetUserPasswordRequest("abc")));
        assertEquals("新密码必须至少 8 位，且同时包含字母和数字", e.getMessage());
        verify(userMapper, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void resetUserPasswordRejectsMissingUser() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.resetUserPassword(7L, new ResetUserPasswordRequest("pw123456")));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
    }

    @Test
    void resetUserPasswordSetsHashAndForcesChange() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, SystemRole.USER, "old"));
        when(passwordHasher.hash("pw123456")).thenReturn("H:new");
        svc.resetUserPassword(7L, new ResetUserPasswordRequest("pw123456"));
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(cap.capture());
        assertEquals("H:new", cap.getValue().getPasswordHash());
        assertTrue(cap.getValue().getMustChangePassword());
        verify(refreshTokenService).revokeActiveTokens(7L);
    }
}