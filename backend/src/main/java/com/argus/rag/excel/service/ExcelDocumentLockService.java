package com.argus.rag.excel.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单实例文档写锁：同一文档的 HTTP 保存 / 协同写入 / 撤销重做 / 版本回滚串行执行。
 */
@Service
public class ExcelDocumentLockService {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(Long documentId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(documentId, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(documentId);
            }
        }
    }

    public void runWithLock(Long documentId, Runnable action) {
        withLock(documentId, () -> {
            action.run();
            return null;
        });
    }
}
