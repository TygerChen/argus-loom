package com.argus.rag.common.health;

import com.argus.rag.excel.mapper.ExcelCollabOutboxMapper;
import com.argus.rag.job.mapper.BackgroundJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 队列健康检查契约（任务 12）：任务死信或 Outbox 积压超阈值时降级，
 * 详情只含计数，不泄露连接串或密钥。
 */
@DisplayName("队列健康检查（QueueHealthIndicator）")
class QueueHealthIndicatorTest {

    private BackgroundJobMapper jobMapper;
    private ExcelCollabOutboxMapper outboxMapper;
    private QueueHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        jobMapper = mock(BackgroundJobMapper.class);
        outboxMapper = mock(ExcelCollabOutboxMapper.class);
        indicator = new QueueHealthIndicator(jobMapper, outboxMapper, 1000);
    }

    @Test
    @DisplayName("无积压时 UP，详情只有计数")
    void upWhenNoBacklog() {
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(outboxMapper.selectCount(any())).thenReturn(0L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKeys("deadLetterJobs", "pendingOutboxEvents");
        assertThat(health.getDetails().toString()).doesNotContain("jdbc", "password", "secret");
    }

    @Test
    @DisplayName("死信任务超阈值时 DOWN")
    void downWhenDeadLetterBacklog() {
        when(jobMapper.selectCount(any())).thenReturn(1001L);
        when(outboxMapper.selectCount(any())).thenReturn(0L);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Outbox 积压超阈值时 DOWN")
    void downWhenOutboxBacklog() {
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(outboxMapper.selectCount(any())).thenReturn(1001L);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("依赖查询异常时降级为 DOWN 且不带异常堆栈细节")
    void downOnQueryFailure() {
        when(jobMapper.selectCount(any())).thenThrow(new RuntimeException("db down"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).doesNotContainKey("stackTrace");
    }
}
