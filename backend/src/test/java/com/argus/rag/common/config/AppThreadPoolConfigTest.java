package com.argus.rag.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线程池治理契约（任务 9）：所有业务线程池必须有界队列 + 拒绝策略，
 * 防止突发流量下任务堆积导致内存溢出或任务被静默丢弃。
 */
@DisplayName("AppThreadPoolConfig 有界队列与拒绝策略")
class AppThreadPoolConfigTest {

    private final AppThreadPoolConfig config = new AppThreadPoolConfig();

    @Test
    @DisplayName("通用异步 taskExecutor：有界队列 + CallerRuns 拒绝策略")
    void taskExecutorBoundedAndRejected() throws Exception {
        ThreadPoolTaskExecutor executor = config.taskExecutor();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field(executor, "threadPoolExecutor");
        assertThat(pool.getQueue().remainingCapacity()).isLessThan(Integer.MAX_VALUE);
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("导出 excelExportExecutor：有界队列 + CallerRuns 拒绝策略")
    void exportExecutorBoundedAndRejected() throws Exception {
        ExecutorService executor = config.excelExportExecutor();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(100);
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("检索 retrievalExecutor：有界队列 + CallerRuns 拒绝策略")
    void retrievalExecutorBoundedAndRejected() throws Exception {
        ThreadPoolTaskExecutor executor = config.retrievalExecutor();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field(executor, "threadPoolExecutor");
        assertThat(pool.getQueue().remainingCapacity()).isLessThan(Integer.MAX_VALUE);
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
