package com.argus.rag.excel.model.vo;

/**
 * 文档所有权转移结果视图。
 *
 * @param documentId    被转移文档 ID
 * @param newOwnerId    新所有者用户 ID
 * @param newOwnerName  新所有者用户名（登录标识）
 */
public record TransferOwnerVO(
        Long documentId,
        Long newOwnerId,
        String newOwnerName
) {
}