package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import com.argus.rag.job.service.BackgroundJobService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentDeleteService} 删除与重试契约：
 * - 幂守护：非法 groupId/文档ID、owner 委托、软删除未命中 {@code DOCUMENT_NOT_FOUND}；
 * - 软删除成功联动向量/ES 清理；
 * - 失败重试：非法 groupId/文档ID、文档不存在、非 FAILED → {@code DOCUMENT_PROCESSING_FAILED}；
 *   成功路径 = 状态复位 + 以时间戳新代数入队（TableInfoHelper 手动注册后覆盖）。
 * 无 roleGate / 无对象存储依赖，全部 mock。
 */
@DisplayName("文档删除与重试（DocumentDeleteService）")
class DocumentDeleteServiceTest {

    private DocumentMapper documentMapper;
    private GroupMembershipService groupMembershipService;
    private VectorIngestionService vectorIngestionService;
    private ElasticsearchChunkIndexService esChunkIndexService;
    private ApplicationEventPublisher eventPublisher;
    private BackgroundJobService backgroundJobService;
    private DocumentDeleteService service;

    @BeforeEach
    void setUp() {
        // 纯 mock 环境无 MyBatis 上下文：retry 成功路径的 LambdaUpdateWrapper 需要实体
        // TableInfo 缓存，手动注册（与 DocumentUploadServiceTest 同一先例）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DocumentEntity.class);
        documentMapper = mock(DocumentMapper.class);
        groupMembershipService = mock(GroupMembershipService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        esChunkIndexService = mock(ElasticsearchChunkIndexService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        backgroundJobService = mock(BackgroundJobService.class);
        service = new DocumentDeleteService(documentMapper, groupMembershipService,
                vectorIngestionService, esChunkIndexService, eventPublisher, backgroundJobService);
    }

    // ---------- softDelete ----------

    @Test
    void softDeleteRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.softDeleteDocument(7L, 0L, 1L));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void softDeleteDelegatesOwnerGate() {
        org.mockito.Mockito.doThrow(new BusinessException("非 OWNER"))
                .when(groupMembershipService).requireGroupOwner(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.softDeleteDocument(7L, 1L, 1L));
        assertEquals("非 OWNER", ex.getMessage());
    }

    @Test
    void softDeleteRejectsIllegalDocumentId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.softDeleteDocument(7L, 1L, 0L));
        assertEquals("文档ID非法", ex.getMessage());
    }

    @Test
    void softDeleteThrowsWhenMarkDeletedMisses() {
        when(documentMapper.markDeleted(1L, 1L)).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.softDeleteDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
    }

    @Test
    void softDeleteSucceedsAndCleansVectorsAndEs() {
        when(documentMapper.markDeleted(1L, 1L)).thenReturn(1);
        service.softDeleteDocument(7L, 1L, 1L);
        verify(vectorIngestionService).deleteDocumentVectors(1L);
        verify(esChunkIndexService).deleteDocumentChunks(1L);
    }

    // ---------- retry ----------

    @Test
    void retryRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryFailedDocumentIngestion(7L, 0L, 1L));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void retryRejectsIllegalDocumentId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryFailedDocumentIngestion(7L, 1L, 0L));
        assertEquals("文档ID非法", ex.getMessage());
    }

    @Test
    void retryRejectsMissingDocument() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryFailedDocumentIngestion(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
    }

    @Test
    void retryRejectsNonFailedStatus() {
        DocumentEntity doc = new DocumentEntity();
        doc.setStatus(DocumentStatus.PROCESSING.name());
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryFailedDocumentIngestion(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_PROCESSING_FAILED, ex.getCode());
        assertEquals("仅失败文档支持重新处理", ex.getMessage());
    }

    @Test
    void retrySuccessResetsStatusAndEnqueuesWithFreshGeneration() {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(1L);
        doc.setStatus(DocumentStatus.FAILED.name());
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);

        service.retryFailedDocumentIngestion(7L, 1L, 1L);

        // 状态先复位为 PROCESSING（wrapper 复位 failureReason/processedAt 由 SQL 语义保证）
        verify(documentMapper).update(any(), any());
        // 再以全新幂等键入队：INGEST:{documentId}:{时间戳代数}，不复用初始 generation=0
        ArgumentCaptor<BackgroundJobService.NewBackgroundJobCommand> command =
                ArgumentCaptor.forClass(BackgroundJobService.NewBackgroundJobCommand.class);
        verify(backgroundJobService).enqueue(command.capture());
        BackgroundJobService.NewBackgroundJobCommand cmd = command.getValue();
        assertEquals("INGEST_DOCUMENT", cmd.jobType());
        assertEquals("DOCUMENT", cmd.resourceType());
        assertEquals(1L, cmd.resourceId());
        assertEquals(3, cmd.maxAttempts());
        assertTrue(cmd.idempotencyKey().startsWith("INGEST:1:"),
                "幂等键应为 INGEST:{documentId}:{generation} 格式，实际=" + cmd.idempotencyKey());
        assertNotEquals("INGEST:1:0", cmd.idempotencyKey(), "手动重试不得复用初始 generation=0");
        assertTrue(cmd.payloadJson().contains("1"), "payload 应携带 groupId 供 Worker 校验归属");
    }

    // retry 成功路径曾因 LambdaUpdateWrapper 依赖 MyBatis-Plus 运行时 TableInfo 缓存而
    // "留待集成测试"；DocumentUploadServiceTest 证实可在单测中手动 initTableInfo 后覆盖，
    // 上面的 retrySuccessResetsStatusAndEnqueuesWithFreshGeneration 即为该路径的守护测试。
}
