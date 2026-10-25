package com.argus.rag.auth.model.vo;

import com.argus.rag.auth.CurrentUserService.CurrentUser;
import com.argus.rag.common.enums.SystemRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CurrentUserProfileResponse} 当前用户信息响应映射契约（真实 {@link CurrentUser}，纯 JVM/零 mock）。
 * from 把当前用户 5 字段逐一展平为响应 VO；布尔 mustChangePassword 语义原样透传。
 */
@DisplayName("当前用户信息响应（CurrentUserProfileResponse）")
class CurrentUserProfileResponseTest {

    @Test
    void fromFlattensCurrentUserFields() {
        CurrentUserProfileResponse r = CurrentUserProfileResponse.from(
                new CurrentUser(7L, "u007", "张三", SystemRole.ADMIN, true));

        assertEquals(7L, r.userId());
        assertEquals("u007", r.userCode());
        assertEquals("张三", r.displayName());
        assertEquals(SystemRole.ADMIN, r.systemRole());
        assertTrue(r.mustChangePassword());
    }

    @Test
    void fromCarriesMustChangePasswordFalseWhenNotSet() {
        CurrentUserProfileResponse r = CurrentUserProfileResponse.from(
                new CurrentUser(9L, "u009", "Lisi", SystemRole.USER, false));
        assertFalse(r.mustChangePassword());
        assertEquals(SystemRole.USER, r.systemRole());
    }
}