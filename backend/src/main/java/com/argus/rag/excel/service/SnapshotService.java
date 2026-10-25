package com.argus.rag.excel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时快照服务——写入 excel_document_snapshot 表。
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final ConcurrentHashMap<Long, LocalDateTime> lastEditTimes = new ConcurrentHashMap<>();
    private final ExcelVersionService versionService;

    public SnapshotService(ExcelVersionService versionService) {
        this.versionService = versionService;
    }

    /** 记录文档被编辑。 */
    public void touch(Long documentId) {
        lastEditTimes.put(documentId, LocalDateTime.now());
    }

    /** 活跃文档每 5 分钟自动生成一次版本快照。 */
    @Scheduled(fixedRate = 300_000)
    public void scheduledSnapshot() {
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (Map.Entry<Long, LocalDateTime> entry : lastEditTimes.entrySet()) {
            Long docId = entry.getKey();
            Duration since = Duration.between(entry.getValue(), now);
            boolean doSnapshot = since.toHours() < 1
                    || (since.toHours() < 24 && now.getMinute() % 30 < 5);
            if (doSnapshot) {
                try {
                    versionService.createSnapshot(docId, ExcelVersionService.TYPE_CELL, "定时自动快照");
                    count++;
                }
                catch (Exception e) { log.error("快照失败 doc={}: {}", docId, e.getMessage()); }
            }
        }
        if (count > 0) log.info("定时快照完成: {} 个文档", count);
    }

    /** 兼容旧调用：立即创建一次单元格自动快照。 */
    public void createSnapshot(Long documentId) {
        versionService.createSnapshot(documentId, ExcelVersionService.TYPE_CELL, "手动触发自动快照");
    }
}
