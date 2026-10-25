package com.argus.rag.qa.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索类簇构建器：将排好序的候选切片聚合为连续类簇。
 *
 * <p>从 {@link HybridChunkRetrievalService} 中提取的纯逻辑方法，不持有任何 Spring 依赖。</p>
 */
final class RetrievalClusterBuilder {

    private RetrievalClusterBuilder() {
    }

    /**
     * 将候选切片聚合为类簇：同一文档中连续的切片合并为一个类簇，
     * 然后按 RRF 评分降序排列。
     */
    static List<RetrievalCluster> buildClusters(List<RetrievalCandidate> rankedCandidates) {
        Map<Long, List<RetrievalCandidate>> candidatesByDocumentId = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : rankedCandidates) {
            candidatesByDocumentId.computeIfAbsent(candidate.documentId(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<RetrievalCluster> clusters = new ArrayList<>();
        for (List<RetrievalCandidate> sameDocumentCandidates : candidatesByDocumentId.values()) {
            List<RetrievalCandidate> sortedByChunkIndex = sameDocumentCandidates.stream()
                    .sorted(Comparator.comparing(RetrievalCandidate::chunkIndex))
                    .toList();
            RetrievalCluster currentCluster = null;
            for (RetrievalCandidate candidate : sortedByChunkIndex) {
                if (currentCluster == null || !currentCluster.isContinuousWith(candidate)) {
                    currentCluster = new RetrievalCluster(candidate);
                    clusters.add(currentCluster);
                    continue;
                }
                currentCluster.add(candidate);
            }
        }
        return clusters.stream()
                .sorted(Comparator
                        .comparingDouble(RetrievalCluster::rankingScore).reversed()
                        .thenComparing(RetrievalCluster::primaryChunkId))
                .toList();
    }
}
