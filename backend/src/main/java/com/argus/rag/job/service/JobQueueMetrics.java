package com.argus.rag.job.service;

import com.argus.rag.job.mapper.BackgroundJobMapper;
import com.argus.rag.job.model.entity.BackgroundJob;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * 后台任务队列指标（任务 12）。
 *
 * <p>把 QUEUED/RUNNING/DEAD_LETTER 数量与最久等待秒数暴露为 Micrometer Gauge，
 * 定时刷新（默认 30 秒），Prometheus 抓取后可直接配告警：
 * 任务最久等待超过 15 分钟、死信数量突增等（方案任务 12 第 8 点）。</p>
 */
@Component
public class JobQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(JobQueueMetrics.class);

    private final BackgroundJobMapper jobMapper;
    /** 各指标的最新快照（单线程 @Scheduled 刷新，Gauge 读取无需额外同步）。 */
    private final double[] snapshot = new double[4];

    public JobQueueMetrics(BackgroundJobMapper jobMapper, MeterRegistry registry) {
        this.jobMapper = jobMapper;
        Gauge.builder("argus.job.queued", snapshot, s -> s[0])
                .description("QUEUED 状态任务数").register(registry);
        Gauge.builder("argus.job.running", snapshot, s -> s[1])
                .description("RUNNING 状态任务数").register(registry);
        Gauge.builder("argus.job.dead_letter", snapshot, s -> s[2])
                .description("DEAD_LETTER 状态任务数").register(registry);
        Gauge.builder("argus.job.oldest_wait_seconds", snapshot, s -> s[3])
                .description("QUEUED 任务中最久已等待秒数").register(registry);
    }

    /** 定时刷新各状态计数与最久等待（读多写少，30 秒一次足够告警用）。 */
    @Scheduled(fixedDelayString = "${argus.job.metrics-refresh-ms:30000}")
    public void refresh() {
        try {
            snapshot[0] = count(BackgroundJob.STATUS_QUEUED);
            snapshot[1] = count(BackgroundJob.STATUS_RUNNING);
            snapshot[2] = count(BackgroundJob.STATUS_DEAD_LETTER);
            BackgroundJob oldest = jobMapper.selectOne(new QueryWrapper<BackgroundJob>()
                    .eq("status", BackgroundJob.STATUS_QUEUED)
                    .orderByAsc("next_run_at")
                    .last("LIMIT 1"));
            snapshot[3] = oldest == null || oldest.getNextRunAt() == null ? 0
                    : Math.max(0, Duration.between(oldest.getNextRunAt(), LocalDateTime.now()).getSeconds());
        } catch (Exception e) {
            log.warn("任务队列指标刷新失败: {}", e.getMessage());
        }
    }

    private long count(String status) {
        Long c = jobMapper.selectCount(new QueryWrapper<BackgroundJob>().eq("status", status));
        return c == null ? 0 : c;
    }
}
