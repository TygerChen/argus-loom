package com.argus.rag.auth.security;

import com.argus.rag.auth.config.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthCookieSupport} refresh-token Cookie 读写契约
 * （真实 {@link AuthProperties} + mock {@link HttpServletResponse}，纯 JVM/零 servlet 容器）。
 * 写/清 Cookie 均以 addHeader("Set-Cookie", …) 输出 ResponseCookie：
 * httpOnly、SameSite=Lax、Path=/、Secure 随生产开关、Max-Age=过期天数。
 */
@DisplayName("Refresh Cookie 读写（AuthCookieSupport）")
class AuthCookieSupportTest {

    private HttpServletResponse response;
    private AuthCookieSupport support;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setRefreshCookieName("ARGUS_REFRESH");
        props.setRefreshTokenExpireDays(7);
        props.setRefreshCookieSecure(true);
        support = new AuthCookieSupport(props);
        response = mock(HttpServletResponse.class);
    }

    private String capturedCookie() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
        return captor.getValue();
    }

    @Test
    void writeCookieCarriesTokenAndPolicy() {
        support.writeRefreshTokenCookie(response, "tok-123");
        String cookie = capturedCookie();
        assertTrue(cookie.startsWith("ARGUS_REFRESH=tok-123"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/"));
    }

    @Test
    void writeCookieSecureMaxAgeComputeFromDays() {
        support.writeRefreshTokenCookie(response, "tok-123");
        String cookie = capturedCookie();
        // 7 天 → 604800 秒
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("Max-Age=604800"));
    }

    @Test
    void clearCookieEmptiesValueAndZeroMaxAge() {
        support.clearRefreshTokenCookie(response);
        String cookie = capturedCookie();
        assertTrue(cookie.startsWith("ARGUS_REFRESH="));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Max-Age=0"));
    }

    @Test
    void devInsecureOmitsSecureFlag() {
        AuthProperties dev = new AuthProperties();
        dev.setRefreshCookieName("ARGUS_REFRESH");
        dev.setRefreshTokenExpireDays(1);
        dev.setRefreshCookieSecure(false);
        AuthCookieSupport devSupport = new AuthCookieSupport(dev);
        HttpServletResponse devResp = mock(HttpServletResponse.class);
        devSupport.writeRefreshTokenCookie(devResp, "tok");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(devResp).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
        assertFalse(captor.getValue().toLowerCase().contains("secure"));
    }

    @Test
    void headerNameIsSetCookie() {
        support.writeRefreshTokenCookie(response, "tok");
        verify(response).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    void customExpireDaysReflectedInMaxAge() {
        AuthProperties custom = new AuthProperties();
        custom.setRefreshCookieName("X");
        custom.setRefreshTokenExpireDays(1);
        custom.setRefreshCookieSecure(true);
        HttpServletResponse oneDayResp = mock(HttpServletResponse.class);
        new AuthCookieSupport(custom).writeRefreshTokenCookie(oneDayResp, "t");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(oneDayResp).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
        assertTrue(captor.getValue().contains("Max-Age=86400"));
        assertNotNull(captor.getValue());
    }
}