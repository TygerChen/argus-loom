package com.argus.rag.ingestion.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentIngestionAsyncService} 与任务记录服务的联动契约
 * （mock 全部协作者，纯 JVM/零 DB/零存储/零 LLM）。
 * <p>锁住任务状态推进：ETL 开始时把任务转 RUNNING，成功后标 SUCCEEDED，
 * 最终失败经 recover 标 FAILED 并写入错误信息。任务记录失败不阻断 ETL 主流程。</p>
 */
@DisplayName("异步 ETL 服务任务联动（DocumentIngestionAsyncService）")
class DocumentIngestionAsyncServiceJobTest {

    private static final Long DOCUMENT_ID = 9L;
    private static final Long GROUP_ID = 3L;

    private DocumentMapper documentMapper;
    private DocumentIngestionProcessor processor;
    private DocumentChunkMapper chunkMapper;
    private VectorIngestionService vectorService;
    private ElasticsearchChunkIndexService esService;
    private IngestionJobService jobService;
    private DocumentIngestionAsyncService service;

    @BeforeEach
    void setUp() {
        // 纯 mock 环境无 MyBatis 上下文：LambdaUpdateWrapper 需要实体 TableInfo 缓存，手动注册
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DocumentEntity.class);
        documentMapper = mock(DocumentMapper.class);
        when(documentMapper.update(any(), any())).thenReturn(1);
        processor = mock(DocumentIngestionProcessor.class);
        chunkMapper = mock(DocumentChunkMapper.class);
        vectorService = mock(VectorIngestionService.class);
        esService = mock(ElasticsearchChunkIndexService.class);
        jobService = mock(IngestionJobService.class);
        service = new DocumentIngestionAsyncService(
                documentMapper, processor, chunkMapper, vectorService, esService, jobService);
    }

    private DocumentEntity processingDocument() {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOCUMENT_ID);
        doc.setGroupId(GROUP_ID);
        doc.setFileName("a.pdf");
        doc.setStatus(DocumentStatus.PROCESSING.name());
        return doc;
    }

    @Test
    void ingestMarksJobRunningThenSucceeded() {
        when(documentMapper.selectByIdAndGroupId(DOCUMENT_ID, GROUP_ID)).thenReturn(processingDocument());

        service.ingestDocument(DOCUMENT_ID, GROUP_ID);

        verify(jobService).markRunning(eq(DOCUMENT_ID), eq(GROUP_ID), anyString());
        verify(jobService).markSucceeded(DOCUMENT_ID);
        verify(jobService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void recoverMarksJobFailedWithReason() {
        service.recover(new RuntimeException("boom"), DOCUMENT_ID, GROUP_ID);

        verify(jobService).markFailed(eq(DOCUMENT_ID), contains("boom"));
    }

    @Test
    void ingestStillSucceedsWhenJobRecordingFails() {
        when(documentMapper.selectByIdAndGroupId(DOCUMENT_ID, GROUP_ID)).thenReturn(processingDocument());
        doThrow(new RuntimeException("job table down"))
                .when(jobService).markRunning(anyLong(), anyLong(), anyString());

        service.ingestDocument(DOCUMENT_ID, GROUP_ID);

        verify(processor).process(DOCUMENT_ID, GROUP_ID);
        verify(jobService).markSucceeded(DOCUMENT_ID);
    }

    @Test
    void recoverStillMarksDocumentFailedWhenJobRecordingFails() {
        doThrow(new RuntimeException("job table down"))
                .when(jobService).markFailed(anyLong(), anyString());

        service.recover(new RuntimeException("boom"), DOCUMENT_ID, GROUP_ID);

        verify(documentMapper).update(any(), any());
    }
}
