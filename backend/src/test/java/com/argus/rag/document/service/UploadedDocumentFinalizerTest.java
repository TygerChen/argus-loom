package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.ingestion.service.IngestionJobService;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import com.argus.rag.job.service.BackgroundJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UploadedDocumentFinalizer} 上传文档最终持久化契约（mock mapper/publisher/2 外部索引服务，纯 JVM/零 DB）。
 * finalizeUploaded 把 NewFileInput 全字段映射到 DocumentEntity（status=PROCESSING/deleted=false/三时间戳）落库并发异步 ETL 事件；
 * compensateExternalIndexes 对 null/无 id 短路、删向量+ES 索引、单侧删除异常被吞不影响另一侧。
 */
@DisplayName("上传文档最终持久化（UploadedDocumentFinalizer）")
class UploadedDocumentFinalizerTest {

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final VectorIngestionService vectorIngestionService = mock(VectorIngestionService.class);
    private final ElasticsearchChunkIndexService elasticChunkIndexService =
            mock(ElasticsearchChunkIndexService.class);
    private final IngestionJobService ingestionJobService = mock(IngestionJobService.class);
    private final BackgroundJobService backgroundJobService = mock(BackgroundJobService.class);
    private final UploadedDocumentFinalizer finalizer = new UploadedDocumentFinalizer(
            documentMapper, publisher, vectorIngestionService, elasticChunkIndexService,
            ingestionJobService, backgroundJobService);

    private static UploadedDocumentFinalizer.NewFileInput input() {
        return new UploadedDocumentFinalizer.NewFileInput(
                7L, 9L, "acct.xlsx", "xlsx", MIME, 2048L, "aabbcc", "bucket-1", "obj/acct.xlsx");
    }

    @Test
    void finalizeMapsAllMetadataAndPersists() {
        DocumentEntity document = finalizer.finalizeUploaded(input());
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentMapper).insert(captor.capture());
        DocumentEntity saved = captor.getValue();
        assertEquals(7L, saved.getGroupId());
        assertEquals(9L, saved.getUploaderUserId());
        assertEquals("acct.xlsx", saved.getFileName());
        assertEquals("xlsx", saved.getFileExt());
        assertEquals(MIME, saved.getContentType());
        assertEquals(2048L, saved.getFileSize());
        assertEquals("aabbcc", saved.getFileHash());
        assertEquals("bucket-1", saved.getStorageBucket());
        assertEquals("obj/acct.xlsx", saved.getStorageObjectKey());
        assertEquals(DocumentStatus.PROCESSING.name(), saved.getStatus());
        assertFalse(saved.getDeleted());
        assertNotNull(saved.getUploadedAt());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(document.getId(), saved.getId());
    }

    @Test
    void finalizeEnqueuesIngestionJobAfterPersist() {
        DocumentEntity document = finalizer.finalizeUploaded(input());
        ArgumentCaptor<BackgroundJobService.NewBackgroundJobCommand> captor =
                ArgumentCaptor.forClass(BackgroundJobService.NewBackgroundJobCommand.class);
        verify(backgroundJobService).enqueue(captor.capture());
        BackgroundJobService.NewBackgroundJobCommand command = captor.getValue();
        assertEquals("INGEST_DOCUMENT", command.jobType());
        assertEquals(document.getId(), command.resourceId());
        assertEquals("INGEST:" + document.getId() + ":0", command.idempotencyKey());
        assertNotNull(command.payloadJson());
        assertFalse(command.payloadJson().isBlank());
    }

    @Test
    void finalizeRegistersIngestionJobAfterPersist() {
        DocumentEntity document = finalizer.finalizeUploaded(input());

        verify(ingestionJobService).registerIngestDocumentJob(document.getId(), 7L);
    }

    @Test
    void compensateNullDocumentSkips() {
        finalizer.compensateExternalIndexes(null);
        verify(vectorIngestionService, never()).deleteDocumentVectors(anyLong());
        verify(elasticChunkIndexService, never()).deleteDocumentChunks(anyLong());
    }

    @Test
    void compensateDocumentWithoutIdSkips() {
        finalizer.compensateExternalIndexes(new DocumentEntity());
        verify(vectorIngestionService, never()).deleteDocumentVectors(anyLong());
        verify(elasticChunkIndexService, never()).deleteDocumentChunks(anyLong());
    }

    @Test
    void compensateDeletesVectorAndIndexChunks() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(42L);
        finalizer.compensateExternalIndexes(entity);
        verify(vectorIngestionService).deleteDocumentVectors(42L);
        verify(elasticChunkIndexService).deleteDocumentChunks(42L);
    }

    @Test
    void compensateSwallowsVectorFailureStillCleansIndex() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(42L);
        doThrow(new RuntimeException("vector down"))
                .when(vectorIngestionService).deleteDocumentVectors(anyLong());
        // 向量删除失败不让 ES 索引补偿，且不向上传播
        assertDoesNotThrow(() -> finalizer.compensateExternalIndexes(entity));
        verify(elasticChunkIndexService).deleteDocumentChunks(42L);
    }

    @Test
    void compensateSwallowsIndexFailureStillCleansVector() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(42L);
        doThrow(new RuntimeException("es down"))
                .when(elasticChunkIndexService).deleteDocumentChunks(anyLong());
        assertDoesNotThrow(() -> finalizer.compensateExternalIndexes(entity));
        verify(vectorIngestionService).deleteDocumentVectors(42L);
    }
}
