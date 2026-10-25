package com.argus.rag.excel.model.dto;

/**
 * 批量操作中单个被拒条目的原因。
 */
public record BatchRejectEntry(Long id, String reason) {
}