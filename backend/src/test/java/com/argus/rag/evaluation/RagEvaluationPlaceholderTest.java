package com.argus.rag.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link RagEvaluationPlaceholder} RAG 评估占位实现契约（纯 JVM/零 mock/零 IO）。
 * evaluate 对任意输入（空/单条/多条/含结构用例）恒返回固定骨架 EvalResult(0.0,0.0,false)，
 * 是 fail-fast 的未实现标记而非静默假指标；EvalCase/EvalResult 两个 record 正确承字段。
 */
@DisplayName("RAG 评估占位（RagEvaluationPlaceholder）")
class RagEvaluationPlaceholderTest {

    private final RagEvaluationPlaceholder placeholder = new RagEvaluationPlaceholder();

    private static RagEvaluationPlaceholder.EvalCase aCase() {
        return new RagEvaluationPlaceholder.EvalCase(
                "谁是报表的负责人", List.of("chunk-1", "chunk-2"), "财务负责人是张三");
    }

    @Test
    void evaluateEmptySetReturnsSkeletonResult() {
        RagEvaluationPlaceholder.EvalResult r = placeholder.evaluate(List.of());
        assertEquals(0.0, r.retrievalHitRate());
        assertEquals(0.0, r.mrr());
        assertFalse(r.answerCorrect());
    }

    @Test
    void evaluateSingleCaseStillReturnsSkeleton() {
        RagEvaluationPlaceholder.EvalResult r = placeholder.evaluate(List.of(aCase()));
        assertEquals(0.0, r.retrievalHitRate());
        assertEquals(0.0, r.mrr());
        assertFalse(r.answerCorrect());
    }

    @Test
    void evaluateNullSetDoesNotThrowAndReturnsSkeleton() {
        RagEvaluationPlaceholder.EvalResult r = placeholder.evaluate(null);
        assertEquals(0.0, r.retrievalHitRate());
        assertEquals(0.0, r.mrr());
        assertFalse(r.answerCorrect());
    }

    @Test
    void evalCaseRecordCarriesAllFields() {
        RagEvaluationPlaceholder.EvalCase c = aCase();
        assertEquals("谁是报表的负责人", c.question());
        assertEquals(List.of("chunk-1", "chunk-2"), c.expectedChunks());
        assertEquals("财务负责人是张三", c.groundTruth());
    }
}