package com.argus.rag.auth.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.auth.model.dto.RegisterRequest;
import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.auth.security.RefreshTokenService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.user.mapper.UserMapper;
import com.argus.rag.user.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 认证契约（mock 5 协作方，注入固定 Clock，纯 JVM/零 DB）：
 * - login：null/空白/超长登录标识、空白/超长/超 72 字节密码等校验拒绝；禁用账号→401；密码不匹配/用户缺失→
 *   AUTH_CREDENTIALS_INVALID；登录标识冲突→BusinessException。成功登录支路因 updateSuccessfulLogin 构造
 *   LambdaUpdateWrapper 触发 MP 运行时反射（同 FinancePdfRenderer 先例）不做单测；
 * - refresh：空 refresh→AUTH_TOKEN_INVALID、查无→AUTH_TOKEN_INVALID、用户缺失→AUTH_USER_NOT_FOUND、
 *   禁用→401、revokeById 返回 false→AUTH_TOKEN_REPLAYED（并发重放）、成功→新令牌集；
 * - register：请求空/用户名邮箱显示名非法（长度/非法字符/保留字）、已占用→AUTH_IDENTITY_EXISTS、成功→
 *   默认 USER+ACTIVE+mustChange=false+密码哈希后落库；
 * - logout：空入参短路，否则委派 revokeToken。
 */
