package com.argus.rag.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UnauthorizedException} 未认证异常契约（纯 JVM）：由统一异常处理器映射 HTTP 401。
 */
@DisplayName("未认证异常（UnauthorizedException）")
class UnauthorizedExceptionTest {

    @Test
    void isRuntimeException() {
        assertTrue(new UnauthorizedException("x") instanceof RuntimeException);
    }

    @Test
    void carriesMessage() {
        assertEquals("未登录或认证已失效", new UnauthorizedException("未登录或认证已失效").getMessage());
    }
}