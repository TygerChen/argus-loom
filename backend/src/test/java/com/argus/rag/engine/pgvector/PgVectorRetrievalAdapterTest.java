package com.argus.rag.engine.pgvector;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PgVectorRetrievalAdapter} 元数据解析 / 跨群组防御门行为锁
 * （纯 JVM、同包 package-private 直调、`new PgVectorRetrievalAdapter(null)` 零 vectorStore）。
 * <p>锁契约：requireLong/requireInteger 兼容 Number+String(trim) 两种元数据类型、
 * 缺值/空值/非数值抛 BusinessException；requireText 空白抛错、否则 trim；toVectorHit 做跨组校验、
 * 字段解析、score null→0D 兜底。</p>
 */
@DisplayName("向量检索元数据解析门（PgVectorRetrievalAdapter.require*/toVectorHit）")
class PgVectorRetrievalAdapterParseTest {

    private static PgVectorRetrievalAdapter adapter() {
        return new PgVectorRetrievalAdapter(null);
    }

    // ---------- requireLong ----------

    @Test
    void requireLongAcceptsNumber() {
        assertEquals(Long.valueOf(42L), adapter().requireLong(Map.of("k", 42L), "k"));
        assertEquals(Long.valueOf(7L), adapter().requireLong(Map.of("k", Integer.valueOf(7)), "k"));
    }

    @Test
    void requireLongParsesAndTrimsString() {
        assertEquals(Long.valueOf(123L), adapter().requireLong(Map.of("k", "123"), "k"));
        assertEquals(Long.valueOf(45L), adapter().requireLong(Map.of("k", "  45  "), "k"));
    }

    @Test
    void requireLongMissingOrBlankThrows() {
        BusinessException missing = assertThrows(BusinessException.class, () -> adapter().requireLong(Map.of(), "k"));
        assertTrue(missing.getMessage().contains("缺少必要元数据"));
        assertThrows(BusinessException.class, () -> adapter().requireLong(Map.of("k", "   "), "k"));
    }

    @Test
    void requireLongNonNumericStringThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter().requireLong(Map.of("k", "abc"), "k"));
        assertTrue(ex.getMessage().contains("格式非法"));
    }

    // ---------- requireInteger ----------

    @Test
    void requireIntegerAcceptsNumber() {
        assertEquals(Integer.valueOf(3), adapter().requireInteger(Map.of("k", Integer.valueOf(3)), "k"));
        assertEquals(Integer.valueOf(9), adapter().requireInteger(Map.of("k", Long.valueOf(9L)), "k"));
    }

    @Test
    void requireIntegerParsesAndTrimsString() {
        assertEquals(Integer.valueOf(5), adapter().requireInteger(Map.of("k", "5"), "k"));
        assertEquals(Integer.valueOf(12), adapter().requireInteger(Map.of("k", " 12 "), "k"));
    }

    @Test
    void requireIntegerMissingOrNonNumericThrows() {
        assertThrows(BusinessException.class, () -> adapter().requireInteger(Map.of(), "k"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter().requireInteger(Map.of("k", "1.5"), "k"));
        assertTrue(ex.getMessage().contains("格式非法"));
    }

    // ---------- requireText ----------

    @Test
    void requireTextTrimsValidText() {
        assertEquals("正文", adapter().requireText(" 正文  "));
    }

    @Test
    void requireTextBlankThrows() {
        assertThrows(BusinessException.class, () -> adapter().requireText(null));
        assertThrows(BusinessException.class, () -> adapter().requireText("   "));
    }

    // ---------- toVectorHit ----------

    private static Document hitDoc(Map<String, Object> meta) {
        return new Document("切片内容", meta);
    }

    @Test
    void toVectorHitWithMatchingGroupParsesAllFields() {
        Document doc = hitDoc(Map.of("groupId", 7L, "documentId", 1L, "chunkId", 2L, "chunkIndex", 3));
        PgVectorRetrievalAdapter.VectorHit hit = adapter().toVectorHit(7L, doc);
        assertEquals(Long.valueOf(1L), hit.documentId());
        assertEquals(Long.valueOf(2L), hit.chunkId());
        assertEquals(Integer.valueOf(3), hit.chunkIndex());
        assertEquals("切片内容", hit.chunkText());
        // Document score 为 null → 兜底 0.0
        assertEquals(0.0, hit.score(), 1e-9);
    }

    @Test
    void toVectorHitParsesIndexFromStringTypeMetadata() {
        Document doc = hitDoc(Map.of("groupId", "7", "documentId", "1", "chunkId", "2", "chunkIndex", "3"));
        PgVectorRetrievalAdapter.VectorHit hit = adapter().toVectorHit(Long.valueOf(7L), doc);
        assertEquals(Integer.valueOf(3), hit.chunkIndex());
    }

    @Test
    void toVectorHitRejectsCrossGroupReturn() {
        Document doc = hitDoc(Map.of("groupId", 9L, "documentId", 1L, "chunkId", 2L, "chunkIndex", 3));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter().toVectorHit(Long.valueOf(7L), doc));
        assertTrue(ex.getMessage().contains("跨群组"));
    }
}