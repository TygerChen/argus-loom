package com.argus.rag.excel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * P2-08：STOMP/SockJS 允许来源解析契约（不再写死 "*"）。
 * 锁住：逗号分隔解析、去空白、空段忽略、空配置回退本机默认（绝不回退 "*"）。
 */
@DisplayName("WebSocket 来源解析（WebSocketConfig.resolveOriginPatterns）")
class WebSocketConfigOriginsTest {

    private static final String[] LOCAL_DEFAULTS = {
            "http://localhost:8081", "http://127.0.0.1:8081", "http://localhost:10001"};

    @Test
    void blankOrNullFallsBackToLocalDefaults() {
        assertArrayEquals(LOCAL_DEFAULTS, WebSocketConfig.resolveOriginPatterns(null));
        assertArrayEquals(LOCAL_DEFAULTS, WebSocketConfig.resolveOriginPatterns("   "));
        assertArrayEquals(LOCAL_DEFAULTS, WebSocketConfig.resolveOriginPatterns(" , , "));
    }

    @Test
    void parsesCommaSeparatedListAndTrims() {
        assertArrayEquals(
                new String[]{"https://argus.example.com", "https://argus.example.cn"},
                WebSocketConfig.resolveOriginPatterns(" https://argus.example.com , https://argus.example.cn "));
    }

    @Test
    void singleOriginYieldsSinglePattern() {
        assertArrayEquals(new String[]{"https://argus.example.com"},
                WebSocketConfig.resolveOriginPatterns("https://argus.example.com"));
    }
}
