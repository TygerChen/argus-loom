package com.argus.rag.user.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.auth.security.RefreshTokenService;
import com.argus.rag.auth.service.PasswordHasher;
import com.argus.rag.auth.service.PasswordPolicyValidator;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.user.mapper.UserMapper;
import com.argus.rag.user.model.dto.ChangePasswordRequest;
import com.argus.rag.user.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AccountService} 个人账户修改密码契约（mock 4 协作方：userMapper/密码哈希/密码策略/refreshToken，纯 JVM/零 DB）：
 * - 新密码不满足密码策略→策略异常透传、不查库；
 * - 用户缺失 / 密码哈希为空 → USER_NOT_FOUND；
 * - 当前密码不匹配 → USER_PASSWORD_MISMATCH；
 * - 新旧密码相同 → USER_PASSWORD_SAME_AS_OLD；
 * - 更新影响 0 行 → USER_NOT_FOUND 且不吊销 token；
 * - 成功 → 落新哈希 + mustChangePassword=false + updateById + 吊销该用户全部 active refresh token。
 */
@DisplayName("个人账户（AccountService）")
class AccountServiceTest {

    private UserMapper userMapper;
    private PasswordHasher passwordHasher;
    private PasswordPolicyValidator passwordValidator;
    private RefreshTokenService refreshTokenService;
    private AccountService svc;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordHasher = mock(PasswordHasher.class);
        passwordValidator = mock(PasswordPolicyValidator.class);
        refreshTokenService = mock(RefreshTokenService.class);
        svc = new AccountService(userMapper, passwordHasher, passwordValidator, refreshTokenService);
    }

    private static CurrentUserService.CurrentUser user() {
        return new CurrentUserService.CurrentUser(7L, "u7", "显示名", SystemRole.USER, true);
    }

    private static ChangePasswordRequest req(String cur, String next) {
        return new ChangePasswordRequest(cur, next);
    }

    private static User storedUser(String hash) {
        User u = new User();
        u.setId(7L);
        u.setUserCode("u7");
        u.setPasswordHash(hash);
        u.setMustChangePassword(true);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    @Test
    void changePasswordRejectsNewPasswordAgainstPolicy() {
        // 策略校验抛的是一般 BusinessException（无码），由服务直接透传不吞不改
        doThrow(new BusinessException("密码不符合策略")).when(passwordValidator).validate("weak");
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.changePassword(user(), req("old", "weak")));
        assertEquals("密码不符合策略", e.getMessage());
        verify(userMapper, never()).selectById(any());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changePasswordRejectsMissingUser() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.changePassword(user(), req("cur", "new")));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
    }

    @Test
    void changePasswordRejectsNullStoredHash() {
        when(userMapper.selectById(7L)).thenReturn(storedUser(null));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.changePassword(user(), req("cur", "new")));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
    }

    @Test
    void changePasswordRejectsCurrentPasswordMismatch() {
        when(userMapper.selectById(7L)).thenReturn(storedUser("stored"));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.changePassword(user(), req("wrong", "newPW1a")));
        assertEquals(ErrorCodes.USER_PASSWORD_MISMATCH, e.getCode());
        assertEquals("当前密码不正确", e.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changePasswordRejectsSameAsOld() {
        when(userMapper.selectById(7L)).thenReturn(storedUser("stored"));
        when(passwordHasher.matches("cur", "stored")).thenReturn(true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.changePassword(user(), req("cur", "cur")));
        assertEquals(ErrorCodes.USER_PASSWORD_SAME_AS_OLD, e.getCode());
        assertEquals("新密码不能与当前密码相同", e.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changePasswordRejectsZeroRowsUpdated() {
        when(userMapper.selectById(7L)).thenReturn(storedUser("stored"));
        when(passwordHasher.matches("cur", "stored")).thenReturn(true);
        when(passwordHasher.hash("newPW1a")).thenReturn("H:new");
        when(userMapper.updateById(any(User.class))).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.changePassword(user(), req("cur", "newPW1a")));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
        verify(refreshTokenService, never()).revokeActiveTokens(any());
    }

    @Test
    void changePasswordSuccessHashesAndRevokesTokens() {
        when(userMapper.selectById(7L)).thenReturn(storedUser("stored"));
        when(passwordHasher.matches("cur", "stored")).thenReturn(true);
        when(passwordHasher.hash("newPW1a")).thenReturn("H:new");
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        svc.changePassword(user(), req("cur", "newPW1a"));
        verify(passwordValidator).validate("newPW1a");
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(cap.capture());
        User updated = cap.getValue();
        assertEquals("H:new", updated.getPasswordHash());
        assertFalse(updated.getMustChangePassword());
        verify(refreshTokenService).revokeActiveTokens(7L);
    }
}