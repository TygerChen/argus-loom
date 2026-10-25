package com.argus.rag.excel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExcelDocumentLockService} 文档级互斥契约（真实 {@link ReentrantLock}，无 DB）：
 * - 同文档并发进入锁内时，临界区同时进入数恒 ≤1（真正串行、不丢写）；
 * - 不同文档各自独立，不会被同一把锁相互阻塞。
 */
@DisplayName("Excel 文档级写锁互斥（ExcelDocumentLockService）")
class ExcelDocumentLockServiceTest {

    @Test
    void concurrentAccessToSameDocumentIsSerialized() throws Exception {
        ExcelDocumentLockService lock = new ExcelDocumentLockService();
        int tasks = 30;
        AtomicInteger inside = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);

        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        for (int i = 0; i < tasks; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.runWithLock(11L, () -> {
                    int now = inside.incrementAndGet();
                    maxConcurrent.updateAndGet(m -> Math.max(m, now));
                    try {
                        Thread.sleep(2); // 放大竞争窗口
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                });
                completed.incrementAndGet();
                done.countDown();
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "所有任务应在超时内完成而不是死锁");
        pool.shutdownNow();

        assertEquals(tasks, completed.get(), "全部任务都应执行完成，无一遗漏");
        assertEquals(1, maxConcurrent.get(), "同文档临界区不得并发进入");
    }

    @Test
    void differentDocumentsAreNotBlockedByEachOther() throws Exception {
        ExcelDocumentLockService lock = new ExcelDocumentLockService();
        AtomicInteger docB = new AtomicInteger(0);
        CountDownLatch aInsideHeld = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);

        Thread a = new Thread(() -> lock.runWithLock(11L, () -> {
            aInsideHeld.countDown();
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        a.start();

        Thread b = new Thread(() -> {
            lock.runWithLock(22L, () -> docB.incrementAndGet());
            bFinished.countDown();
        });
        b.start();

        assertTrue(aInsideHeld.await(2, TimeUnit.SECONDS), "文档 A 应先拿到锁");
        assertTrue(bFinished.await(2, TimeUnit.SECONDS), "不同文档锁之间不应互斥阻塞");
        assertEquals(1, docB.get());
    }
}