package com.argus.rag.qa.support;

import com.argus.rag.qa.model.vo.AskQuestionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CitationAssembler} 引用来源组装契约（纯 JVM/零 mock/零网络）。
 * 从 Spring AI Document 的元数据提取引用：按 fileName 去重（保留首次命中、保持插入序）；
 * 缺文件名 → 弃；score 缺省 0；非 Number 元数据 → null；fileName 缺失回退 documentName 且 trim。
 */
@DisplayName("引用来源组装（CitationAssembler）")
class CitationAssemblerTest {

    private final CitationAssembler assembler = new CitationAssembler();

    private static Document doc(Object... kv) {
        Map<String, Object> meta = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            meta.put((String) kv[i], kv[i + 1]);
        }
        return new Document("正文", meta);
    }

    @Test
    void nullOrEmptyReturnsEmpty() {
        assertTrue(assembler.assembleDocuments(null).isEmpty());
        assertTrue(assembler.assembleDocuments(List.of()).isEmpty());
    }

    @Test
    void missingFileNameIsDropped() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("documentId", 1L)));
        assertTrue(out.isEmpty());
    }

    @Test
    void populatedCitationCarriesAllNumericMetadata() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("fileName", "报表.xlsx", "documentId", 10L, "chunkId", 99L,
                        "chunkIndex", 3, "groupId", 7L, "score", 0.5)));
        assertEquals(1, out.size());
        AskQuestionResponse.Citation c = out.get(0);
        assertEquals(10L, c.documentId());
        assertEquals(99L, c.chunkId());
        assertEquals(3, c.chunkIndex());
        assertEquals(7L, c.groupId());
        assertEquals("报表.xlsx", c.fileName());
        assertEquals(0.5, c.score());
        assertNull(c.snippet());
    }

    @Test
    void fileNameFallsBackToDocumentNameAndTrims() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("documentName", "  备注.pdf  ")));
        assertEquals("备注.pdf", out.get(0).fileName());
    }

    @Test
    void dedupeByFileNameKeepsFirst() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("fileName", "同.xlsx", "documentId", 1L),
                doc("fileName", "同.xlsx", "documentId", 2L)));
        assertEquals(1, out.size());
        assertEquals(1L, out.get(0).documentId());
    }

    @Test
    void missingScoreDefaultsZero() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("fileName", "a.pdf")));
        assertEquals(0.0, out.get(0).score());
    }

    @Test
    void nonNumericDocumentIdBecomesNull() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("fileName", "a.pdf", "documentId", "abc", "chunkIndex", "not-int", "score", "zz")));
        AskQuestionResponse.Citation c = out.get(0);
        assertNull(c.documentId());
        assertNull(c.chunkIndex());
        assertEquals(0.0, c.score());
    }

    @Test
    void orderPreservedAcrossDistinctFiles() {
        List<AskQuestionResponse.Citation> out = assembler.assembleDocuments(List.of(
                doc("fileName", "b.pdf"),
                doc("fileName", "a.pdf")));
        assertEquals("b.pdf", out.get(0).fileName());
        assertEquals("a.pdf", out.get(1).fileName());
    }
}