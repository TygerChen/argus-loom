package com.argus.rag.job.service;

import com.argus.rag.job.mapper.BackgroundJobMapper;
import com.argus.rag.job.model.entity.BackgroundJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可靠任务队列服务契约（任务 9）。
 * <p>幂等入队 / SKIP LOCKED 领取 / 租约接管 / 完成与失败退避四条核心语义。
 * Mapper 是薄 SQL 层（FOR UPDATE SKIP LOCKED 语句在 XML 中），这里验证服务层编排逻辑。</p>
 */
@DisplayName("可靠任务队列（BackgroundJobService）")
class BackgroundJobServiceTest {

    private BackgroundJobMapper jobMapper;
    private BackgroundJobService service;

    @BeforeEach
    void setUp() {
        jobMapper = mock(BackgroundJobMapper.class);
        service = new BackgroundJobService(jobMapper);
    }

    private BackgroundJob job(long id, String status, String idempotencyKey) {
        BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setJobType("INGEST_DOCUMENT");
        j.setResourceType("DOCUMENT");
        j.setResourceId(100L);
        j.setIdempotencyKey(idempotencyKey);
        j.setStatus(status);
        j.setAttemptCount(0);
        j.setMaxAttempts(3);
        return j;
    }

    @Test
    @DisplayName("幂等键命中时返回既有任务，不重复创建")
    void sameIdempotencyKeyReturnsExistingJob() {
        BackgroundJob existing = job(9L, "QUEUED", "hash-1");
        when(jobMapper.selectByIdempotencyKey("hash-1")).thenReturn(existing);

        BackgroundJob result = service.enqueue(new BackgroundJobService.NewBackgroundJobCommand(
                "INGEST_DOCUMENT", "DOCUMENT", 100L, "hash-1", "{\"docId\":100}", 3));

        assertThat(result.getId()).isEqualTo(9L);
        verify(jobMapper, never()).insert(any(BackgroundJob.class));
    }

