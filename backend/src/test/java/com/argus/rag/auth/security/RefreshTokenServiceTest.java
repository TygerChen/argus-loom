package com.argus.rag.auth.security;

import com.argus.rag.auth.config.AuthProperties;
import com.argus.rag.auth.mapper.UserRefreshTokenMapper;
import com.argus.rag.auth.model.entity.UserRefreshToken;
import com.argus.rag.auth.service.PasswordHasher;
import com.argus.rag.auth.service.RefreshTokenRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefreshTokenService} 刷新 token 契约（mock mapper + {@link PasswordHasher}，注入固定 Clock，
 * 纯 JVM / 零 DB；不触碰任何 Lambda 缺省 SQL，mocked mapper 吞掉 selectOne/update）：
 * - 签发：token 形如 {@code tokenId.secret}，tokenId 随机、secret 为 URL-safe Base64，存储用 hash(完整 token)
 *   + expiresAt=now+expireDays + createdAt，落库实体各字段可捕获断言；
 * - 查找：null/空白/格式错 → empty 且不查库；查无记录 / hash 不匹配 / 已吊销 / 已过期 → empty；有效 → 返回记录；
 * - 吊销：revokeActiveTokens 置 revokedAt=now 仅作用于未吊销未过期；revokeTokenById 按受影响行数返回；
 *   revokeToken 在无有效 token 时短路 false、有则委派至 revokeTokenById。
 * 用检测实体参数方式避开 LambdaQueryWrapper 的 MP 运行时反射缓存（同 FinancePdfRenderer 先例）。
 */
