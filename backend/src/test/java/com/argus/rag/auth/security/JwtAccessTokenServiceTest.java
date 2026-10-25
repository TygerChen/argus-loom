package com.argus.rag.auth.security;

import com.argus.rag.auth.config.AuthProperties;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtAccessTokenService} 签发/解析契约（注入固定 Clock，纯 jjwt、零 I/O）：
 * - 密钥：< 32 字节构造即抛 {@code IllegalStateException("JWT secret 至少需要 32 字节")}；
 * - 往返：签发 → 解析出与 {@link TokenSubject} 一致的 claims + issuedAt/expiresAt（=iat+expireMinutes）；
 * - 校验：签发者不匹配 / 缺任一必需 claim / 过期 / 非 jwt / 异密钥签名 均 → {@code BusinessException(AUTH_TOKEN_INVALID)}。
 * 测试用 {@code Keys.hmacShaKeyFor(secret)} 自签 token 驱动各拒绝分支，避免依赖私有签名密钥；过期用真实
 * {@code now} 锻造（jjwt 按系统当前时间校验 exp，而非注入的 Clock）。
 */
@DisplayName("JWT Access Token（JwtAccessTokenService）")
class JwtAccessTokenServiceTest {

    private static final String SECRET = "super-secret-key-must-be-forty-characters-long!!";
    private static final Instant NOW = Clock.fixed(Instant.parse("2999-01-01T00:00:00Z"), ZoneOffset.UTC).instant();

    private AuthProperties props;
    private JwtAccessTokenService svc;

    private static final JwtAccessTokenService.TokenSubject SUBJECT =
            new JwtAccessTokenService.TokenSubject(7L, "U7", "会计", SystemRole.FINANCE, true);

    @BeforeEach
    void setUp() {
        props = new AuthProperties();
        props.setIssuer("argus-rag");
        props.setAccessTokenExpireMinutes(30);
        props.setJwtSecret(SECRET);
        svc = new JwtAccessTokenService(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** 用当前密钥自签一枚 token，便于驱动各拒绝分支（可选择性省略 claim / 换 issuer / 换 exp）。 */
    private static String forge(String issuer, Long uid, String role, Boolean pwd, Instant exp) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("displayName", "会计");
        if (uid != null) claims.put("uid", uid);
        if (role != null) claims.put("systemRole", role);
        if (pwd != null) claims.put("mustChangePassword", pwd);
        return Jwts.builder()
                .issuer(issuer)
                .subject("U7")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(exp))
                .claims(claims)
                .signWith(key())
                .compact();
    }

    // ---------- 密钥校验 ----------

    @Test
    void constructorRejectsShortSecret() {
        AuthProperties shortProps = new AuthProperties();
        shortProps.setJwtSecret("too-short");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtAccessTokenService(shortProps, Clock.systemUTC()));
        assertTrue(e.getMessage().contains("32 字节"));
    }

    // ---------- 签发/解析往返 ----------

    @Test
    void roundTripYieldsMatchingClaimsAndTimes() {
        String token = svc.issueToken(SUBJECT);
        JwtAccessTokenService.AccessTokenClaims got = svc.parse(token);
        assertEquals(7L, got.userId());
        assertEquals("U7", got.userCode());
        assertEquals("会计", got.displayName());
        assertEquals(SystemRole.FINANCE, got.systemRole());
        assertEquals(true, got.mustChangePassword());
        assertEquals(NOW, got.issuedAt());
        assertEquals(NOW.plus(30, ChronoUnit.MINUTES), got.expiresAt());
    }

    @Test
    void expirationUsesConfiguredMinutes() {
        props.setAccessTokenExpireMinutes(90);
        svc = new JwtAccessTokenService(props, Clock.fixed(NOW, ZoneOffset.UTC));
        var claims = svc.parse(svc.issueToken(SUBJECT));
        assertEquals(NOW.plus(90, ChronoUnit.MINUTES), claims.expiresAt());
    }

    // ---------- 拒绝路径 ----------

    @Test
    void rejectsTokenWithWrongIssuerSameSecret() {
        String token = forge("other-issuer", 7L, SystemRole.FINANCE.name(), false,
                NOW.plus(30, ChronoUnit.MINUTES));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.parse(token));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void rejectsMissingRoleClaim() {
        String token = forge("argus-rag", 7L, (String) null, false, NOW.plus(30, ChronoUnit.MINUTES));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.parse(token));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void rejectsMissingUidClaim() {
        String token = forge("argus-rag", (Long) null, SystemRole.FINANCE.name(), false,
                NOW.plus(30, ChronoUnit.MINUTES));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.parse(token));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void rejectsGarbageToken() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.parse("not-a-jwt"));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void rejectsExpiredToken() {
        // 过期校验由 jjwt 按系统当前时间判断 → 用真实 now 铸造早已过期的 token
        String token = forge("argus-rag", 7L, SystemRole.FINANCE.name(), false,
                Instant.now().minus(1, ChronoUnit.HOURS));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.parse(token));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        String otherSecret = "an-entirely-different-secret-key-long-enough-0123456789";
        String token = Jwts.builder()
                .issuer("argus-rag")
                .subject("U7")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plus(30, ChronoUnit.MINUTES)))
                .claim("uid", 7L)
                .claim("displayName", "会计")
                .claim("systemRole", SystemRole.FINANCE.name())
                .claim("mustChangePassword", false)
                .signWith(Keys.hmacShaKeyFor(otherSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        BusinessException e = assertThrows(BusinessException.class, () -> svc.parse(token));
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, e.getCode());
    }
}