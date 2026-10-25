package com.argus.rag.excel.model.vo;

/**
 * 文档分享（授权）结果视图。
 *
 * @param documentId 被授权文档 ID
 * @param userId     被授权用户 ID
 * @param username   被授权用户名（登录标识）
 * @param role       授予的角色：OWNER / EDITOR / VIEWER
 */
public record GrantResultVO(
        Long documentId,
        Long userId,
        String username,
        String role
) {
}