package com.argus.rag.qa.rag;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据文档组装器：将检索候选、数据库行和类簇信息组装为 Spring AI {@link Document}。
 *
 * <p>从 {@link HybridChunkRetrievalService} 中提取的文档组装与证据窗口构建逻辑。</p>
 *
 * <p><b>父子分块（parent-document retrieval）</b>：检索用小切片命中后，本类按
 * 命中切片的 {@code sectionPath}（分块时埋进 metadataJson 的章节标题路径）取<b>同一章节
 * 的全部切片</b>拼成完整证据，而非固定邻居窗口 ±1。语义完整的整节是抗"断章取义型
 * 幻觉"的关键——"报销上限 5000 元"和它所属章节的上下文一起进大模型，才不会被
 * 单独拎出来误读。整节无标题（sectionPath 空）或整节过长（超 {@link #PARENT_SECTION_MAX_CHARS}）
 * 时退化为历史邻居窗口行为，保证不超上下文、向后兼容。</p>
 */
final class EvidenceDocumentAssembler {

    /** 父子分块取整节的字符上限：同节文本拼接超过此值即退化为邻居窗口，避免稀释注意力/超上下文。 */
    private static final int PARENT_SECTION_MAX_CHARS = 3000;

    /** 解析 metadataJson（含 sectionPath）用的 Jackson 只读树解析器（ObjectMapper 线程安全）。 */
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

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

    /** 构建证据窗口文本：优先父子分块取整节，退化为邻居窗口 */
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
        // 父子分块：命中切片所属章节非空且整节不超限 → 返回同节全部切片（语义完整优先）
        String sectionEvidence = buildSectionEvidence(fileName, chunks, cluster.primaryChunkIndex());
        if (sectionEvidence != null) {
            return sectionEvidence;
        }
        // 退化为历史邻居窗口：无章节标题或整节过长时，取命中切片前后各 neighborWindow 个切片
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

    /**
     * 父子分块取整节：命中切片所属章节（sectionPath）非空且同节文本总长不超上限时，
     * 返回按 chunkIndex 升序拼接的整节证据；否则返回 null（调用方退化为邻居窗口）。
     */
    private String buildSectionEvidence(String fileName, List<DocumentChunkEntity> chunks, int primaryIndex) {
        String sectionPath = resolveSectionPathOf(chunks, primaryIndex);
        if (sectionPath == null || sectionPath.isBlank()) {
            return null;
        }
        List<DocumentChunkEntity> sectionChunks = chunks.stream()
                .filter(c -> c.getChunkIndex() != null)
                .filter(c -> sectionPath.equals(resolveSectionPathOf(c)))
                .filter(c -> StringUtils.hasText(c.getChunkText()))
                .sorted(Comparator.comparing(DocumentChunkEntity::getChunkIndex))
                .toList();
        if (sectionChunks.isEmpty()) {
            return null;
        }
        int totalChars = 0;
        for (DocumentChunkEntity chunk : sectionChunks) {
            totalChars += chunk.getChunkText().length();
            if (totalChars > PARENT_SECTION_MAX_CHARS) {
                return null;
            }
        }
        StringBuilder builder = new StringBuilder("文件名：" + fileName);
        for (DocumentChunkEntity chunk : sectionChunks) {
            builder.append("\n").append(chunk.getChunkText().trim());
        }
        return builder.toString();
    }

    /** 解析指定 chunkIndex 切片所属章节路径；未找到或缺失返回空串。 */
    private String resolveSectionPathOf(List<DocumentChunkEntity> chunks, int chunkIndex) {
        for (DocumentChunkEntity chunk : chunks) {
            if (chunk.getChunkIndex() != null && chunk.getChunkIndex() == chunkIndex) {
                return resolveSectionPathOf(chunk);
            }
        }
        return "";
    }

    /** 从单切片的 metadataJson 里读取 sectionPath；解析失败/缺失返回空串（fail-soft）。 */
    private String resolveSectionPathOf(DocumentChunkEntity chunk) {
        String json = chunk.getMetadataJson();
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode node = METADATA_MAPPER.readTree(json);
            JsonNode sectionPath = node.get("sectionPath");
            return sectionPath == null || sectionPath.isNull() ? "" : sectionPath.asText();
        } catch (Exception ignored) {
            return "";
        }
    }
}
