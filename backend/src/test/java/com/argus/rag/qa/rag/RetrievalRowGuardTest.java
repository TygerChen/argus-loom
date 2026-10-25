package com.argus.rag.qa.rag;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalRowGuard} 契约测试。
 *
 * <p>这些纯函数从 {@code HybridChunkRetrievalService} 下沉而来，本测试锁住
 * 现有行为（正常路径 + 非法/缺失输入的异常），确保重构不改变检索语义。</p>
 */
@DisplayName("检索结果行取值/规整（RetrievalRowGuard）")
class RetrievalRowGuardTest {

    // ── normalizeScore ────────────────────────────────────────────────
    @Test
    void normalizeScoreShouldMapIntoRangeZeroToOne() {
        assertEquals(0.0, RetrievalRowGuard.normalizeScore(0.0), 1e-9);
        assertTrue(RetrievalRowGuard.normalizeScore(5.0) > 0.99);
        assertTrue(RetrievalRowGuard.normalizeScore(5.0) < 1.0);
        assertEquals(1.0 - Math.exp(-2.5), RetrievalRowGuard.normalizeScore(2.5), 1e-12);
    }

    // ── getValue ─────────────────────────────────────────────────────
    @Test
    void getValueShouldReadCamelCaseAndLowercaseKeys() {
        Map<String, Object> row = new HashMap<>();
        row.put("chunkId", 1L);
        row.put("documentid", 2L);
        assertEquals(1L, RetrievalRowGuard.getValue(row, "chunkId"));
        assertEquals(2L, RetrievalRowGuard.getValue(row, "documentId")); // 回退小写键
    }

    @Test
    void getValueShouldReturnNullWhenMissing() {
        assertNull(RetrievalRowGuard.getValue(new HashMap<>(), "nope"));
    }

    // ── requirePositiveGroupId ───────────────────────────────────────
    @Test
    void requirePositiveGroupIdRejectsInvalid() {
        assertEquals(7L, RetrievalRowGuard.requirePositiveGroupId(7L));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requirePositiveGroupId(0L));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requirePositiveGroupId(-1L));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requirePositiveGroupId(null));
    }

    // ── requireQuestion ──────────────────────────────────────────────
    @Test
    void requireQuestionTrimsAndRejectsBlank() {
        assertEquals("检索", RetrievalRowGuard.requireQuestion("  检索  "));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireQuestion(""));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireQuestion("   "));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireQuestion(null));
    }

    // ── requireLong / requireInteger ─────────────────────────────────
    @Test
    void requireLongAndIntegerConvertNumbers() {
        assertEquals(42L, RetrievalRowGuard.requireLong(42L, "chunkId"));
        assertEquals(42L, RetrievalRowGuard.requireLong(42.99, "chunkId"));
        assertEquals(7, RetrievalRowGuard.requireInteger(7, "chunkIndex"));
        assertEquals(7, RetrievalRowGuard.requireInteger(7L, "chunkIndex"));
    }

    @Test
    void requireLongAndIntegerRejectNonNumber() {
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireLong("abc", "chunkId"));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireInteger(null, "chunkIndex"));
    }

    // ── requireText ──────────────────────────────────────────────────
    @Test
    void requireTextTrimsAndRejectsBlank() {
        assertEquals("doc", RetrievalRowGuard.requireText("  doc  ", "fileName"));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireText("", "fileName"));
        assertThrows(BusinessException.class, () -> RetrievalRowGuard.requireText(null, "fileName"));
    }
}