package com.argus.rag.qa.rag;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据文档组装器：将检索候选、数据库行和类簇信息组装为 Spring AI {@link Document}。
 *
 * <p>从 {@link HybridChunkRetrievalService} 中提取的文档组装与证据窗口构建逻辑。</p>
 */
final class EvidenceDocumentAssembler {

    private final int neighborWindow;
    private final DocumentChunkMapper documentChunkMapper;

    EvidenceDocumentAssembler(int neighborWindow, DocumentChunkMapper documentChunkMapper) {
        this.neighborWindow = neighborWindow;
        this.documentChunkMapper = documentChunkMapper;
    }

    /** 将数据库查询结果按 chunkId 索引，便于快速查找 */
    static Map<Long, Map<String, Object>> indexRows(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> rowByChunkId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            rowByChunkId.put(RetrievalRowGuard.requireLong(RetrievalRowGuard.getValue(row, "chunkId"), "chunkId"), row);
        }
        return rowByChunkId;
    }

    /** 将检索候选和数据库数据组装为 Spring AI 的 Document 对象 */
    Document toDocument(
            String evidenceId,
            Map<String, Object> row,
            RetrievalCluster cluster,
            Map<Long, List<DocumentChunkEntity>> chunkWindowCache,
            String coverageMode) {
        Long documentId = RetrievalRowGuard.requireLong(RetrievalRowGuard.getValue(row, "documentId"), "documentId");
        Integer chunkIndex = RetrievalRowGuard.requireInteger(RetrievalRowGuard.getValue(row, "chunkIndex"), "chunkIndex");
        if (!documentId.equals(cluster.documentId()) || !chunkIndex.equals(cluster.primaryChunkIndex())) {
            throw new BusinessException("检索结果与文档切片不一致");
        }
        Long chunkId = RetrievalRowGuard.requireLong(RetrievalRowGuard.getValue(row, "chunkId"), "chunkId");
        String fileName = RetrievalRowGuard.requireText(RetrievalRowGuard.getValue(row, "fileName"), "fileName");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evidenceId", evidenceId);
        metadata.put("groupId", RetrievalRowGuard.requireLong(RetrievalRowGuard.getValue(row, "groupId"), "groupId"));
        metadata.put("documentId", documentId);
        metadata.put("chunkId", chunkId);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("primaryChunkId", cluster.primaryChunkId());
        metadata.put("primaryChunkIndex", cluster.primaryChunkIndex());
        metadata.put("startChunkIndex", cluster.expandedStartChunkIndex(neighborWindow));
        metadata.put("endChunkIndex", cluster.expandedEndChunkIndex(neighborWindow));
        metadata.put("fileName", fileName);
        double normalizedScore = RetrievalRowGuard.normalizeScore(cluster.rankingScore());
        metadata.put("score", normalizedScore);
        metadata.put("retrievalSource", cluster.source());
        metadata.put("coverageMode", coverageMode);
        metadata.put("vectorScore", cluster.vectorScore());
        metadata.put("keywordScore", cluster.keywordScore());
        metadata.put("hybridScore", cluster.rankingScore());
        String evidenceText = buildEvidenceWindow(row, cluster, chunkWindowCache);
        if (!StringUtils.hasText(evidenceText)) {
            return null;
        }
        return Document.builder()
                .id(evidenceId)
                .text(evidenceText)
                .metadata(metadata)
                .build();
    }

    /** 构建证据窗口文本：根据聚类的切片范围和邻居窗口拼接切片文本 */
    String buildEvidenceWindow(
            Map<String, Object> row,
            RetrievalCluster cluster,
            Map<Long, List<DocumentChunkEntity>> chunkWindowCache) {
        Long groupId = RetrievalRowGuard.requireLong(RetrievalRowGuard.getValue(row, "groupId"), "groupId");
        Long documentId = RetrievalRowGuard.requireLong(RetrievalRowGuard.getValue(row, "documentId"), "documentId");
        String fileName = RetrievalRowGuard.requireText(RetrievalRowGuard.getValue(row, "fileName"), "fileName");
        List<DocumentChunkEntity> chunks = chunkWindowCache.computeIfAbsent(
                documentId,
                ignored -> documentChunkMapper.selectReadyActiveChunksByDocumentId(groupId, documentId));
        if (chunks.isEmpty()) {
            return null;
        }
        int startIndex = cluster.expandedStartChunkIndex(neighborWindow);
        int endIndex = cluster.expandedEndChunkIndex(neighborWindow);
        StringBuilder builder = new StringBuilder();
        for (DocumentChunkEntity chunk : chunks) {
            if (chunk.getChunkIndex() != null
                    && chunk.getChunkIndex() >= startIndex
                    && chunk.getChunkIndex() <= endIndex
                    && StringUtils.hasText(chunk.getChunkText())) {
                if (!builder.isEmpty()) {
                    builder.append("\n");
                }
                builder.append(chunk.getChunkText().trim());
            }
        }
        if (builder.isEmpty()) {
            return null;
        }
        return "文件名：" + fileName + "\n" + builder;
    }
}