    @Test
    @DisplayName("死信任务同键再入队：条件 UPDATE 复活为 QUEUED 并清空错误")
    void deadLetterJobIsRevivedOnEnqueue() {
        BackgroundJob dead = job(11L, "DEAD_LETTER", "hash-dead");
        when(jobMapper.selectByIdempotencyKey("hash-dead")).thenReturn(dead);
        when(jobMapper.update(any(), any())).thenReturn(1);

        BackgroundJob result = service.enqueue(new BackgroundJobService.NewBackgroundJobCommand(
                "INGEST_DOCUMENT", "DOCUMENT", 100L, "hash-dead", "{\"docId\":100}", 3));

        assertThat(result.getId()).isEqualTo(11L);
        verify(jobMapper, never()).insert(any(BackgroundJob.class));
        verify(jobMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    @DisplayName("SUCCEEDED 任务同键再入队：直接复用，不复活不新建")
    void succeededJobIsReusedAsIs() {
        BackgroundJob done = job(12L, "SUCCEEDED", "hash-done");
        when(jobMapper.selectByIdempotencyKey("hash-done")).thenReturn(done);

        BackgroundJob result = service.enqueue(new BackgroundJobService.NewBackgroundJobCommand(
                "INGEST_DOCUMENT", "DOCUMENT", 100L, "hash-done", "{\"docId\":100}", 3));

        assertThat(result.getId()).isEqualTo(12L);
        verify(jobMapper, never()).insert(any(BackgroundJob.class));
        verify(jobMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("新幂等键创建 QUEUED 任务并设置退避起点")
    void newKeyInsertsQueuedJob() {
        when(jobMapper.selectByIdempotencyKey("hash-2")).thenReturn(null);

        BackgroundJob result = service.enqueue(new BackgroundJobService.NewBackgroundJobCommand(
                "INGEST_DOCUMENT", "DOCUMENT", 100L, "hash-2", "{\"docId\":100}", 3));

        assertThat(result.getStatus()).isEqualTo("QUEUED");
        assertThat(result.getNextRunAt()).isNotNull();
        assertThat(result.getMaxAttempts()).isEqualTo(3);
        verify(jobMapper).insert(result);
    }

    @Test
    @DisplayName("领取：把到期 QUEUED 任务通过 SKIP LOCKED 原子认领并写入租约")
    void claimDueJobsUsesSkipLockedClaim() {
        BackgroundJob due = job(1L, "QUEUED", "k1");
        when(jobMapper.selectDueJobs(any(LocalDateTime.class), eq(10))).thenReturn(List.of(due));
        when(jobMapper.claim(eq(1L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        List<BackgroundJob> claimed = service.claimDueJobs("worker-1", 10, LocalDateTime.now().plusMinutes(1));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getLeaseOwner()).isEqualTo("worker-1");
        verify(jobMapper).selectDueJobs(any(LocalDateTime.class), eq(10));
        verify(jobMapper).claim(eq(1L), eq("QUEUED"), eq("RUNNING"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("租约被其他 Worker 抢先认领时跳过该任务（返回空）")
    void claimSkipsJobLostToAnotherWorker() {
        BackgroundJob due = job(2L, "QUEUED", "k2");
        when(jobMapper.selectDueJobs(any(LocalDateTime.class), eq(10))).thenReturn(List.of(due));
        when(jobMapper.claim(eq(2L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        List<BackgroundJob> claimed = service.claimDueJobs("worker-2", 10, LocalDateTime.now().plusMinutes(1));

        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("过期租约任务可被其他 Worker 接管：selectDueJobs 含 RUNNING 且租约到期的任务")
    void expiredLeaseIsClaimable() {
        BackgroundJob expiredLease = job(3L, "RUNNING", "k3");
        when(jobMapper.selectDueJobs(any(LocalDateTime.class), eq(10)))
                .thenReturn(List.of(expiredLease));
        when(jobMapper.claim(eq(3L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        List<BackgroundJob> claimed = service.claimDueJobs("worker-3", 10, LocalDateTime.now().plusMinutes(1));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getLeaseOwner()).isEqualTo("worker-3");
    }

    @Test
    @DisplayName("完成：只有当前租约持有者能把任务置为 SUCCEEDED")
    void completeChecksLeaseOwner() {
        service.complete(1L, "worker-1");
        verify(jobMapper).complete(eq(1L), eq("worker-1"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("失败退避：首次失败按声明的 1 分钟退避（回归测试：曾因索引 off-by-one 实际 5 分钟）")
    void failOrRetryFirstBackoffIsOneMinute() {
        BackgroundJob running = job(4L, "RUNNING", "k4");
        running.setAttemptCount(1);
        running.setMaxAttempts(3);
        when(jobMapper.selectById(4L)).thenReturn(running);

        service.failOrRetry(4L, "worker-1", new BackgroundJobService.JobFailure("ES_DOWN", "elasticsearch timeout"));

        ArgumentCaptor<BackgroundJob> cap = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("QUEUED");
        assertThat(cap.getValue().getAttemptCount()).isEqualTo(2);
        // 设计意图：attempts=1（首次执行失败）→ RETRY_BACKOFF_MINUTES[0] = 1 分钟
        assertThat(cap.getValue().getNextRunAt())
                .isAfter(LocalDateTime.now().plusSeconds(50))
                .isBefore(LocalDateTime.now().plusSeconds(90));
        assertThat(cap.getValue().getLastErrorCode()).isEqualTo("ES_DOWN");
    }

    @Test
    @DisplayName("失败退避：第二次失败 5 分钟，第三次失败 15 分钟——声明的三档全部可达")
    void failOrRetryFullDeclaredSequence() {
        BackgroundJob second = job(6L, "RUNNING", "k6");
        second.setAttemptCount(2);
        second.setMaxAttempts(3);
        when(jobMapper.selectById(6L)).thenReturn(second);

        service.failOrRetry(6L, "worker-1", new BackgroundJobService.JobFailure("ES_DOWN", "t2"));

        ArgumentCaptor<BackgroundJob> cap2 = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobMapper).updateById(cap2.capture());
        assertThat(cap2.getValue().getNextRunAt())
                .isAfter(LocalDateTime.now().plusMinutes(4))
                .isBefore(LocalDateTime.now().plusMinutes(6));

        BackgroundJob third = job(7L, "RUNNING", "k7");
        third.setAttemptCount(3);
        third.setMaxAttempts(3);
        when(jobMapper.selectById(7L)).thenReturn(third);
        when(jobMapper.updateById(any(BackgroundJob.class))).thenReturn(1);

        service.failOrRetry(7L, "worker-1", new BackgroundJobService.JobFailure("ES_DOWN", "t3"));

        ArgumentCaptor<BackgroundJob> cap3 = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobMapper, org.mockito.Mockito.times(2)).updateById(cap3.capture());
        assertThat(cap3.getAllValues().get(1).getNextRunAt())
                .isAfter(LocalDateTime.now().plusMinutes(14))
                .isBefore(LocalDateTime.now().plusMinutes(16));
    }

    @Test
    @DisplayName("达到最大重试次数进入 DEAD_LETTER，等待人工处理")
    void failOrRetryDeadLettersAfterMaxAttempts() {
        BackgroundJob running = job(5L, "RUNNING", "k5");
        running.setAttemptCount(4);
        running.setMaxAttempts(3);
        when(jobMapper.selectById(5L)).thenReturn(running);

        service.failOrRetry(5L, "worker-1", new BackgroundJobService.JobFailure("ES_DOWN", "still down"));

        ArgumentCaptor<BackgroundJob> cap = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(cap.getValue().getNextRunAt()).isNull();
    }
}
