package com.argus.rag.ingestion.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ElasticsearchRebuildService} 全量重建契约（mock mapper / ES 服务，零 ES 依赖）：
 * - 正常重建：先清空全量 → 逐文档回灌（无切片文档跳过）→ 统计准确；
 * - 并发防护：重建进行中再次触发抛 BusinessException；
 * - 异常后防护复位：中途失败抛出后，标志位必须复位（下次可重建）；
 * - 空库：无 READY 文档时只清空不写入。
 */
@DisplayName("ES 索引全量重建（ElasticsearchRebuildService）")
class ElasticsearchRebuildServiceTest {

    private DocumentMapper documentMapper;
    private DocumentChunkMapper documentChunkMapper;
    private ElasticsearchChunkIndexService chunkIndexService;
    private ElasticsearchRebuildService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        chunkIndexService = mock(ElasticsearchChunkIndexService.class);
        service = new ElasticsearchRebuildService(documentMapper, documentChunkMapper, chunkIndexService);
    }

    private DocumentEntity doc(Long id, String fileName) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setFileName(fileName);
        d.setStatus("READY");
        d.setDeleted(false);
        return d;
    }

    private DocumentChunkEntity chunk(long id) {
        DocumentChunkEntity c = new DocumentChunkEntity();
        c.setId(id);
        return c;
    }

    @Test
    void rebuildClearsThenReindexesReadyDocumentsAndCounts() {
        DocumentEntity withChunks = doc(1L, "差旅制度.md");
        DocumentEntity emptyDoc = doc(2L, "空文档.md");
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(withChunks, emptyDoc));
        when(documentChunkMapper.selectByDocumentId(1L)).thenReturn(List.of(chunk(11L), chunk(12L)));
        when(documentChunkMapper.selectByDocumentId(2L)).thenReturn(List.of());

        ElasticsearchRebuildService.RebuildResult result = service.rebuildAll();

        verify(chunkIndexService, times(1)).deleteAllChunks();
        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<DocumentChunkEntity>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkIndexService, times(1)).indexReadyChunks(fileName.capture(), chunks.capture());
        assertEquals("差旅制度.md", fileName.getValue());
        assertEquals(List.of(11L, 12L),
                chunks.getValue().stream().map(DocumentChunkEntity::getId).toList());
        assertEquals(1, result.documents());
        assertEquals(2, result.indexedChunks());
        assertNotNull(result.elapsedMs());
    }

    @Test
    void concurrentRebuildIsRejected() {
        // 在重建流程内（标志位已置位）再次触发 rebuildAll → 应拒绝
        doAnswer(inv -> {
            service.rebuildAll();
            return null;
        }).when(chunkIndexService).deleteAllChunks();
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc(1L, "a.md")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.rebuildAll());
        assertEquals("ES 索引重建已在进行中，请稍后再试", ex.getMessage());
    }

    @Test
    void failureResetsGuardSoNextRebuildCanRun() {
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc(1L, "a.md")));
        when(documentChunkMapper.selectByDocumentId(1L)).thenReturn(List.of(chunk(11L)));
        // 连续打桩：mock 的第 1 次 deleteAllChunks 抛异常（重建失败），第 2 次正常（标志位复位后重试成功）
        doThrow(new RuntimeException("es down")).doNothing()
                .when(chunkIndexService).deleteAllChunks();

        assertThrows(RuntimeException.class, () -> service.rebuildAll());

        // 防护标志位在 finally 中复位：第二次重建可以正常发起
        ElasticsearchRebuildService.RebuildResult result = service.rebuildAll();
        assertEquals(1, result.documents());
    }

    @Test
    void noReadyDocumentsOnlyClears() {
        when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ElasticsearchRebuildService.RebuildResult result = service.rebuildAll();

        verify(chunkIndexService).deleteAllChunks();
        verify(chunkIndexService, never()).indexReadyChunks(anyString(), anyList());
        assertEquals(0, result.documents());
        assertEquals(0, result.indexedChunks());
    }
}
