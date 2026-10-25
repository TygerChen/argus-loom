package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryStatus 枚举单元测试。
 */
class MemoryStatusTest {

    @Test
    @DisplayName("所有 7 种状态应存在")
    void allSevenStatusesShouldExist() {
        MemoryStatus[] values = MemoryStatus.values();
        assertEquals(7, values.length, "应有 7 种状态");
    }

    @Test
    @DisplayName("状态名称字符串匹配")
    void statusNamesShouldMatch() {
        assertEquals("CANDIDATE", MemoryStatus.CANDIDATE.name());
        assertEquals("PENDING_CONFIRMATION", MemoryStatus.PENDING_CONFIRMATION.name());
        assertEquals("ACTIVE", MemoryStatus.ACTIVE.name());
        assertEquals("SUPERSEDED", MemoryStatus.SUPERSEDED.name());
        assertEquals("EXPIRED", MemoryStatus.EXPIRED.name());
        assertEquals("REVOKED", MemoryStatus.REVOKED.name());
        assertEquals("DELETED", MemoryStatus.DELETED.name());
    }
}