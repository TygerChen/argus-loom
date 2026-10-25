package com.argus.rag.engine.reranking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RerankingService.NoOp} 重排降级实现行为锁（无进程态、纯 JVM）。
 * <p>锁契约：空/null 候选→空；否则按原序以递减分数 {@code 1.0 - index*0.001} 输出，
 * 结果索引与原候选一一对应（{@code result[i].index()==i}）且分数严格递减。</p>
 */
class RerankingServiceNoopTest {

    private final RerankingService.NoOp noOp = new RerankingService.NoOp();

    @Test
    void nullOrEmptyCandidatesYieldEmpty() {
        assertTrue(noOp.rerank("q", null).isEmpty());
        assertTrue(noOp.rerank("q", List.of()).isEmpty());
    }

    @Test
    void returnsOneResultPerCandidateInOriginalOrderWithDescScores() {
        List<RerankingService.RerankResult> out = noOp.rerank("q", List.of("a", "b", "c"));
        assertEquals(3, out.size());
        // 每个原候选位置一一对应（不重排）
        assertEquals(0, out.get(0).index());
        assertEquals(1, out.get(1).index());
        assertEquals(2, out.get(2).index());
        // 分数严格递减：1.0、0.999、0.998
        for (int i = 0; i < out.size(); i++) {
            assertEquals(1.0 - i * 0.001, out.get(i).score(), 1e-9);
        }
        // 排序后首项分数最高、末项最低
        assertTrue(out.get(0).score() > out.get(out.size() - 1).score());
    }

    @Test
    void singleCandidateKeepsUniformTopScore() {
        List<RerankingService.RerankResult> out = noOp.rerank("q", List.of("only"));
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).index());
        assertEquals(1.0, out.get(0).score(), 1e-9);
    }
}