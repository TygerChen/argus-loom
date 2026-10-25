package com.argus.rag.qa.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RrfFusion} 倒数排名融合公式的行为契约。
 *
 * <p>锁住：默认 k=0 的精确 1/rank 语义、非默认 k 的 1/(k+rank)、
 * rank<=0 按 1 处理、负 k 按 0 处理、rank 越高分数越低。</p>
 */
@DisplayName("RRF 倒数排名融合（RrfFusion）")
class RrfFusionTest {

    @Test
    void defaultKrank1GivesFullScore() {
        assertEquals(1.0, RrfFusion.rankScore(1), 1e-9);
    }

    @Test
    void defaultKrank2IsHalf() {
        assertEquals(1.0 / 2, RrfFusion.rankScore(2), 1e-9);
    }

    @Test
    void defaultKrank5IsOneFifth() {
        assertEquals(1.0 / 5, RrfFusion.rankScore(5), 1e-9);
    }

    @Test
    void customKShiftsDenominator() {
        assertEquals(1.0 / 12, RrfFusion.rankScore(2, 10), 1e-9);
    }

    @Test
    void nonPositiveRankClampedToOne() {
        assertEquals(1.0, RrfFusion.rankScore(0), 1e-9);
        assertEquals(1.0, RrfFusion.rankScore(-3), 1e-9);
    }

    @Test
    void negativeKellyClampedToZero() {
        assertEquals(1.0 / 4, RrfFusion.rankScore(4, -5), 1e-9);
    }

    @Test
    void higherRankNeverOutscoresLowerRank() {
        double prev = RrfFusion.rankScore(1);
        for (int rank = 2; rank <= 20; rank++) {
            double cur = RrfFusion.rankScore(rank);
            assertEquals(true, cur < prev, "rank " + rank + " 不应高于前一 rank");
            prev = cur;
        }
    }
}