@DisplayName("认证（AuthService）")
class AuthServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 10, 0, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private UserMapper userMapper;
    private PasswordHasher passwordHasher;
    private PasswordPolicyValidator passwordValidator;
    private JwtAccessTokenService jwt;
    private RefreshTokenService refresh;
    private AuthService svc;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordHasher = mock(PasswordHasher.class);
        passwordValidator = mock(PasswordPolicyValidator.class);
        jwt = mock(JwtAccessTokenService.class);
        refresh = mock(RefreshTokenService.class);
        svc = new AuthService(userMapper, passwordHasher, passwordValidator, jwt, refresh, CLOCK);
    }

    private static User activeUser(Long id, String name, String email, String passwordHash) {
        User u = new User();
        u.setId(id);
        u.setUserCode(name);
        u.setUsername(name);
        u.setEmail(email);
        u.setDisplayName("会计 " + id);
        u.setPasswordHash(passwordHash);
        u.setSystemRole(SystemRole.USER);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    private static RegisterRequest req(String username, String email, String displayName, String password) {
        return new RegisterRequest(username, email, displayName, password);
    }

    // ---------- login 校验拒绝分支 ----------

    @Test
    void loginRejectsBlankLoginId() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.login("   ", "pw"));
        assertEquals("登录标识不能为空", e.getMessage());
    }

    @Test
    void loginRejectsOverlongLoginId() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.login("a".repeat(129), "pw"));
        assertEquals("登录标识长度非法", e.getMessage());
    }

    @Test
    void loginRejectsBlankPassword() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.login("u1", "   "));
        assertEquals("密码不能为空", e.getMessage());
    }

    @Test
    void loginRejectsOverlongPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.login("u1", "a".repeat(300)));
        assertEquals("密码长度非法", e.getMessage());
    }

    @Test
    void loginRejectsPasswordOver72Bytes() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.login("u1", "a".repeat(73)));
        assertEquals("密码长度超过安全上限，请控制在 72 字节以内", e.getMessage());
    }

    @Test
    void loginRejectsUnknownLoginId() {
        when(userMapper.selectByLoginIdForUpdate(any())).thenReturn(List.of());
        BusinessException e = assertThrows(BusinessException.class, () -> svc.login("u1", "pw"));
        assertEquals(ErrorCodes.AUTH_CREDENTIALS_INVALID, e.getCode());
    }

    @Test
    void loginRejectsConflictingLoginId() {
        when(userMapper.selectByLoginIdForUpdate(any())).thenReturn(
                List.of(activeUser(1L, "a", "a@x", "h"), activeUser(2L, "a", "b@x", "h")));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.login("a", "pw"));
        assertEquals("登录标识存在冲突，请联系管理员处理", e.getMessage());
    }

    @Test
    void loginDoesNotLeakDisabledStatusWhenPasswordWrong() {
        // 防枚举契约：禁用账号 + 错误密码必须返回与普通密码错误完全一致的信息，
        // 否则攻击者可用任意密码探测"该账号存在且被禁用"（曾为先查禁用后验密码，对抗性审查发现）
        User user = activeUser(7L, "u7", "u7@x", "h");
        user.setStatus(UserStatus.DISABLED);
        when(userMapper.selectByLoginIdForUpdate(any())).thenReturn(List.of(user));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.login("u7", "whatever"));
        assertEquals(ErrorCodes.AUTH_CREDENTIALS_INVALID, e.getCode());
        assertEquals("账号或密码错误", e.getMessage());
    }

    @Test
    void loginRejectsDisabledUserWithCorrectPassword() {
        // 密码正确才提示禁用：只有真用户（持正确密码）才看得到禁用状态
        User user = activeUser(7L, "u7", "u7@x", "h");
        user.setStatus(UserStatus.DISABLED);
        when(userMapper.selectByLoginIdForUpdate(any())).thenReturn(List.of(user));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(true);
        UnauthorizedException e = assertThrows(UnauthorizedException.class, () -> svc.login("u7", "pw"));
        assertEquals("账号已被禁用", e.getMessage());
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userMapper.selectByLoginIdForUpdate(any())).thenReturn(
                List.of(activeUser(7L, "u7", "u7@x", "stored")));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.login("u7", "bad"));
        assertEquals(ErrorCodes.AUTH_CREDENTIALS_INVALID, e.getCode());
    }

    // ---------- refresh ----------

    @Test
    void refreshRejectsBlankToken() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.refresh("   "));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void refreshRejectsInactiveToken() {
        when(refresh.findActiveToken(any())).thenReturn(Optional.empty());
        BusinessException e = assertThrows(BusinessException.class, () -> svc.refresh("tok"));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void refreshRejectsMissingUser() {
        when(refresh.findActiveToken(any())).thenReturn(Optional.of(rec(1L, 99L)));
        when(userMapper.selectById(99L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.refresh("tok"));
        assertEquals(ErrorCodes.AUTH_USER_NOT_FOUND, e.getCode());
    }

    @Test
    void refreshRejectsDisabledUser() {
        User user = activeUser(99L, "u99", "u", "h");
        user.setStatus(UserStatus.DISABLED);
        when(refresh.findActiveToken(any())).thenReturn(Optional.of(rec(1L, 99L)));
        when(userMapper.selectById(99L)).thenReturn(user);
        UnauthorizedException e = assertThrows(UnauthorizedException.class, () -> svc.refresh("tok"));
        assertEquals("账号已被禁用", e.getMessage());
    }

    @Test
    void refreshDetectsReplayWhenRevokeReturnsFalse() {
        when(refresh.findActiveToken(any())).thenReturn(Optional.of(rec(1L, 99L)));
        when(userMapper.selectById(99L)).thenReturn(activeUser(99L, "u99", "u", "h"));
        when(refresh.revokeTokenById(1L)).thenReturn(false);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.refresh("tok"));
        assertEquals(ErrorCodes.AUTH_TOKEN_REPLAYED, e.getCode());
        verify(refresh).revokeActiveTokens(99L);   // 已吊销全部 active token 以阻断重放
    }

    @Test
    void refreshSuccessReturnsNewTokens() {
        when(refresh.findActiveToken(any())).thenReturn(Optional.of(rec(1L, 99L)));
        when(userMapper.selectById(99L)).thenReturn(activeUser(99L, "u99", "u99@x", "h"));
        when(refresh.revokeTokenById(1L)).thenReturn(true);
        when(refresh.issueToken(99L)).thenReturn(
                new RefreshTokenService.IssuedRefreshToken("NEW_REFRESH", null));
        when(jwt.issueToken(any())).thenReturn("ACCESS");
        AuthService.AuthTokens out = svc.refresh("tok");
        assertEquals(99L, out.userId());
        assertEquals("ACCESS", out.accessToken());
        assertEquals("NEW_REFRESH", out.refreshToken());
        assertFalse(out.mustChangePassword());
        assertEquals(99L, out.currentUser().userId());
        verify(refresh, never()).revokeActiveTokens(any());
    }

    // ---------- logout ----------

    @Test
    void logoutBlankShortCircuits() {
        svc.logout("   ");
        verify(refresh, never()).revokeToken(any());
        svc.logout(null);
        verify(refresh, never()).revokeToken(any());
    }

    @Test
    void logoutDelegatesRevokeToken() {
        when(refresh.revokeToken("tok")).thenReturn(true);
        svc.logout("tok");
        verify(refresh).revokeToken("tok");
    }

    // ---------- register ----------

    @Test
    void registerRejectsNullRequest() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.register(null));
        assertEquals("注册请求不能为空", e.getMessage());
    }

    @Test
    void registerRejectsInvalidUsername() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.register(req("has space", "e@x", "名", "pw123456")));
        assertEquals("用户名不合法", e.getMessage());
    }

    @Test
    void registerRejectsReservedUsername() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.register(req("admin", "e@x", "名", "pw123456")));
        assertEquals("用户名不合法", e.getMessage());
    }

    @Test
    void registerRejectsBlankEmail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.register(req("u1", "   ", "名", "pw123456")));
        assertEquals("邮箱不能为空", e.getMessage());
    }

    @Test
    void registerRejectsPasswordNotMeetingPolicy() {
        org.mockito.Mockito.doThrow(new BusinessException("密码不符合策略")).when(passwordValidator).validate("bad");
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.register(req("u1", "e@x", "名", "bad")));
        assertEquals("密码不符合策略", e.getMessage());
    }

    @Test
    void registerDetectsUsernameAlreadyExists() {
        when(userMapper.exists(any())).thenReturn(true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.register(req("u1", "e@x", "名", "pw123456")));
        assertEquals(ErrorCodes.AUTH_IDENTITY_EXISTS, e.getCode());
        assertEquals("用户名已存在", e.getMessage());
    }

    @Test
    void registerDetectsEmailAlreadyExists() {
        when(userMapper.exists(any())).thenReturn(false, true);   // 用户名查无，邮箱命中 → 邮箱已存在
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.register(req("u1", "e@x", "名", "pw123456")));
        assertEquals("邮箱已存在", e.getMessage());
    }

    @Test
    void registerSuccessPersistsUserAsActiveUser() {
        when(passwordHasher.hash(anyString())).thenReturn("H:hashed");
        svc.register(req("u1", "e@x", "显示名", "pw123456"));
        verify(passwordValidator).validate("pw123456");
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(cap.capture());
        User u = cap.getValue();
        assertEquals("u1", u.getUserCode());
        assertEquals("u1", u.getUsername());
        assertEquals("e@x", u.getEmail());
        assertEquals("显示名", u.getDisplayName());
        assertEquals("H:hashed", u.getPasswordHash());
        assertEquals(SystemRole.USER, u.getSystemRole());
        assertEquals(UserStatus.ACTIVE, u.getStatus());
        assertFalse(u.getMustChangePassword());
        verify(userMapper, times(2)).exists(any());   // 唯一性校验（用户名+邮箱）确已触发
    }

    // ---------- helpers ----------

    private static RefreshTokenRecord rec(Long id, Long userId) {
        return new RefreshTokenRecord(id, userId, "tid", "hash", NOW.plusDays(1), null);
    }
}