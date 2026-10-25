package com.argus.rag.auth.model.vo;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.auth.service.AuthService.AuthTokens;
import com.argus.rag.common.enums.SystemRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AuthTokensResponse}/{@link CurrentUserProfileResponse} 认证响应 VO 映射契约
 * （纯 JVM/零容器）。from(AuthTokens) 把 accessToken + 当前用户展平为响应；CurrentUser
 * 3 参简写（系统角色 USER、必改密码 false）。
 */
@DisplayName("认证响应 VO 映射（AuthTokensResponse/CurrentUserProfileResponse）")
class AuthTokensResponseTest {

    @Test
    void fromAuthTokensMapsAccessTokenAndUser() {
        CurrentUserService.CurrentUser current = new CurrentUserService.CurrentUser(
                7L, "U007", "测试用户", SystemRole.ADMIN, true);
        AuthTokens tokens = new AuthTokens(7L, "at-abc", "rt-xyz", true, current);

        AuthTokensResponse resp = AuthTokensResponse.from(tokens);

        // accessToken 原样
        assertEquals("at-abc", resp.accessToken());
        // 内部 currentUser 展平
        assertEquals(7L, resp.currentUser().userId());
        assertEquals("U007", resp.currentUser().userCode());
        assertEquals("测试用户", resp.currentUser().displayName());
        assertEquals(SystemRole.ADMIN, resp.currentUser().systemRole());
        assertEquals(true, resp.currentUser().mustChangePassword());
    }

    @Test
    void profileFromMapsThreeArgShortcutToDefaults() {
        CurrentUserService.CurrentUser shortUser =
                new CurrentUserService.CurrentUser(2L, "mem", "成员");
        CurrentUserProfileResponse resp = CurrentUserProfileResponse.from(shortUser);
        assertEquals(2L, resp.userId());
        assertEquals("mem", resp.userCode());
        assertEquals("成员", resp.displayName());
        assertEquals(SystemRole.USER, resp.systemRole());   // 3 参简写默认 USER
        assertEquals(false, resp.mustChangePassword());      // 3 参简写默认 false
    }
}