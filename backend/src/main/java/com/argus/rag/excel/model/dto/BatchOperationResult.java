package com.argus.rag.excel.model.dto;

import java.util.List;

/**
 * 回收站批量恢复 / 彻底删除的结果：成功 ID 列表 + 被拒条目明细。
 * <p>字段名即 JSON key，契约不可变。</p>
 */
public record BatchOperationResult(
        int total,
        int successCount,
        int rejectedCount,
        List<Long> successIds,
        List<BatchRejectEntry> rejected) {
}