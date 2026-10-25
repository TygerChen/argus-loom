package com.argus.rag.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceNotFoundException} 资源不存在异常契约（纯 JVM）：HTTP 404；message+cause 两构造。
 */
@DisplayName("资源不存在异常（ResourceNotFoundException）")
class ResourceNotFoundExceptionTest {

    @Test
    void isRuntimeException() {
        assertTrue(new ResourceNotFoundException("x") instanceof RuntimeException);
    }

    @Test
    void messageOnly() {
        assertEquals("目标文档不存在", new ResourceNotFoundException("目标文档不存在").getMessage());
    }

    @Test
    void messageAndCause() {
        IllegalStateException cause = new IllegalStateException("root");
        ResourceNotFoundException e = new ResourceNotFoundException("会话不存在", cause);
        assertEquals("会话不存在", e.getMessage());
        assertSame(cause, e.getCause());
    }
}