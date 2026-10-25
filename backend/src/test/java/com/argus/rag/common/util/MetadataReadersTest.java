package com.argus.rag.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MetadataReaders} 纯 Map 元数据按类型取值锁。
 * <p>三处（{@code AssistantKnowledgeBaseTool}/{@code QaRetrievalService}/{@code QaRecordPersistenceService}）
 * 的 Number 取值分身已收敛为委托本类，行为须保持：Number→按目标类型取值、缺失/非 Number→null。</p>
 */
class MetadataReadersTest {

    private Map<String, Object> metadata(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void longValueReadsNumberAndNullsOtherwise() {
        assertEquals(42L, MetadataReaders.longValue(metadata("a", 42L), "a"));
        assertEquals(7L, MetadataReaders.longValue(metadata("a", Integer.valueOf(7)), "a"));
        assertNull(MetadataReaders.longValue(metadata("a", "abc"), "a"));
        assertNull(MetadataReaders.longValue(metadata("a", null), "a"));
        assertNull(MetadataReaders.longValue(metadata(), "absent"));
    }

    @Test
    void intValueReadsNumberAndNullsOther() {
        assertEquals(7, MetadataReaders.intValue(metadata("a", Integer.valueOf(7)), "a"));
        assertEquals(7, MetadataReaders.intValue(metadata("a", 7L), "a"));
        assertNull(MetadataReaders.intValue(metadata("a", "abc"), "a"));
        assertNull(MetadataReaders.intValue(metadata(), "absent"));
    }

    @Test
    void doubleValueReadsNumberAndNullsOther() {
        assertEquals(4.5, MetadataReaders.doubleValue(metadata("a", 4.5), "a"));
        assertEquals(2.0, MetadataReaders.doubleValue(metadata("a", 2L), "a"));
        assertNull(MetadataReaders.doubleValue(metadata("a", "abc"), "a"));
        assertNull(MetadataReaders.doubleValue(metadata(), "absent"));
    }

    @Test
    void scoreReadsNumberAndDefaultsToZero() {
        assertEquals(3.25, MetadataReaders.score(metadata("score", 3.25)));
        assertEquals(2.0, MetadataReaders.score(metadata("score", 2L)));
        assertEquals(0D, MetadataReaders.score(metadata("score", "abc")), 0.0);
        assertEquals(0D, MetadataReaders.score(metadata()), 0.0);
        assertEquals(0D, MetadataReaders.score(metadata("other", 9D)), 0.0);
    }

    @Test
    void textReadsAndTrimsStringNullOtherwise() {
        assertEquals("abc", MetadataReaders.text(metadata("a", " abc "), "a"));
        assertEquals("", MetadataReaders.text(metadata("a", "   "), "a"));
        assertNull(MetadataReaders.text(metadata("a", 42L), "a"));
        assertNull(MetadataReaders.text(metadata(), "absent"));
    }
}