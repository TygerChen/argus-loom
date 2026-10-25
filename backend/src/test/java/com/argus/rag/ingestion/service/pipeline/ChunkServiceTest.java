package com.argus.rag.ingestion.service.pipeline;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkService} 文档切片持久化契约（mock {@link DocumentChunkMapper} + 真实 Spring AI Document
 * + 真实 ObjectMapper，零 DB/零 LLM/零真实网络）：
 * - 守卫：documentId/groupId 任一为空 →「切片前必须提供 documentId 和 groupId」且不碰 mapper；
 *   空/全空白切片 →「文档切片结果为空，无法持久化」；
 * - 成功路径：删旧 → 批量入库 → 反查主键回填，chunkIndex 去空白后连续重排、charStart/charEnd 随
 *   上一个切片累计、摘要 ≤120 原样 / >120 截断+省略号、metadataJson 含 documentId/groupId/chunkIndex/
 *   charStart/charEnd/chunkStrategy；
 * - 范围可信度判定：charStart+charEnd 可信采用，charEnd 不足/负数/小数/溢出 → 回退自增，
 *   仅 start、仅 end、字符串整型解析；
 * - 主键回填失败：数量不匹配→「回填主键失败」、返回主键不完整→「主键数据不完整」、
 *   无法按 chunkIndex 匹配→「无法匹配 chunk 主键」；
 * - 分批入库：切片数超过 Postgres 参数上限(6540/批)时拆成多批 insertBatch、子列表尺寸正确。
 */
@DisplayName("文档切片持久化（ChunkService）")
class ChunkServiceTest {

    private DocumentChunkMapper mapper;
    private ChunkService service;

    private static final Long DOC = 10L;
    private static final Long GROUP = 20L;

    @BeforeEach
    void setUp() {
        mapper = mock(DocumentChunkMapper.class);
        service = new ChunkService(mapper, new ObjectMapper());
    }

    // ---------- helpers ----------

    private static Document doc(String text) {
        return new Document(text);
    }

    private static Document doc(String text, Map<String, Object> metadata) {
        return new Document(text, metadata);
    }

    /** 反查回填：返回与输入切片同数、chunkIndex 匹配、带自增主键的持久化实体 */
    private void stubBackfill(List<DocumentChunkEntity> persisted) {
        when(mapper.selectByDocumentId(DOC)).thenReturn(persisted);
    }

    private static List<DocumentChunkEntity> persistedChunks(int count) {
        return persistedChunks(count, (i) -> {
            DocumentChunkEntity e = new DocumentChunkEntity();
            e.setId(i + 1L);
            e.setChunkIndex(i);
            return e;
        });
    }

