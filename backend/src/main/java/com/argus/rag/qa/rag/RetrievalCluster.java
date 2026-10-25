package com.argus.rag.qa.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索类簇，代表同一文档中连续切片的聚合组。
 * <p>
 * 将连续的切片合并为一个证据单元，跟踪主要候选项（评分最高者）和切片范围。
 * 从 {@code HybridChunkRetrievalService} 拆出的包内数据类，语义与可见性与原嵌套
 * {@code static final class RetrievalCluster} 完全一致，仅将 class 由嵌套改为顶层
 * 同包（package-private）。
 * </p>
 */
final class RetrievalCluster {

    /** 所属文档 ID */
    private final Long documentId;
    /** 类簇内的所有候选切片 */
    private final List<RetrievalCandidate> members = new ArrayList<>();
    /** 主要候选项（评分最高的切片） */
    private RetrievalCandidate primaryCandidate;
    /** 类簇的起始切片序号 */
    private int startChunkIndex;
    /** 类簇的结束切片序号 */
    private int endChunkIndex;
    /** 类簇的 RRF 融合评分（取成员最大值） */
    private double rankingScore;
    /** 类簇的向量检索评分（取成员最大值） */
    private double vectorScore;
    /** 类簇的关键词检索评分（取成员最大值） */
    private double keywordScore;
    /** 类簇是否包含向量检索命中的成员 */
    private boolean hasVectorSource;
    /** 类簇是否包含关键词检索命中的成员 */
    private boolean hasKeywordSource;

    RetrievalCluster(RetrievalCandidate seed) {
        this.documentId = seed.documentId();
        this.startChunkIndex = seed.chunkIndex();
        this.endChunkIndex = seed.chunkIndex();
        add(seed);
    }

    /** 判断候选切片是否与当前类簇连续（同文档且序号相邻） */
    boolean isContinuousWith(RetrievalCandidate candidate) {
        return documentId.equals(candidate.documentId()) && candidate.chunkIndex() == endChunkIndex + 1;
    }

    /** 添加候选切片到类簇，更新范围、评分和主要候选项 */
    void add(RetrievalCandidate candidate) {
        members.add(candidate);
        endChunkIndex = Math.max(endChunkIndex, candidate.chunkIndex());
        startChunkIndex = Math.min(startChunkIndex, candidate.chunkIndex());
        rankingScore = Math.max(rankingScore, candidate.rankingScore());
        vectorScore = Math.max(vectorScore, candidate.vectorScore());
        keywordScore = Math.max(keywordScore, candidate.keywordScore());
        hasVectorSource = hasVectorSource || "VECTOR".equals(candidate.source())
                || "BOTH".equals(candidate.source());
        hasKeywordSource = hasKeywordSource || "KEYWORD".equals(candidate.source())
                || "BOTH".equals(candidate.source());
        if (primaryCandidate == null
                || candidate.rankingScore() > primaryCandidate.rankingScore()
                || (candidate.rankingScore() == primaryCandidate.rankingScore()
                        && candidate.chunkIndex() < primaryCandidate.chunkIndex())) {
            primaryCandidate = candidate;
        }
    }

    Long documentId() {
        return documentId;
    }

    Long primaryChunkId() {
        return primaryCandidate.chunkId();
    }

    Integer primaryChunkIndex() {
        return primaryCandidate.chunkIndex();
    }

    double rankingScore() {
        return rankingScore;
    }

    double vectorScore() {
        return vectorScore;
    }

    double keywordScore() {
        return keywordScore;
    }

    /** 获取扩展后的起始切片序号（向前扩展 neighborWindow 个切片，最小为 0） */
    int expandedStartChunkIndex(int neighborWindow) {
        return Math.max(0, startChunkIndex - Math.max(0, neighborWindow));
    }

    /** 获取扩展后的结束切片序号（向后扩展 neighborWindow 个切片） */
    int expandedEndChunkIndex(int neighborWindow) {
        return endChunkIndex + Math.max(0, neighborWindow);
    }

    /** 获取类簇的检索来源：BOTH（双通道）、VECTOR 或 KEYWORD */
    String source() {
        if (hasVectorSource && hasKeywordSource) {
            return "BOTH";
        }
        return hasVectorSource ? "VECTOR" : "KEYWORD";
    }
}