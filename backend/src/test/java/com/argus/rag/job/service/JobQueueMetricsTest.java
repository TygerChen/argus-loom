package com.argus.rag.job.service;

import com.argus.rag.job.mapper.BackgroundJobMapper;
import com.argus.rag.job.model.entity.BackgroundJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务队列指标契约（任务 12）：QUEUED/RUNNING/DEAD_LETTER 数量与最久等待秒数
 * 全部通过 Micrometer Gauge 暴露，供 Prometheus 抓取与告警规则引用。
 */
@DisplayName("任务队列指标（JobQueueMetrics）")
class JobQueueMetricsTest {

    private BackgroundJobMapper jobMapper;
    private SimpleMeterRegistry registry;
    private JobQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        jobMapper = mock(BackgroundJobMapper.class);
        registry = new SimpleMeterRegistry();
        metrics = new JobQueueMetrics(jobMapper, registry);
    }

    @Test
    @DisplayName("Gauge 按各状态任务数取值")
    void gaugesReflectStatusCounts() {
        when(jobMapper.selectCount(any())).thenReturn(3L);

        metrics.refresh();

        assertThat(registry.get("argus.job.queued").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("argus.job.running").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("argus.job.dead_letter").gauge().value()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("最久等待 Gauge：QUEUED 任务最早 next_run_at 距今秒数")
    void oldestWaitGauge() {
        BackgroundJob oldest = new BackgroundJob();
        java.time.LocalDateTime base = java.time.LocalDateTime.now();
        when(jobMapper.selectOne(any())).thenAnswer(inv -> {
            BackgroundJob j = new BackgroundJob();
            j.setNextRunAt(base);
            return j;
        });

        metrics.refresh();

        double value = registry.get("argus.job.oldest_wait_seconds").gauge().value();
        assertThat(value).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("无等待任务时最久等待为 0")
    void oldestWaitZeroWhenEmpty() {
        when(jobMapper.selectOne(any())).thenReturn(null);

        metrics.refresh();

        assertThat(registry.get("argus.job.oldest_wait_seconds").gauge().value()).isEqualTo(0.0);
    }
}
