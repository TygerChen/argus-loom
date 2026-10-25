package com.argus.rag.excel.model.dto;

/**
 * 回收站批量操作的纯计数结果（{@code /recycle/clear} 返回）。
 * <p>仅含计数，无明细；字段名即 JSON key，契约不可变。</p>
 */
public record BatchCountResult(
        int total,
        int successCount,
        int rejectedCount) {
}