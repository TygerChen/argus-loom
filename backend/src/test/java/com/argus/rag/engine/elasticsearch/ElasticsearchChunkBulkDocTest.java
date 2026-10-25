package com.argus.rag.engine.elasticsearch;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ElasticsearchChunkIndexService} bulk 组装 / 单文档字段 / 字段完整性校验行为锁
 * （纯 JVM、6 参包私有构造 new ElasticsearchChunkIndexService(new ObjectMapper(), null, ...) 注入零 httpClient、
 * 零 ES 网络 IO）。
 * <p>锁契约：buildChunkBulkLines 每 chunk 生成 action+doc 两行 NDJSON、index=配置索引名、_id=chunk id；
 * buildChunkDoc 组装 chunkId/groupId/documentId/chunkIndex/fileName/chunkText+READY+deleted=false；
 * validateChunk 任一必要字段缺失抛「ES 索引写入缺少必要 chunk 字段」。</p>
 */
@DisplayName("ES chunk bulk/文档组装/字段校验（ElasticsearchChunkIndexService 三包可见方法）")
class ElasticsearchChunkBulkDocTest {

    private static final String INDEX = "argus_test_idx";

    private ElasticsearchChunkIndexService svc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        svc = new ElasticsearchChunkIndexService(mapper, null, "localhost", 9200, "http", INDEX);
    }

    private static DocumentChunkEntity chunk(Long id, Long groupId, Long documentId, Integer index, String text) {
        DocumentChunkEntity c = new DocumentChunkEntity();
        c.setId(id);
        c.setGroupId(groupId);
        c.setDocumentId(documentId);
        c.setChunkIndex(index);
        c.setChunkText(text);
        return c;
    }

    private static void assertThrowMissing(DocumentChunkEntity chunk) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new ElasticsearchChunkIndexService(new ObjectMapper(), null, "h", 9200, "http", "i")
                        .validateChunk(chunk));
        assertEquals("ES 索引写入缺少必要 chunk 字段", ex.getMessage());
    }

    // ---------- validateChunk ----------

    @Test
    void validChunkPasses() {
        svc.validateChunk(chunk(7L, 9L, 5L, 0, "文本"));
    }

    @Test
    void nullChunkRejected() {
        assertThrowMissing(null);
    }

    @Test
    void missingFieldsRejected() {
        assertThrowMissing(chunk(null, 9L, 5L, 0, "文本"));
        assertThrowMissing(chunk(7L, null, 5L, 0, "文本"));
        assertThrowMissing(chunk(7L, 9L, null, 0, "文本"));
        assertThrowMissing(chunk(7L, 9L, 5L, null, "文本"));
    }

    @Test
    void blankChunkTextRejected() {
        assertThrowMissing(chunk(7L, 9L, 5L, 0, "   "));
    }

    // ---------- buildChunkDoc ----------

    @Test
    void docCarriesAllFieldsWithReadyAndNotDeleted() {
        Map<String, Object> doc = svc.buildChunkDoc("a.pdf", chunk(7L, 9L, 5L, 2, "正文"));
        assertEquals(7L, doc.get("chunkId"));
        assertEquals(9L, doc.get("groupId"));
        assertEquals(5L, doc.get("documentId"));
        assertEquals(2, doc.get("chunkIndex"));
        assertEquals("a.pdf", doc.get("fileName"));
        assertEquals("正文", doc.get("chunkText"));
        assertEquals("READY", doc.get("status"));
        assertEquals(Boolean.FALSE, doc.get("deleted"));
    }

    @Test
    void docPreservesFileNameParameter() {
        Map<String, Object> doc = svc.buildChunkDoc("资料.xlsx", chunk(1L, 2L, 3L, 0, "x"));
        assertEquals("资料.xlsx", doc.get("fileName"));
    }

    // ---------- buildChunkBulkLines ----------

    @Test
    void singleChunkYieldsTwoNdjsonLines() throws Exception {
        List<DocumentChunkEntity> chunks = List.of(chunk(7L, 9L, 5L, 0, "正文"));
        String body = svc.buildChunkBulkLines("a.pdf", chunks);
        assertTrue(body.endsWith("\n"));

        String[] lines = body.split("\n");
        assertEquals(2, lines.length);

        JsonNode action = mapper.readTree(lines[0]);
        assertEquals(INDEX, action.path("index").path("_index").asText());
        assertEquals("7", action.path("index").path("_id").asText());

        JsonNode doc = mapper.readTree(lines[1]);
        assertEquals(7L, doc.get("chunkId").asLong());
        assertEquals("READY", doc.get("status").asText());
        assertEquals(false, doc.get("deleted").asBoolean());
    }

    @Test
    void multipleChunksProduceTwoLinesEach() throws Exception {
        List<DocumentChunkEntity> chunks = List.of(
                chunk(1L, 2L, 3L, 0, "一"),
                chunk(4L, 5L, 6L, 1, "二"));
        String body = svc.buildChunkBulkLines("x.txt", chunks);
        String[] lines = body.split("\n");
        assertEquals(4, lines.length);

        assertEquals("1", mapper.readTree(lines[0]).path("index").path("_id").asText());
        assertEquals("4", mapper.readTree(lines[2]).path("index").path("_id").asText());
        // doc 文本两端正确序列化
        assertEquals("一", mapper.readTree(lines[1]).get("chunkText").asText());
    }

    @Test
    void validatesBeforeEachWrite() {
        List<DocumentChunkEntity> chunks = List.of(chunk(1L, 2L, 3L, 0, "ok"), chunk(1L, 2L, 3L, 0, null));
        assertThrows(BusinessException.class, () -> svc.buildChunkBulkLines("x.txt", chunks));
    }
}