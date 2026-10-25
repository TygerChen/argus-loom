package com.argus.rag.ingestion.service;

import com.argus.rag.common.enums.IngestionJobStatus;
import com.argus.rag.common.enums.IngestionJobType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.argus.rag.ingestion.mapper.IngestionJobMapper;
import com.argus.rag.ingestion.model.entity.IngestionJobEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionJobService} 摄入任务记录服务契约（mock IngestionJobMapper，纯 JVM/零 DB）。
 * <p>锁住任务全生命周期：上传登记 PENDING → ETL 开始转 RUNNING → 成功 SUCCEEDED /
 * 失败 FAILED（记录错误信息）→ 重试重置为 PENDING；查询按文档返回全部任务。</p>
 */
@DisplayName("摄入任务记录服务（IngestionJobService）")
class IngestionJobServiceTest {

    private static final Long DOCUMENT_ID = 42L;
    private static final Long GROUP_ID = 7L;

    private IngestionJobMapper ingestionJobMapper;
    private IngestionJobService service;

    @BeforeEach
    void setUp() {
        // 纯 mock 环境无 MyBatis 上下文：LambdaUpdateWrapper/insert 需要实体 TableInfo 缓存，手动注册
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionJobEntity.class);
        ingestionJobMapper = mock(IngestionJobMapper.class);
        // 模拟数据库自增主键：insert 时回填 id
        when(ingestionJobMapper.insert(any(IngestionJobEntity.class))).thenAnswer(inv -> {
            IngestionJobEntity entity = inv.getArgument(0);
            entity.setId(100L);
            return 1;
        });
        service = new IngestionJobService(ingestionJobMapper);
    }

    @Test
    void registerCreatesPendingIngestDocumentJob() {
        Long jobId = service.registerIngestDocumentJob(DOCUMENT_ID, GROUP_ID);

        ArgumentCaptor<IngestionJobEntity> captor = ArgumentCaptor.forClass(IngestionJobEntity.class);
        verify(ingestionJobMapper).insert(captor.capture());
        IngestionJobEntity saved = captor.getValue();
        assertEquals(DOCUMENT_ID, saved.getDocumentId());
        assertEquals(GROUP_ID, saved.getGroupId());
        assertEquals(IngestionJobType.INGEST_DOCUMENT.name(), saved.getJobType());
        assertEquals(IngestionJobStatus.PENDING.name(), saved.getStatus());
        assertEquals(0, saved.getRetryCount());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertNotNull(jobId);
    }

    @Test
    void markRunningTransitionsPendingJobToRunning() {
        IngestionJobEntity pending = new IngestionJobEntity();
        pending.setId(11L);
        pending.setStatus(IngestionJobStatus.PENDING.name());
        when(ingestionJobMapper.selectByDocumentIdOrdered(DOCUMENT_ID)).thenReturn(List.of(pending));
        when(ingestionJobMapper.claimRunning(eq(11L), any(), any(), any(), any())).thenReturn(1);

        service.markRunning(DOCUMENT_ID, GROUP_ID, "worker-1");

        verify(ingestionJobMapper).claimRunning(eq(11L),
                eq(IngestionJobStatus.PENDING.name()),
                eq(IngestionJobStatus.RUNNING.name()),
                eq("worker-1"), any());
    }

    @Test
    void markRunningWithoutPendingJobIsNoOp() {
        when(ingestionJobMapper.selectByDocumentIdOrdered(DOCUMENT_ID)).thenReturn(List.of());

        service.markRunning(DOCUMENT_ID, GROUP_ID, "worker-1");

        verify(ingestionJobMapper, never()).claimRunning(any(), any(), any(), any(), any());
    }

    @Test
    void markSucceededFinishesRunningJob() {
        IngestionJobEntity running = new IngestionJobEntity();
        running.setId(12L);
        running.setStatus(IngestionJobStatus.RUNNING.name());
        running.setWorkerId("worker-1");
        when(ingestionJobMapper.selectByDocumentIdOrdered(DOCUMENT_ID)).thenReturn(List.of(running));
        when(ingestionJobMapper.markSucceeded(eq(12L), any(), any(), any(), any())).thenReturn(1);

        service.markSucceeded(DOCUMENT_ID);

        verify(ingestionJobMapper).markSucceeded(eq(12L),
                eq(IngestionJobStatus.RUNNING.name()),
                eq(IngestionJobStatus.SUCCEEDED.name()),
                eq("worker-1"), any());
    }

    @Test
    void markFailedRecordsErrorAndFinishesRunningJob() {
        IngestionJobEntity running = new IngestionJobEntity();
        running.setId(13L);
        running.setStatus(IngestionJobStatus.RUNNING.name());
        running.setWorkerId("worker-1");
        when(ingestionJobMapper.selectByDocumentIdOrdered(DOCUMENT_ID)).thenReturn(List.of(running));
        when(ingestionJobMapper.markFailed(eq(13L), any(), any(), any(), any(), any())).thenReturn(1);

        service.markFailed(DOCUMENT_ID, "解析失败: PDF 损坏");

        verify(ingestionJobMapper).markFailed(eq(13L),
                eq(IngestionJobStatus.RUNNING.name()),
                eq(IngestionJobStatus.FAILED.name()),
                eq("worker-1"), any(), eq("解析失败: PDF 损坏"));
    }

    @Test
    void markFailedTruncatesLongError() {
        IngestionJobEntity running = new IngestionJobEntity();
        running.setId(14L);
        running.setStatus(IngestionJobStatus.RUNNING.name());
        running.setWorkerId("worker-1");
        when(ingestionJobMapper.selectByDocumentIdOrdered(DOCUMENT_ID)).thenReturn(List.of(running));
        when(ingestionJobMapper.markFailed(any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.markFailed(DOCUMENT_ID, "x".repeat(3000));

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(ingestionJobMapper).markFailed(any(), any(), any(), any(), any(), errorCaptor.capture());
        assertEquals(512, errorCaptor.getValue().length());
    }

    @Test
    void listJobsByDocumentReturnsMapperResult() {
        IngestionJobEntity job = new IngestionJobEntity();
        job.setId(15L);
        job.setStatus(IngestionJobStatus.SUCCEEDED.name());
        when(ingestionJobMapper.selectByDocumentIdOrdered(DOCUMENT_ID)).thenReturn(List.of(job));

        List<IngestionJobEntity> jobs = service.listJobsByDocument(DOCUMENT_ID);

        assertEquals(1, jobs.size());
        assertEquals(IngestionJobStatus.SUCCEEDED.name(), jobs.get(0).getStatus());
    }
}
