package com.argus.rag.job.service;

import com.argus.rag.ingestion.service.DocumentIngestionAsyncService;
import com.argus.rag.job.model.entity.BackgroundJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BackgroundJobWorker} 轮询与分发契约（mock 服务，executor 同步直跑）：
 * - INGEST_DOCUMENT：解析 payload 取 groupId → 委托 ingestDocument → 凭本轮 workerId complete；
 * - payload 缺 groupId / resourceId 为空：数据问题走 failOrRetry(JOB_PAYLOAD_INVALID)——
 *   静默 complete 会把坏任务伪装成成功，死信才可见、可排查（曾为 silent-complete，
 *   由对抗性代码审查发现后修复）；
 * - 未知/缺失任务类型：转 failOrRetry(UNKNOWN_JOB_TYPE)，绝不默默吞掉；
 * - 执行抛异常：转 failOrRetry(EXECUTION_FAILED)，退避与死信由 BackgroundJobService 决策；
 * - 轮询本身失败（DB 抖动）：只 warn 不抛，等待下一轮；
 * - 租约窗口：领取时传入 ≈now+5 分钟的租约。
 * 此前本类零测试覆盖（UNKNOWN_JOB_TYPE 分支无人守护），与 BackgroundJobServiceTest 互补。
 */
@DisplayName("后台任务 Worker 轮询与分发（BackgroundJobWorker）")
class BackgroundJobWorkerTest {

    private BackgroundJobService jobService;
    private DocumentIngestionAsyncService ingestionService;
    private BackgroundJobWorker worker;

    @BeforeEach
    void setUp() {
        jobService = mock(BackgroundJobService.class);
        ingestionService = mock(DocumentIngestionAsyncService.class);
        // executor 同步直跑：分发即执行，断言确定性（真实 taskExecutor 的异步性不属本契约）
        worker = new BackgroundJobWorker(jobService, ingestionService, Runnable::run);
    }

    private BackgroundJob job(String jobType, Long resourceId, String payloadJson) {
        BackgroundJob job = new BackgroundJob();
        job.setId(1L);
        job.setJobType(jobType);
        job.setResourceId(resourceId);
        job.setPayloadJson(payloadJson);
        return job;
    }

    @Test
    void ingestJobDelegatesAndCompletesWithLeaseOwner() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of(job("INGEST_DOCUMENT", 9L, "{\"groupId\":5}")));

        worker.pollAndDispatch();

        verify(ingestionService).ingestDocument(9L, 5L);
        verify(jobService).complete(org.mockito.Mockito.eq(1L),
                argThat(id -> id != null && id.startsWith("worker-")));
        verify(jobService, never()).failOrRetry(anyLong(), anyString(), any());
    }

    @Test
    void claimBatchSizeTenWithFiveMinuteLease() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any())).thenReturn(List.of());

        worker.pollAndDispatch();

        ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobService).claimDueJobs(anyString(), org.mockito.Mockito.eq(10), lease.capture());
        assertTrue(lease.getValue().isAfter(LocalDateTime.now().plusMinutes(4)),
                "租约应约为 now+5 分钟");
    }

    @Test
    void ingestJobWithoutGroupIdGoesToFailOrRetryNotSilentSuccess() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any())).thenReturn(
                List.of(job("INGEST_DOCUMENT", 9L, "{}")));

        worker.pollAndDispatch();

        verify(ingestionService, never()).ingestDocument(any(), any());
        ArgumentCaptor<BackgroundJobService.JobFailure> failure =
                ArgumentCaptor.forClass(BackgroundJobService.JobFailure.class);
        verify(jobService).failOrRetry(org.mockito.Mockito.eq(1L), anyString(), failure.capture());
        assertEquals("JOB_PAYLOAD_INVALID", failure.getValue().errorCode());
        verify(jobService, never()).complete(anyLong(), anyString());
    }

    @Test
    void ingestJobWithoutResourceIdGoesToFailOrRetryNotSilentSuccess() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any())).thenReturn(
                List.of(job("INGEST_DOCUMENT", null, "{\"groupId\":5}")));

        worker.pollAndDispatch();

        verify(ingestionService, never()).ingestDocument(any(), any());
        ArgumentCaptor<BackgroundJobService.JobFailure> failure =
                ArgumentCaptor.forClass(BackgroundJobService.JobFailure.class);
        verify(jobService).failOrRetry(org.mockito.Mockito.eq(1L), anyString(), failure.capture());
        assertEquals("JOB_PAYLOAD_INVALID", failure.getValue().errorCode());
        verify(jobService, never()).complete(anyLong(), anyString());
    }

    @Test
    void unknownJobTypeGoesToFailOrRetryNotSilentlyDropped() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any())).thenReturn(
                List.of(job("EXCEL_EXPORT", 3L, "{}")));

        worker.pollAndDispatch();

        ArgumentCaptor<BackgroundJobService.JobFailure> failure =
                ArgumentCaptor.forClass(BackgroundJobService.JobFailure.class);
        verify(jobService).failOrRetry(org.mockito.Mockito.eq(1L), anyString(), failure.capture());
        assertEquals("UNKNOWN_JOB_TYPE", failure.getValue().errorCode());
        assertTrue(failure.getValue().errorMessage().contains("EXCEL_EXPORT"));
        verify(jobService, never()).complete(anyLong(), anyString());
    }

    @Test
    void nullJobTypeTreatedAsUnknown() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any())).thenReturn(
                List.of(job(null, 3L, "{}")));

        worker.pollAndDispatch();

        ArgumentCaptor<BackgroundJobService.JobFailure> failure =
                ArgumentCaptor.forClass(BackgroundJobService.JobFailure.class);
        verify(jobService).failOrRetry(org.mockito.Mockito.eq(1L), anyString(), failure.capture());
        assertEquals("UNKNOWN_JOB_TYPE", failure.getValue().errorCode());
    }

    @Test
    void executionFailureGoesToRetryPath() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any())).thenReturn(
                List.of(job("INGEST_DOCUMENT", 9L, "{\"groupId\":5}")));
        doThrow(new RuntimeException("boom")).when(ingestionService).ingestDocument(9L, 5L);

        worker.pollAndDispatch();

        ArgumentCaptor<BackgroundJobService.JobFailure> failure =
                ArgumentCaptor.forClass(BackgroundJobService.JobFailure.class);
        verify(jobService).failOrRetry(org.mockito.Mockito.eq(1L), anyString(), failure.capture());
        assertEquals("EXECUTION_FAILED", failure.getValue().errorCode());
        verify(jobService, never()).complete(anyLong(), anyString());
    }

    @Test
    void pollFailureIsSwallowedUntilNextRound() {
        when(jobService.claimDueJobs(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> worker.pollAndDispatch());

        verify(jobService, times(0)).complete(anyLong(), anyString());
        verify(jobService, times(0)).failOrRetry(anyLong(), anyString(), any());
    }

    @Test
    void multipleClaimedJobsEachProcessed() {
        BackgroundJob first = job("INGEST_DOCUMENT", 9L, "{\"groupId\":5}");
        first.setId(1L);
        BackgroundJob unknown = job("WHO_AM_I", 2L, "{}");
        unknown.setId(2L);
        when(jobService.claimDueJobs(anyString(), anyInt(), any()))
                .thenReturn(List.of(first, unknown));

        worker.pollAndDispatch();

        verify(ingestionService).ingestDocument(9L, 5L);
        verify(jobService).complete(org.mockito.Mockito.eq(1L), anyString());
        verify(jobService).failOrRetry(org.mockito.Mockito.eq(2L), anyString(), any());
    }
}
