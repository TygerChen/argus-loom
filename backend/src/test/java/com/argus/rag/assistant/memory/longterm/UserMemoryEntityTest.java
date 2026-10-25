package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserMemoryEntity 单元测试。
 */
class UserMemoryEntityTest {

    @Test
    @DisplayName("实体 Lombok @Data 应生成 getter/setter")
    void lombokShouldGenerateGettersAndSetters() {
        UserMemoryEntity entity = new UserMemoryEntity();
        entity.setUserId(1L);
        entity.setTenantId(10L);
        entity.setMemoryType("PREFERENCE");
        entity.setTopic("report.format");
        entity.setContent("默认使用 Markdown");
        entity.setSource("USER_EXPLICIT");
        entity.setConfidence(new BigDecimal("0.98"));
        entity.setImportance(new BigDecimal("0.80"));
        entity.setStatus("ACTIVE");

        assertEquals(1L, entity.getUserId());
        assertEquals(10L, entity.getTenantId());
        assertEquals("PREFERENCE", entity.getMemoryType());
        assertEquals("report.format", entity.getTopic());
        assertEquals("默认使用 Markdown", entity.getContent());
        assertEquals("USER_EXPLICIT", entity.getSource());
        assertEquals(new BigDecimal("0.98"), entity.getConfidence());
        assertEquals(new BigDecimal("0.80"), entity.getImportance());
        assertEquals("ACTIVE", entity.getStatus());
    }

    @Test
    @DisplayName("默认值应为 null（非基本类型）")
    void defaultsShouldBeNull() {
        UserMemoryEntity entity = new UserMemoryEntity();
        assertNull(entity.getId());
        assertNull(entity.getUserId());
        assertNull(entity.getStatus());
        assertNull(entity.getCreatedAt());
    }
}