package com.argus.rag.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RefreshTokenRecord} 刷新令牌记录契约（纯 JVM/零 mock/零容器）。
 * isActive(now) 判当前有效：未吊销且未过期（expiresAt 严格晚于 now）为真；已吊销或已过期为假；
 * 恰在过期边界（now==expiresAt）为假（isAfter 不含端点）。
 */
@DisplayName("刷新令牌记录（RefreshTokenRecord）")
class RefreshTokenRecordTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 19, 8, 0);
    private static final LocalDateTime EXPIRES = NOW.plusDays(7);

    private static RefreshTokenRecord record(LocalDateTime revokedAt) {
        return new RefreshTokenRecord(1L, 7L, "tid-1", "hash", EXPIRES, revokedAt);
    }

    @Test
    void validWhenNotRevokedAndNotExpired() {
        assertTrue(record(null).isActive(NOW));
    }

    @Test
    void inactiveWhenRevokedEvenIfNotExpired() {
        assertFalse(record(NOW.plusDays(1)).isActive(NOW));
    }

    @Test
    void inactiveWhenExpiredAndNotRevoked() {
        RefreshTokenRecord expired = new RefreshTokenRecord(1L, 7L, "t", "h", NOW.minusDays(1), null);
        assertFalse(expired.isActive(NOW));
    }

    @Test
    void inactiveAtExactExpiryBoundary() {
        RefreshTokenRecord atExpiry = new RefreshTokenRecord(1L, 7L, "t", "h", NOW, null);
        assertFalse(atExpiry.isActive(NOW));
    }

    @Test
    void inactiveWhenBothRevokedAndExpired() {
        assertFalse(record(EXPIRES.minusDays(1)).isActive(NOW));
    }
}