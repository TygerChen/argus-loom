package com.argus.rag.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用级线程池收口（P2-02 / P1-06 / P2-04）。
 *
 * <p>此前 {@code @Async}、{@code @Scheduled}、{@code CompletableFuture.runAsync} 与
 * {@code parallelStream} 都落在 Spring 默认执行器或 JVM 公共 ForkJoinPool 上：队列无界、
 * 无拒绝策略、与业务争抢公共线程。这里定义显式线程池与调度器，做到：
 * <ul>
 *   <li>线程名可辨识（{@code argus-*}），日志/线程转储可直接归因；</li>
 *   <li>队列有界 + 拒绝策略（CallerRuns：不丢任务，把背压交回调用线程）；</li>
 *   <li>按用途隔离（通用异步 / 导出 / 检索并行 / 定时），互不饿死。</li>
 * </ul>
 * 说明：{@code taskExecutor} 与 {@code taskScheduler} 使用 Spring 约定名，
 * 既覆盖 Spring Boot 自动配置的默认实例，也让 {@code @Async}/{@code @Scheduled}
 * 默认解析到本配置（无需逐个注解指定 bean 名）。</p>
 */
@Configuration
public class AppThreadPoolConfig {

    /** 通用异步（文档 ETL 监听、长期记忆抽取、LLM 用量采集等）：允许短暂突发，队列有界不丢任务。 */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return buildExecutor(4, 8, 200, "argus-async-");
    }

    /**
     * 导出任务专用线程池（P1-06）。
     * <p>以 {@link ExecutorService} 形态暴露：导出服务按 taskId 持有 {@code Future}，
     * 取消时 {@code cancel(true)} 能真正中断正在写盘的线程；{@code CallerRunsPolicy}
     * 保证任务不会因队列满被静默丢弃。停机时由 Spring 调用 {@code shutdown()}。</p>
     */
    @Bean(name = "excelExportExecutor", destroyMethod = "shutdown")
    public ExecutorService excelExportExecutor() {
        return new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                namedThreadFactory("argus-export-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 检索并行通道（P2-04：替代 ForkJoinPool.commonPool），任务短小、IO 密集。 */
    @Bean("retrievalExecutor")
    public ThreadPoolTaskExecutor retrievalExecutor() {
        return buildExecutor(4, 8, 64, "argus-retrieval-");
    }

    /** 定时任务调度器（快照、导出任务清扫等），双线程足够且避免并发快照互相覆盖。 */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("argus-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 统一构造：有界队列 + CallerRuns（背压回调用者，不静默丢任务）+ 优雅停机。 */
    private static ThreadPoolTaskExecutor buildExecutor(int coreSize, int maxSize, int queueCapacity,
                                                        String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /** 可辨识线程名（含自增序号），便于日志与线程转储定位具体池与实例。 */
    static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
