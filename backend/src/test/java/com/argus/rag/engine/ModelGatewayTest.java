package com.argus.rag.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelGateway} 降级占位契约（纯 JVM/零 mock）。
 * 降级链尚未落地，{@code generateWithFallback} 恒抛 UnsupportedOperationException
 * （fail-fast，不返回空白占位，避免调用方误判「成功但无内容」）。
 */
@DisplayName("模型降级入口占位（ModelGateway）")
class ModelGatewayTest {

    private final ModelGateway gateway = new ModelGateway();

    @Test
    void generateWithFallbackAlwaysThrowsUnsupported() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> gateway.generateWithFallback("any prompt"));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("not wired"));
    }

    @Test
    void throwsRegardlessOfPrompt() {
        assertThrows(UnsupportedOperationException.class, () -> gateway.generateWithFallback(null));
        assertThrows(UnsupportedOperationException.class, () -> gateway.generateWithFallback(""));
        assertThrows(UnsupportedOperationException.class, () -> gateway.generateWithFallback("x"));
    }
}