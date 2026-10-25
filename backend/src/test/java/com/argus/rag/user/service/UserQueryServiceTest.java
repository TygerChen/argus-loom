package com.argus.rag.user.service;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.user.mapper.UserMapper;
import com.argus.rag.user.model.entity.User;
import com.argus.rag.user.model.vo.AdminUserItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserQueryService} 用户查询契约（mock mapper，纯 JVM/零 DB）。
 * 锁纯 `selectById` 映射与空白短路；依赖 {@code LambdaQueryWrapper} 的成功支路（listUsers/exists 系/
 * findByUserCodeOrUsername 命中）在纯 JVM 构造即抛 MybatisPlusException，同 FinancePdfRenderer 先例不做单测。
 * - findById：缺失 → null；存在 → UserRecord 全字段映射 + mustChangePassword 布尔归一；
 * - getUser：缺失 → USER_NOT_FOUND；存在 → AdminUserItemResponse 全字段（含入 email/role/status/lastLoginAt）；
 * - findByUserCodeOrUsername：null/空白 → null 且不查库（短路验证）。
 */
@DisplayName("用户查询（UserQueryService）")
class UserQueryServiceTest {

    private UserMapper userMapper;
    private UserQueryService svc;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        svc = new UserQueryService(userMapper);
    }

    private static User user() {
        User u = new User();
        u.setId(42L);
        u.setUserCode("admin");
        u.setUsername("admin");
        u.setEmail("a@x.com");
        u.setDisplayName("管理员");
        u.setSystemRole(SystemRole.ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setMustChangePassword(true);
        u.setLastLoginAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        return u;
    }

    @Test
    void findByIdMissingReturnsNull() {
        when(userMapper.selectById(99L)).thenReturn(null);
        assertNull(svc.findById(99L));
    }

    @Test
    void findByIdMapsRecordFully() {
        when(userMapper.selectById(42L)).thenReturn(user());
        UserQueryService.UserRecord r = svc.findById(42L);
        assertEquals(42L, r.userId());
        assertEquals("admin", r.userCode());
        assertEquals("管理员", r.displayName());
        assertEquals(SystemRole.ADMIN, r.systemRole());
        assertEquals(UserStatus.ACTIVE, r.status());
        assertTrue(r.mustChangePassword());
    }

    @Test
    void findByIdNormalizesNullMustChangePasswordToFalse() {
        User u = user();
        u.setMustChangePassword(null);
        when(userMapper.selectById(42L)).thenReturn(u);
        UserQueryService.UserRecord r = svc.findById(42L);
        org.junit.jupiter.api.Assertions.assertFalse(r.mustChangePassword());
    }

    @Test
    void getUserRejectsMissing() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.getUser(7L));
        assertEquals(ErrorCodes.USER_NOT_FOUND, e.getCode());
    }

    @Test
    void getUserMapsAllFields() {
        when(userMapper.selectById(42L)).thenReturn(user());
        AdminUserItemResponse vo = svc.getUser(42L);
        assertEquals(42L, vo.userId());
        assertEquals("admin", vo.userCode());
        assertEquals("a@x.com", vo.email());
        assertEquals("管理员", vo.displayName());
        assertEquals(SystemRole.ADMIN, vo.systemRole());
        assertEquals(UserStatus.ACTIVE, vo.status());
        assertTrue(vo.mustChangePassword());
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0), vo.lastLoginAt());
    }

    @Test
    void findByAccountBlankOrNullShortCircuitsWithoutQuery() {
        assertNull(svc.findByUserCodeOrUsername(null));
        assertNull(svc.findByUserCodeOrUsername("   "));
        verify(userMapper, never()).selectOne(any());
    }
}