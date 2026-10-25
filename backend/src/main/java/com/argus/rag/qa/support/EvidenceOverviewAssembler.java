package com.argus.rag.qa.support;

import com.argus.rag.common.util.MetadataReaders;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.qa.model.vo.QaRecordDetailVO;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 证据覆盖概览组装器。
 * <p>
 * 将检索返回的原始证据按文档聚合，生成文档数、证据数、检索模式、检索来源和切片摘要，
 * 供前端解释“本次回答实际看到了哪些内容”。
 * </p>
 *
 * <p><b>这是"可解释性"的产品落点</b>：用户问"AI 你凭什么这么答？"，
 * 前端展示的"参考了 3 个文档、共 5 个片段"卡片就是本类拼的。
 * 每个片段摘要截断到 180 字——概览只要"够判断"，全文在引用详情里，
 * 这是信息分层展示的典型做法。</p>
 */
@Component
public class EvidenceOverviewAssembler {

    private static final String DEFAULT_COVERAGE_MODE = "RELEVANCE_ONLY";
    private static final int SNIPPET_MAX_LENGTH = 180;

    /**
     * 将检索证据组装成覆盖概览。
     *
     * @param documents 检索返回的证据文档
     * @return 证据覆盖概览；无证据时返回 {@code null}
     */
    public AskQuestionResponse.EvidenceOverview assemble(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        String coverageMode = readCoverageMode(documents);
        Map<String, GroupAccumulator> groups = new LinkedHashMap<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            Long documentId = readLong(metadata, "documentId");
            String fileName = readFileName(metadata);
            String key = documentId != null ? "document:" + documentId : "file:" + fileName;
            GroupAccumulator group = groups.computeIfAbsent(
                    key,
                    ignored -> new GroupAccumulator(documentId, fileName));
            group.add(toSnippet(document));
        }

        List<AskQuestionResponse.DocumentEvidenceGroup> documentGroups = groups.values().stream()
                .map(GroupAccumulator::toGroup)
                .toList();
        return new AskQuestionResponse.EvidenceOverview(
                documentGroups.size(),
                documents.size(),
                coverageMode,
                documentGroups,
                buildWarnings(coverageMode, documentGroups.size()));
    }

    /** 根据持久化的问答引用快照，组装出与实时回答一致的证据概览结构。 */
    public AskQuestionResponse.EvidenceOverview assembleHistoryCitations(List<QaRecordDetailVO.Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        Map<String, GroupAccumulator> groups = new LinkedHashMap<>();
        for (QaRecordDetailVO.Citation citation : citations) {
            Long documentId = citation.documentId();
            String fileName = sanitizeFileName(citation.fileName());
            String key = documentId != null ? "document:" + documentId : "file:" + fileName;
            GroupAccumulator group = groups.computeIfAbsent(
                    key,
                    ignored -> new GroupAccumulator(documentId, fileName));
            group.add(toSnippet(citation));
        }

        List<AskQuestionResponse.DocumentEvidenceGroup> documentGroups = groups.values().stream()
                .map(GroupAccumulator::toGroup)
                .toList();
        return new AskQuestionResponse.EvidenceOverview(
                documentGroups.size(),
                citations.size(),
                "HISTORY_SNAPSHOT",
                documentGroups,
                List.of());
    }

    private AskQuestionResponse.EvidenceSnippet toSnippet(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new AskQuestionResponse.EvidenceSnippet(
                readText(metadata, "evidenceId"),
                readLong(metadata, "chunkId"),
                readInteger(metadata, "chunkIndex"),
                readInteger(metadata, "startChunkIndex"),
                readInteger(metadata, "endChunkIndex"),
                readScore(metadata),
                readTextOrDefault(metadata, "retrievalSource", "UNKNOWN"),
                summarize(document.getText()));
    }

    private AskQuestionResponse.EvidenceSnippet toSnippet(QaRecordDetailVO.Citation citation) {
        return new AskQuestionResponse.EvidenceSnippet(
                null,
                citation.chunkId(),
                citation.chunkIndex(),
                citation.startChunkIndex(),
                citation.endChunkIndex(),
                citation.score() == null ? 0D : citation.score(),
                StringUtils.hasText(citation.retrievalSource()) ? citation.retrievalSource().trim() : "UNKNOWN",
                summarize(citation.snippet()));
    }

    private String readCoverageMode(List<Document> documents) {
        for (Document document : documents) {
            String coverageMode = readText(document.getMetadata(), "coverageMode");
            if (StringUtils.hasText(coverageMode)) {
                return coverageMode;
            }
        }
        return DEFAULT_COVERAGE_MODE;
    }

    private static List<String> buildWarnings(String coverageMode, int documentCount) {
        if ("DOCUMENT_COVERAGE".equals(coverageMode) && documentCount <= 1) {
            return List.of("当前问题启用了跨文档覆盖检索，但本次证据仅覆盖 1 个文档。");
        }
        return List.of();
    }

    /** 对证据摘要文本做归一化供前端展示：剥掉开头的「文件名：…」行，折叠空白后截到上限。 */
    private static String summarize(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replaceFirst("^文件名：[^\\n]*\\n", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= SNIPPET_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_MAX_LENGTH) + "...";
    }

    private static Long readLong(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private static Integer readInteger(Map<String, Object> metadata, String key) {
        return MetadataReaders.intValue(metadata, key);
    }

    private static double readScore(Map<String, Object> metadata) {
        return MetadataReaders.score(metadata);
    }

    private static String readTextOrDefault(Map<String, Object> metadata, String key, String fallback) {
        String text = readText(metadata, key);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private static String readText(Map<String, Object> metadata, String key) {
        return MetadataReaders.text(metadata, key);
    }

    private static String readFileName(Map<String, Object> metadata) {
        String fileName = readText(metadata, "fileName");
        if (StringUtils.hasText(fileName)) {
            return fileName;
        }
        String documentName = readText(metadata, "documentName");
        return StringUtils.hasText(documentName) ? documentName : "未知文档";
    }

    private static String sanitizeFileName(String fileName) {
        return StringUtils.hasText(fileName) ? fileName.trim() : "Unknown document";
    }

    private static final class GroupAccumulator {
        private final Long documentId;
        private final String fileName;
        private final List<AskQuestionResponse.EvidenceSnippet> snippets = new ArrayList<>();
        private final Set<String> retrievalSources = new LinkedHashSet<>();
        private double topScore;

        private GroupAccumulator(Long documentId, String fileName) {
            this.documentId = documentId;
            this.fileName = fileName;
        }

        private void add(AskQuestionResponse.EvidenceSnippet snippet) {
            snippets.add(snippet);
            topScore = Math.max(topScore, snippet.score());
            retrievalSources.add(snippet.retrievalSource());
        }

        private AskQuestionResponse.DocumentEvidenceGroup toGroup() {
            return new AskQuestionResponse.DocumentEvidenceGroup(
                    documentId,
                    fileName,
                    snippets.size(),
                    topScore,
                    List.copyOf(retrievalSources),
                    List.copyOf(snippets));
        }
    }
}
