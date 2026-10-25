package com.argus.rag.ingestion.vector;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VectorIngestionService} 向量写入契约（mock Spring AI {@link VectorStore} + 真实 ObjectMapper
 * + 真实 FilterExpressionBuilder，纯 JVM/零 DB/零嵌入模型）：
 * - ingestChunks null/空→直接返回不写库；chunk 落库守卫（id/documentId/chunkIndex/文本）BusinessException；
 * - 稳定 UUID id（documentId:chunkIndex）、元数据含 groupId/documentId/chunkId/chunkIndex + 可选 fileName；
 * - 写前按文档维度删除旧向量（去重）、再按 addBatchSize 分批 add；
 * - deleteDocumentVectors null/<=0 短路、合法用 eq(documentId) 过滤删除；
 * - embedAndStore 空→跳过、按批次拆 submit（9/自定义）add；
 * - 可选元数据从 metadataJson 提取 fileName（优先于 documentName、trim）、null/空/坏 JSON→忽略。
 */
@DisplayName("向量写入（VectorIngestionService）")
class VectorIngestionServiceTest {

    private VectorStore vectorStore;
    private VectorIngestionService service;
    private VectorIngestionService smallService;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        service = new VectorIngestionService(vectorStore);            // 默认批次 9
        smallService = new VectorIngestionService(vectorStore, 3);    // 批次 3
    }

    // ---------- helpers ----------

    private static DocumentChunkEntity chunk(Long id, Long documentId, Integer chunkIndex, String text,
                                             String metadataJson) {
        DocumentChunkEntity c = new DocumentChunkEntity();
        c.setId(id);
        c.setDocumentId(documentId);
        c.setGroupId(1L);
        c.setChunkIndex(chunkIndex);
        c.setChunkText(text);
        c.setMetadataJson(metadataJson);
        return c;
    }

    // ---------- ingestChunks 守卫 ----------

    @Test
    void ingestChunksSkipsNullOrEmpty() {
        service.ingestChunks(null);
        service.ingestChunks(List.of());
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }

    @Test
    void ingestChunksRejectsChunkWithoutId() {
        List<DocumentChunkEntity> chunks = List.of(chunk(null, 10L, 0, "text", null));
        BusinessException e = assertThrows(BusinessException.class, () -> service.ingestChunks(chunks));
        assertEquals("向量写入前必须先完成 chunk 落库", e.getMessage());
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }

    @Test
    void ingestChunksRejectsMissingDocumentIdOrIndex() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.ingestChunks(List.of(chunk(1L, null, 0, "text", null))));
        assertEquals("向量写入前必须提供 documentId 和 chunkIndex", e.getMessage());

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> service.ingestChunks(List.of(chunk(1L, 2L, null, "text", null))));
        assertEquals("向量写入前必须提供 documentId 和 chunkIndex", e2.getMessage());
    }

    @Test
    void ingestChunksRejectsBlankText() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.ingestChunks(List.of(chunk(1L, 2L, 0, "   ", null))));
        assertEquals("空切片不能写入向量库", e.getMessage());
    }

    // ---------- ingestChunks 成功路径 ----------

    @Test
    void ingestChunksDeletesByDeduplicatedDocumentIds() {
        service.ingestChunks(List.of(chunk(11L, 7L, 0, "文本A", null), chunk(12L, 7L, 1, "文本B", null)));
        // 两个 chunk 都是 documentId 7 → 按文档去重后只删 1 次
        verify(vectorStore, times(1)).delete(any(Filter.Expression.class));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void deduplicatesDocumentIdsBeforeDelete() {
        service.ingestChunks(List.of(
                chunk(1L, 7L, 0, "a", null),
                chunk(2L, 7L, 1, "b", null),
                chunk(3L, 8L, 0, "c", null)));
        verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
    }

    @Test
    void stableIdIsDeterministicUuidOfDocColonIndex() {
        String expected = UUID.nameUUIDFromBytes("7:0".getBytes()).toString();
        service.ingestChunks(List.of(chunk(99L, 7L, 0, "x", null)));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals(expected, captor.getValue().get(0).getId());
    }

    @Test
    void documentMetadataCarriesCoreFields() {
        service.ingestChunks(List.of(chunk(99L, 7L, 3, "x", null)));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Map<String, Object> md = captor.getValue().get(0).getMetadata();
        assertEquals(1L, md.get("groupId"));
        assertEquals(7L, md.get("documentId"));
        assertEquals(99L, md.get("chunkId"));
        assertEquals(3, md.get("chunkIndex"));
        assertEquals("x", captor.getValue().get(0).getText());
    }

    // ---------- 可选元数据 fileName ----------

    @Test
    void extractOptionalMetadataPrefersFileNameAndTrims() {
        service.ingestChunks(List.of(chunk(1L, 7L, 0, "x", "{\"fileName\":\" 报表.xlsx \",\"k\":1}")));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Map<String, Object> md = captor.getValue().get(0).getMetadata();
        assertEquals("报表.xlsx", md.get("fileName"));
    }

    @Test
    void extractOptionalMetadataFallsBackToDocumentName() {
        service.ingestChunks(List.of(chunk(1L, 7L, 0, "x", "{\"fileName\":\"\",\"documentName\":\"  对账单 \"}")));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals("对账单", captor.getValue().get(0).getMetadata().get("fileName"));
    }

    @Test
    void extractOptionalMetadataIgnoresBlankOrBadJson() {
        service.ingestChunks(List.of(chunk(1L, 7L, 0, "x", "   ")));
        service.ingestChunks(List.of(chunk(2L, 7L, 1, "x", "not-json")));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        for (List<Document> batch : captor.getAllValues()) {
            assertTrue(batch.get(0).getMetadata().get("fileName") == null);
        }
    }

    // ---------- 删旧 + 分批写 ----------

    @Test
    void deleteDocumentVectorsSkipsNullOrNonPositive() {
        service.deleteDocumentVectors(null);
        service.deleteDocumentVectors(0L);
        service.deleteDocumentVectors(-3L);
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }

    @Test
    void deleteDocumentVectorsDeletesWithEqFilter() {
        service.deleteDocumentVectors(9L);
        verify(vectorStore).delete(any(Filter.Expression.class));
    }

    @Test
    void embedAndStoreSkipsEmpty() {
        service.embedAndStore(List.of());
        service.embedAndStore(null);
        verify(vectorStore, never()).add(any());
    }

    @Test
    void embedAndStoreSplitsIntoDefaultBatchesOfNine() {
        List<Document> docs = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> Document.builder().id("d" + i).text("t" + i).build()).toList();
        service.embedAndStore(docs);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        assertEquals(9, captor.getAllValues().get(0).size());
        assertEquals(6, captor.getAllValues().get(1).size());
    }

    @Test
    void embedAndStoreSplitsPerConfiguredBatchSize() {
        List<Document> docs = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> Document.builder().id("d" + i).text("t" + i).build()).toList();
        smallService.embedAndStore(docs);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        assertEquals(3, captor.getAllValues().get(0).size());
        assertEquals(2, captor.getAllValues().get(1).size());
    }

    @Test
    void configuredBatchSizeNonPositiveFallsBackToNine() {
        VectorIngestionService svc = new VectorIngestionService(vectorStore, 0);
        List<Document> docs = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> (Document) Document.builder().id("d" + i).text("t" + i).build())
                .toList();
        svc.embedAndStore(docs);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        assertEquals(9, captor.getAllValues().get(0).size());
        assertEquals(1, captor.getAllValues().get(1).size());
    }
}