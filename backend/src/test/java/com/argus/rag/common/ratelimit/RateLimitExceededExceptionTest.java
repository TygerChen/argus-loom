package com.argus.rag.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimitExceededException} 限流超限异常契约（纯 JVM，期望 429）。
 * limit/window/scope 三参构造：消息按「scope 维度每 window 秒最多 limit 次」格式化，getter 透传。
 */
@DisplayName("限流超限异常（RateLimitExceededException）")
class RateLimitExceededExceptionTest {

    @Test
    void isRuntimeException() {
        assertTrue(new RateLimitExceededException(10, 60, "IP") instanceof RuntimeException);
    }

    @Test
    void gettersRoundTrip() {
        RateLimitExceededException e = new RateLimitExceededException(10, 60, "IP");
        assertEquals(10, e.getLimit());
        assertEquals(60, e.getWindow());
        assertEquals("IP", e.getScope());
    }

    @Test
    void messageIsFormattedWithScopeWindowLimit() {
        String msg = new RateLimitExceededException(5, 30, "用户").getMessage();
        assertTrue(msg.contains("用户"));
        assertTrue(msg.contains("30"));
        assertTrue(msg.contains("5"));
    }
}