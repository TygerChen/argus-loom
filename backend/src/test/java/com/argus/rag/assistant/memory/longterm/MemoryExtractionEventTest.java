package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryExtractionEvent 单元测试。
 */
class MemoryExtractionEventTest {

    @Test
    @DisplayName("record 组件字段应正确")
    void recordFieldsShouldBeCorrect() {
        var event = new MemoryExtractionEvent(1L, 10L, 100L, "你好", "你好！有什么可以帮助你的？");
        assertEquals(1L, event.userId());
        assertEquals(10L, event.tenantId());
        assertEquals(100L, event.sessionId());
        assertEquals("你好", event.userMessage());
        assertEquals("你好！有什么可以帮助你的？", event.reply());
    }
}