package com.argus.rag.engine.reranking;

import java.util.Comparator;
import java.util.List;

/**
 * 重排序（Reranking）服务：用于提升检索的精确度。
 * <p>
 * 完成初稿召回（向量检索 + 关键词检索）之后，再对候选结果做一轮重排：用
 * 交叉编码器（Cross-Encoder）模型对候选与问题重新打分，优化相关性的先后顺序。
 * 这里刻意把「召回数量 K」(Recall K，取大，保证覆盖面) 与「进入上下文的 K
 * （Context K，取小，保证精度）」分开处理。
 * </p>
 */
public interface RerankingService {

    /**
     * 对一批候选文本做重排打分。
     *
     * @param query      用户的原始问题
     * @param candidates 待重排的候选文本列表
     * @return 已按相关度降序排好序的候选结果（附带打分）
     */
    List<RerankResult> rerank(String query, List<String> candidates);

    record RerankResult(int index, double score) {}

    /**
     * 空实现（NoOp）：不真正打分，直接把原始顺序返回，并给统一的均匀分数。
     * 用于未配置任何重排序模型时的兜底，保证链路不因缺模型而中断。
     */
    class NoOp implements RerankingService {
        @Override
        public List<RerankResult> rerank(String query, List<String> candidates) {
            if (candidates == null || candidates.isEmpty()) return List.of();
            return java.util.stream.IntStream.range(0, candidates.size())
                    // 每个候选给一个「随下标略降」的分数（1.0, 0.999, 0.998…）：
                    // 幅度极小不足以改变相对顺序，但能让下游拿到"有分可排序"的结果，同时稳定保留原始召回顺序。
                    .mapToObj(i -> new RerankResult(i, 1.0 - i * 0.001))
                    .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                    .toList();
        }
    }
}