@DisplayName("刷新令牌（RefreshTokenService）")
class RefreshTokenServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 10, 30, 0);
    private static final Clock CLOCK = Clock.fixed(
            NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private UserRefreshTokenMapper mapper;
    private PasswordHasher hasher;
    private RefreshTokenService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(UserRefreshTokenMapper.class);
        hasher = mock(PasswordHasher.class);
        AuthProperties props = new AuthProperties();
        props.setRefreshTokenExpireDays(14);
        svc = new RefreshTokenService(mapper, hasher, props, CLOCK);
    }

    private static UserRefreshToken rec(Long id, Long uid, String tokenId, String hash,
                                        LocalDateTime exp, LocalDateTime revoked) {
        UserRefreshToken e = new UserRefreshToken();
        e.setId(id);
        e.setUserId(uid);
        e.setTokenId(tokenId);
        e.setTokenHash(hash);
        e.setExpiresAt(exp);
        e.setRevokedAt(revoked);
        return e;
    }

    // ---------- issueToken ----------

    @Test
    void issueTokenBuildsIdDotSecretAndPersistsEntity() {
        when(hasher.hash(anyString())).thenAnswer(inv -> "H:" + inv.getArgument(0));
        RefreshTokenService.IssuedRefreshToken out = svc.issueToken(7L);

        String[] parts = out.refreshToken().split("\\.", 2);
        assertEquals(2, parts.length);
        String tokenId = parts[0];
        assertTrue(parts[1].matches("[A-Za-z0-9_-]+"), "secret 应为 URL-safe Base64");

        ArgumentCaptor<UserRefreshToken> cap = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(mapper).insert(cap.capture());
        UserRefreshToken ent = cap.getValue();
        assertEquals(7L, ent.getUserId());
        assertEquals(tokenId, ent.getTokenId());
        assertEquals("H:" + out.refreshToken(), ent.getTokenHash());   // 存储的是完整 token 的哈希
        assertEquals(NOW.plus(14, ChronoUnit.DAYS), ent.getExpiresAt());
        assertEquals(NOW, ent.getCreatedAt());
    }

    @Test
    void issueTokenGeneratesDistinctTokensPerCall() {
        RefreshTokenService.IssuedRefreshToken a = svc.issueToken(1L);
        RefreshTokenService.IssuedRefreshToken b = svc.issueToken(2L);
        assertNotEquals(a.refreshToken(), b.refreshToken());
        String[] pa = a.refreshToken().split("\\.", 2);
        String[] pb = b.refreshToken().split("\\.", 2);
        assertNotEquals(pa[0], pb[0]);   // tokenId 亦不同（UUID）
    }

    // ---------- findActiveToken ----------

    @Test
    void findActiveTokenNullBlankYieldsEmptyWithoutQuery() {
        assertTrue(svc.findActiveToken(null).isEmpty());
        assertTrue(svc.findActiveToken("   ").isEmpty());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void findActiveTokenMalformedYieldsEmpty() {
        assertTrue(svc.findActiveToken("onlyId").isEmpty());          // 无分隔符
        assertTrue(svc.findActiveToken("id.").isEmpty());              // 缺 secret
        assertTrue(svc.findActiveToken(".secret").isEmpty());          // 缺 tokenId
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void findActiveTokenUnknownReturnsEmpty() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(svc.findActiveToken("abc.secret").isEmpty());
    }

    @Test
    void findActiveTokenHashMismatchReturnsEmpty() {
        when(mapper.selectOne(any())).thenReturn(
                rec(5L, 7L, "abc", "H:x", NOW.plusDays(1), null));
        when(hasher.matches(anyString(), anyString())).thenReturn(false);
        assertTrue(svc.findActiveToken("abc.secret").isEmpty());
    }

    @Test
    void findActiveTokenExpiredOrRevokedReturnsEmpty() {
        when(mapper.selectOne(any())).thenReturn(
                rec(5L, 7L, "abc", "H:x", NOW.minusDays(1), null));
        when(hasher.matches(anyString(), anyString())).thenReturn(true);
        assertTrue(svc.findActiveToken("abc.secret").isEmpty());      // 过期

        when(mapper.selectOne(any())).thenReturn(
                rec(6L, 8L, "def", "H:y", NOW.plusDays(1), NOW));
        assertTrue(svc.findActiveToken("def.secret").isEmpty());      // 已吊销
    }

    @Test
    void findActiveTokenValidReturnsRecord() {
        when(mapper.selectOne(any())).thenReturn(
                rec(5L, 7L, "abc", "H:tok", NOW.plusDays(14), null));
        when(hasher.matches(anyString(), anyString())).thenReturn(true);
        Optional<RefreshTokenRecord> out = svc.findActiveToken("abc.secret");
        assertTrue(out.isPresent());
        RefreshTokenRecord r = out.get();
        assertEquals(5L, r.id());
        assertEquals(7L, r.userId());
        assertEquals("abc", r.tokenId());
        assertEquals("H:tok", r.tokenHash());
        assertEquals(NOW.plusDays(14), r.expiresAt());
    }

    // ---------- revokeActiveTokens (单设备登录) ----------

    @Test
    void revokeActiveTokensStampsRevokedAtOnUnrevokedUnexpired() {
        svc.revokeActiveTokens(7L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UserRefreshToken> entCol = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(mapper).update(entCol.capture(), any());
        assertEquals(NOW, entCol.getValue().getRevokedAt());
    }

    // ---------- revokeTokenById / revokeToken ----------

    @Test
    void revokeByIdReflectsAffectedRows() {
        when(mapper.revokeByIdIfActive(10L, NOW)).thenReturn(0);
        assertFalse(svc.revokeTokenById(10L));
        when(mapper.revokeByIdIfActive(11L, NOW)).thenReturn(1);
        assertTrue(svc.revokeTokenById(11L));
    }

    @Test
    void revokeTokenShortCircuitsWhenNoActiveToken() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertFalse(svc.revokeToken("abc.secret"));
        verify(mapper, never()).revokeByIdIfActive(any(), any());
    }

    @Test
    void revokeTokenDelegatesRevokeByIdWhenActive() {
        when(mapper.selectOne(any())).thenReturn(
                rec(5L, 7L, "abc", "H:tok", NOW.plusDays(1), null));
        when(hasher.matches(anyString(), anyString())).thenReturn(true);
        when(mapper.revokeByIdIfActive(5L, NOW)).thenReturn(1);
        assertTrue(svc.revokeToken("abc.secret"));
    }
}