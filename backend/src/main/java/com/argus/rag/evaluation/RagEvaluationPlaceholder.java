package com.argus.rag.evaluation;

import org.springframework.stereotype.Component;
import java.util.List;

/**
 * RAG 评估骨架（占位）。
 *
 * <p>未来的 RAG 评估指标集成点：召回命中率（retrieval hit rate）、MRR、
 * 答案正确性（answer correctness）、幻觉检测（hallucination detection）。
 *
 * <p>现阶段仅提供离线评估集（test set）管理的骨架。</p>
 */
@Component
public class RagEvaluationPlaceholder {

    public record EvalCase(String question, List<String> expectedChunks, String groundTruth) {}

    public record EvalResult(double retrievalHitRate, double mrr, boolean answerCorrect) {}

    /** 对一个测试集跑一轮评估（当前为骨架，未真正实现）。 */
    public EvalResult evaluate(List<EvalCase> cases) {
        return new EvalResult(0.0, 0.0, false);
    }
}