package com.argus.rag.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BusinessException} 业务异常契约（纯 JVM）。
 * 四构造函数：未指定 code 时 getCode()=null；带 code 时返回稳定业务错误码；cause/嵌套根因透传。
 */
@DisplayName("业务异常（BusinessException）")
class BusinessExceptionTest {

    @Test
    void isRuntimeException() {
        assertTrue(new BusinessException("oops") instanceof RuntimeException);
    }

    @Test
    void messageOnlyLeavesCodeNull() {
        BusinessException e = new BusinessException("拒绝作答");
        assertEquals("拒绝作答", e.getMessage());
        assertNull(e.getCode());
    }

    @Test
    void messageAndCause() {
        IllegalStateException cause = new IllegalStateException("downstream");
        BusinessException e = new BusinessException("业务失败", cause);
        assertEquals("业务失败", e.getMessage());
        assertSame(cause, e.getCause());
        assertNull(e.getCode());
    }

    @Test
    void codeAndMessage() {
        BusinessException e = new BusinessException("REJECT_ANSWER", "拒绝作答原因");
        assertEquals("REJECT_ANSWER", e.getCode());
        assertEquals("拒绝作答原因", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void codeMessageAndCause() {
        IllegalArgumentException cause = new IllegalArgumentException("root");
        BusinessException e = new BusinessException("CODE_X", "文案", cause);
        assertEquals("CODE_X", e.getCode());
        assertEquals("文案", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void codeNullWhenMessageOnly() {
        assertTrue(new BusinessException("仅文案").getCode() == null);
    }
}