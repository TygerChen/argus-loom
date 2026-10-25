package com.argus.rag.engine.elasticsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EsKeywordScoring} 纯函数行为锁定测试。
 * <p>
 * 锁定从 {@code ElasticsearchChunkIndexService} 抽出的两个纯计算：关键词分数归一化
 * （对数变换）与文本规范化/截断。全部为无 IO 的 JVM 断言。
 * </p>
 */
class EsKeywordScoringTest {

    // ---------- normalizeRaw ----------

    @Test
    void normalizeRawNonPositiveReturnsZero() {
        assertEquals(0D, EsKeywordScoring.normalizeRaw(0D), 1e-12);
        assertEquals(0D, EsKeywordScoring.normalizeRaw(-5D), 1e-12);
        assertEquals(0D, EsKeywordScoring.normalizeRaw(-0.5D), 1e-12);
    }

    @Test
    void normalizeRawReferenceValueIsOne() {
        // KEYWORD_SCORE_REFERENCE=100 → log1p(100)/log1p(100)=1，且 min(1,·)=1
        assertEquals(1D, EsKeywordScoring.normalizeRaw(100D), 1e-12);
    }

    @Test
    void normalizeRawLargeValueClampsToOne() {
        assertEquals(1D, EsKeywordScoring.normalizeRaw(1000D), 1e-12);
        assertEquals(1D, EsKeywordScoring.normalizeRaw(1e6), 1e-12);
    }

    @Test
    void normalizeRawTenIsRoughlyHalf() {
        // log1p(10)/log1p(100) ≈ 2.397/4.615 ≈ 0.52
        double v = EsKeywordScoring.normalizeRaw(10D);
        assertTrue(v >= 0.50D && v <= 0.54D, "raw=10 归一化应约 0.52, got=" + v);
    }

    @Test
    void normalizeRawStrictlyIncreasingInReferenceRange() {
        double low = EsKeywordScoring.normalizeRaw(1D);
        double mid = EsKeywordScoring.normalizeRaw(10D);
        double high = EsKeywordScoring.normalizeRaw(50D);
        assertTrue(0D < low && low < mid && mid < high && high < 1D,
                "归一化在 [0,100] 内应严格单调增且 <1, got " + low + "/" + mid + "/" + high);
    }

    @Test
    void normalizeRawUnitForSingleHitVsReference() {
        // 一个命中的 rawScore=1 → log1p(1)/log1p(100)≈0.4669
        double v = EsKeywordScoring.normalizeRaw(1D);
        assertTrue(v > 0.13D && v < 0.17D, "raw=1 归一化应约 0.150, got=" + v);
    }

    // ---------- abbreviate ----------

    @Test
    void abbreviateBlankReturnsEmpty() {
        assertEquals("", EsKeywordScoring.abbreviate(null));
        assertEquals("", EsKeywordScoring.abbreviate(""));
        assertEquals("", EsKeywordScoring.abbreviate("   "));
        assertEquals("", EsKeywordScoring.abbreviate("\t\n "));
    }

    @Test
    void abbreviateShortTextTrimmedAndCollapsed() {
        assertEquals("hello world", EsKeywordScoring.abbreviate("  hello   world  "));
        assertEquals("a", EsKeywordScoring.abbreviate("a"));
    }

    @Test
    void abbreviateExactlyOneTwentyKept() {
        String s120 = "x".repeat(120);
        assertEquals(s120, EsKeywordScoring.abbreviate(s120));
    }

    @Test
    void abbreviateLongTextTrimsToOneTwentyPlusEllipsis() {
        String s = "y".repeat(123);
        assertEquals("y".repeat(120) + "...", EsKeywordScoring.abbreviate(s));
    }

    @Test
    void abbreviateCollapsesNewlinesAndTabs() {
        assertEquals("a b", EsKeywordScoring.abbreviate("a\n\t b"));
    }
}