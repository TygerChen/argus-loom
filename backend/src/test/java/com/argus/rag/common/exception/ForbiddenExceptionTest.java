package com.argus.rag.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ForbiddenException} 权限不足异常契约（纯 JVM）：RuntimeException，期望 HTTP 403。
 */
@DisplayName("权限不足异常（ForbiddenException）")
class ForbiddenExceptionTest {

    @Test
    void isRuntimeException() {
        assertTrue(new ForbiddenException("x") instanceof RuntimeException);
    }

    @Test
    void carriesMessage() {
        assertEquals("无权访问该文档", new ForbiddenException("无权访问该文档").getMessage());
    }

    @Test
    void nullMessageAllowed() {
        assertEquals(null, new ForbiddenException(null).getMessage());
    }
}