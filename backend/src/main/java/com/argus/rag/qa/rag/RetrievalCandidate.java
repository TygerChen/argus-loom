package com.argus.rag.qa.rag;

import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import com.argus.rag.qa.support.RrfFusion;

/**
 * 检索候选项，代表一个切片在检索结果中的命中信息。
 * <p>
 * 记录该切片在向量检索和关键词检索中的命中情况、各通道评分和 RRF 融合评分。
 * 从 {@code HybridChunkRetrievalService} 拆出的包内数据类，语义与可见性与原嵌套
 * {@code static final class RetrievalCandidate} 完全一致，仅将 class 由嵌套改为顶层
 * 顶层同包（package-private）。
 * </p>
 */
final class RetrievalCandidate {

    /** 所属文档 ID */
    private final Long documentId;
    /** 切片 ID */
    private final Long chunkId;
    /** 切片在文档中的序号 */
    private final Integer chunkIndex;
    /** 向量检索评分（取多次命中的最大值） */
    private double vectorScore;
    /** 关键词检索评分（取多次命中的最大值） */
    private double keywordScore;
    /** RRF 融合评分（累加值） */
    private double rankingScore;
    /** 重排序后评分（由 Cross-Encoder 计算） */
    Double rerankScore;
    /** 切片内容文本 */
    String chunkText;
    /** 是否在向量检索中命中（同包编排服务直接读） */
    boolean vectorMatched;
    /** 是否在关键词检索中命中（同包编排服务直接读） */
    boolean keywordMatched;

    private RetrievalCandidate(Long documentId, Long chunkId, Integer chunkIndex) {
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex;
    }

    /** 从向量检索命中结果创建候选项 */
    static RetrievalCandidate fromVectorHit(PgVectorRetrievalAdapter.VectorHit hit) {
        return new RetrievalCandidate(hit.documentId(), hit.chunkId(), hit.chunkIndex());
    }

    /** 从关键词检索命中结果创建候选项 */
    static RetrievalCandidate fromKeywordHit(ElasticsearchChunkIndexService.KeywordHit hit) {
        return new RetrievalCandidate(hit.documentId(), hit.chunkId(), hit.chunkIndex());
    }

    /** 合并向量检索命中：更新评分并累加 RRF 分数 */
    void mergeVectorHit(PgVectorRetrievalAdapter.VectorHit hit, int rank) {
        this.vectorMatched = true;
        this.vectorScore = Math.max(this.vectorScore, hit.score());
        this.rankingScore += RrfFusion.rankScore(rank);
    }

    /** 合并关键词检索命中：更新评分并累加 RRF 分数 */
    void mergeKeywordHit(ElasticsearchChunkIndexService.KeywordHit hit, int rank) {
        this.keywordMatched = true;
        this.keywordScore = Math.max(this.keywordScore, hit.normalizedScore());
        this.rankingScore += RrfFusion.rankScore(rank);
    }

    Long documentId() {
        return documentId;
    }

    Long chunkId() {
        return chunkId;
    }

    Integer chunkIndex() {
        return chunkIndex;
    }

    double vectorScore() {
        return vectorScore;
    }

    double keywordScore() {
        return keywordScore;
    }

    double rankingScore() {
        return rankingScore;
    }

    /** 获取检索来源：BOTH（双通道）、VECTOR 或 KEYWORD */
    String source() {
        if (vectorMatched && keywordMatched) {
            return "BOTH";
        }
        return vectorMatched ? "VECTOR" : "KEYWORD";
    }
}