    private static List<DocumentChunkEntity> persistedChunks(int count,
                                                             java.util.function.IntFunction<DocumentChunkEntity> factory) {
        List<DocumentChunkEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(factory.apply(i));
        }
        return list;
    }

    /** 执行一次单切片保存（回填 mock 为 1 条匹配实体），返回落库切片 */
    private DocumentChunkEntity saveOne(String text, Map<String, Object> metadata) {
        stubBackfillOne();
        return service.saveChunkDocuments(DOC, GROUP, List.of(doc(text, metadata))).get(0);
    }

    private void stubBackfillOne() {
        when(mapper.selectByDocumentId(DOC)).thenReturn(persistedChunks(1));
    }

    private void stubBackfill(int count) {
        when(mapper.selectByDocumentId(DOC)).thenReturn(persistedChunks(count));
    }

    // ---------- 守卫 ----------

    @Test
    void saveRejectsMissingIdentifiers() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(null, GROUP, List.of(doc("x"))));
        assertEquals("切片前必须提供 documentId 和 groupId", e.getMessage());
        verify(mapper, never()).deleteByDocumentId(anyLong());

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, null, List.of(doc("x"))));
        assertEquals("切片前必须提供 documentId 和 groupId", e2.getMessage());
    }

    @Test
    void guardRejectsEmptyOrBlankChunks() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, GROUP, null));
        assertEquals("文档切片结果为空，无法持久化", e.getMessage());

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, GROUP, List.of()));
        assertEquals("文档切片结果为空，无法持久化", e2.getMessage());

        // 全部空白文本 → 构建后仍为空
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, GROUP, List.of(doc("   "), doc("\r\n"))));
        assertEquals("文档切片结果为空，无法持久化", e3.getMessage());
        verify(mapper, never()).deleteByDocumentId(anyLong());
    }

    // ---------- 成功路径 ----------

    @Test
    void successNormalizesWhitespaceAndReindexes() {
        stubBackfill(2);
        List<DocumentChunkEntity> result = service.saveChunkDocuments(DOC, GROUP, List.of(
                doc("  Line1\r\nLine2  "),
                doc("   "),
                doc("段落2")));
        assertEquals(2, result.size());

        DocumentChunkEntity c0 = result.get(0);
        assertEquals(0, c0.getChunkIndex());
        assertEquals("Line1\nLine2", c0.getChunkText());
        assertEquals(0, c0.getCharStart());
        assertEquals(11, c0.getCharEnd());
        assertEquals("Line1\nLine2", c0.getChunkSummary());
        assertTrue(c0.getMetadataJson().contains("\"documentId\":" + DOC));
        assertTrue(c0.getMetadataJson().contains("\"groupId\":" + GROUP));
        assertTrue(c0.getMetadataJson().contains("\"chunkIndex\":0"));
        assertTrue(c0.getMetadataJson().contains("\"charStart\":0"));
        assertTrue(c0.getMetadataJson().contains("\"chunkStrategy\":\"spring-ai-document\""));

        DocumentChunkEntity c1 = result.get(1);
        assertEquals(1, c1.getChunkIndex());
        assertEquals("段落2", c1.getChunkText());
        assertEquals(11, c1.getCharStart());
        assertEquals(14, c1.getCharEnd());

        verify(mapper).deleteByDocumentId(DOC);
        verify(mapper, times(1)).insertBatch(anyList());
        verify(mapper).selectByDocumentId(DOC);
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
    }

    @Test
    void summaryUsesTextWhenShort() {
        DocumentChunkEntity c = saveOne("short text", Map.of());
        assertEquals("short text", c.getChunkSummary());
    }

    @Test
    void summaryTruncatesAt120AndAppendsEllipsis() {
        DocumentChunkEntity c = saveOne("A".repeat(250), Map.of());
        assertEquals("A".repeat(120) + "...", c.getChunkSummary());
    }

    // ---------- 范围可信度 ----------

    @Test
    void rangeUsesTrustedCharStartAndEnd() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charStart", 10, "charEnd", 15));
        assertEquals(10, c.getCharStart());
        assertEquals(15, c.getCharEnd());
    }

    @Test
    void rangeFallsBackWhenMetadataEndTooShort() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charStart", 10, "charEnd", 14));
        assertEquals(0, c.getCharStart());
        assertEquals(5, c.getCharEnd());
    }

    @Test
    void rangeUsesStartOnlyWhenTrusted() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charStart", 10));
        assertEquals(10, c.getCharStart());
        assertEquals(15, c.getCharEnd());
    }

    @Test
    void rangeUsesEndOnlyWhenTrusted() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charEnd", 11));
        assertEquals(6, c.getCharStart());
        assertEquals(11, c.getCharEnd());
    }

    @Test
    void rangeFallsBackOnStartOverflow() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charStart", Integer.MAX_VALUE - 1, "charEnd", 15));
        assertEquals(0, c.getCharStart());
        assertEquals(5, c.getCharEnd());
    }

    @Test
    void rangeIgnoresNegativeStart() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charStart", -3));
        assertEquals(0, c.getCharStart());
        assertEquals(5, c.getCharEnd());
    }

    @Test
    void rangeIgnoresFractionalAndNonNumeric() {
        DocumentChunkEntity c = saveOne("hello", Map.of("charStart", "3.5"));
        assertEquals(0, c.getCharStart());
        assertEquals(5, c.getCharEnd());

        DocumentChunkEntity c2 = saveOne("hello", Map.of("charStart", "abc"));
        assertEquals(0, c2.getCharStart());
        assertEquals(5, c2.getCharEnd());
    }

    // ---------- 回填失败分支 ----------

    @Test
    void backfillRejectsCountMismatch() {
        stubBackfill(2); // 实际只存 1 条切片
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, GROUP, List.of(doc("hello"))));
        assertEquals("批量保存切片后回填主键失败", e.getMessage());
    }

    @Test
    void backfillRejectsMissingPrimaryKey() {
        // 持久化返回缺 id → 主键不完整
        List<DocumentChunkEntity> persisted = List.of(entityWith(null, 0));
        stubBackfillList(persisted);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, GROUP, List.of(doc("hello"))));
        assertEquals("批量保存切片后返回的主键数据不完整", e.getMessage());
    }

    @Test
    void backfillRejectsUnmatchedChunkIndex() {
        // 持久化返回的 chunkIndex 与内存切片不匹配
        List<DocumentChunkEntity> persisted = List.of(entityWith(9L, 99));
        stubBackfillList(persisted);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveChunkDocuments(DOC, GROUP, List.of(doc("hello"))));
        assertEquals("批量保存切片后无法匹配 chunk 主键", e.getMessage());
    }

    private static DocumentChunkEntity entityWith(Long id, Integer chunkIndex) {
        DocumentChunkEntity e = new DocumentChunkEntity();
        e.setId(id);
        e.setChunkIndex(chunkIndex);
        return e;
    }

    private void stubBackfillList(List<DocumentChunkEntity> persisted) {
        when(mapper.selectByDocumentId(DOC)).thenReturn(persisted);
    }

    // ---------- 分批入库 ----------

    @Test
    void persistSplitsBatchesBeyondPostgresParameterLimit() {
        // 超过 6540/批 → 按两批切分：6540 + 60
        int total = 6600;
        stubBackfill(total);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            docs.add(doc("chunk-" + i));
        }
        service.saveChunkDocuments(DOC, GROUP, docs);

        ArgumentCaptor<List<DocumentChunkEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(2)).insertBatch(captor.capture());
        List<List<DocumentChunkEntity>> batches = captor.getAllValues();
        assertEquals(6540, batches.get(0).size());
        assertEquals(60, batches.get(1).size());
        verify(mapper).deleteByDocumentId(DOC);
        verify(mapper).selectByDocumentId(DOC);
    }
}