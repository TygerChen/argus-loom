package com.argus.rag.common.health;

import com.argus.rag.excel.entity.ExcelCollabOutbox;
import com.argus.rag.excel.mapper.ExcelCollabOutboxMapper;
import com.argus.rag.job.mapper.BackgroundJobMapper;
import com.argus.rag.job.model.entity.BackgroundJob;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 队列健康检查（任务 12）：死信任务或协同 Outbox 积压超过阈值即降级。
 * 详情只暴露计数，绝不带连接串/密钥/异常堆栈。
 */
@Component("queue")
public class QueueHealthIndicator implements HealthIndicator {

    private final BackgroundJobMapper jobMapper;
    private final ExcelCollabOutboxMapper outboxMapper;
    private final long outboxBacklogThreshold;

    public QueueHealthIndicator(BackgroundJobMapper jobMapper,
                                ExcelCollabOutboxMapper outboxMapper,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${argus.health.outbox-backlog-threshold:1000}") long outboxBacklogThreshold) {
        this.jobMapper = jobMapper;
        this.outboxMapper = outboxMapper;
        this.outboxBacklogThreshold = outboxBacklogThreshold;
    }

    @Override
    public Health health() {
        try {
            Long dead = jobMapper.selectCount(new QueryWrapper<BackgroundJob>()
                    .eq("status", BackgroundJob.STATUS_DEAD_LETTER));
            Long pendingOutbox = outboxMapper.selectCount(new QueryWrapper<ExcelCollabOutbox>()
                    .eq("status", ExcelCollabOutbox.STATUS_PENDING));
            long deadCount = dead == null ? 0 : dead;
            long outboxCount = pendingOutbox == null ? 0 : pendingOutbox;
            boolean down = deadCount > 0 || outboxCount > outboxBacklogThreshold;
            Health.Builder builder = down ? Health.down() : Health.up();
            return builder
                    .withDetail("deadLetterJobs", deadCount)
                    .withDetail("pendingOutboxEvents", outboxCount)
                    .withDetail("outboxBacklogThreshold", outboxBacklogThreshold)
                    .build();
        } catch (Exception e) {
            // 不带异常消息（可能含 SQL/连接信息），只报告降级事实。
            return Health.down().withDetail("error", "queue probe failed").build();
        }
    }
}
