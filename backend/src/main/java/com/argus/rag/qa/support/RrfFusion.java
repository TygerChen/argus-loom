package com.argus.rag.qa.support;

/**
 * Reciprocal Rank Fusion 倒数排名融合（RRF）纯函数集合。
 *
 * <p>RRF 用各通道返回的排名而非原始分数做融合：分数 1/(k+rank)，对不同召回通道
 * （向量 / 关键词）天然同量纲、无需归一化。常参数 {@code k} 越小，越高排名的权重越突出。</p>
 *
 * <p>从 {@link com.argus.rag.qa.rag.HybridChunkRetrievalService} 内嵌入内部候选类递归，
 * 提取为无副作用静态组件，便于独立单测与复用。</p>
 */
public final class RrfFusion {

    /** 默认融合常数 k（与原实现一致：k=0 即精确 1/rank）。 */
    public static final int DEFAULT_K = 0;

    private RrfFusion() {
    }

    /** {@code rank}（从 1 计）对应的 RRF 得分，使用默认融合常数。 */
    public static double rankScore(int rank) {
        return rankScore(rank, DEFAULT_K);
    }

    /**
     * {@code rank}（从 1 计）对应的 RRF 得分：{@code 1 / (k + max(rank, 1))}。
     *
     * @param rank 通道内排名（1 起，0/负按 1 处理）
     * @param k    融合常数（>=0）
     * @return (0, 1] 区间分数，rank 越小分数越高
     */
    public static double rankScore(int rank, int k) {
        return 1D / (Math.max(k, 0) + Math.max(rank, 1));
    }
}