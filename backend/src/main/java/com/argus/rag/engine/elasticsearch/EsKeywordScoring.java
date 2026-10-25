package com.argus.rag.engine.elasticsearch;

import org.springframework.util.StringUtils;

/**
 * Elasticsearch 关键词检索的纯计算工具。
 * <p>
 * 从 {@code ElasticsearchChunkIndexService} 拆出的无状态静态方法，不依赖任何 IO、
 * HTTP 客户端或可配字段，可供单元测试直接锁定行为。
 * </p>
 */
final class EsKeywordScoring {

    /** 关键词分数归一化的参考基准值（rawScore=100 时归一化约 1.0） */
    private static final double KEYWORD_SCORE_REFERENCE = 100D;

    private EsKeywordScoring() {
    }

    /**
     * 将 ES 原始相关性分数归一化到 [0, 1] 区间。
     * <p>
     * 对数变换 {@code log1p(x)} 压缩分数范围：{@code min(1.0, log1p(rawScore) / log1p(KEYWORD_SCORE_REFERENCE))}。
     * rawScore≤0 返回 0；rawScore=100 时约 1.0，rawScore=10 时约 0.5。
     *
     * @param rawScore ES 返回的原始 _score（非负）
     * @return 归一化后的分数 [0, 1]
     */
    static double normalizeRaw(double rawScore) {
        if (rawScore <= 0D) {
            return 0D;
        }
        return Math.min(1D, Math.log1p(rawScore) / Math.log1p(KEYWORD_SCORE_REFERENCE));
    }

    /**
     * 归一化关键词文本：合并连续空白、去首尾，超过 120 字符则截断并追加 "..."。
     *
     * @param text 原始文本
     * @return 归一化后的文本（空输入返回空串；最长 123 字符）
     */
    static String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}