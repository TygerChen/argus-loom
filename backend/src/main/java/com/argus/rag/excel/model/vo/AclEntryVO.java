package com.argus.rag.excel.model.vo;

/**
 * ACL 列表条目：单个文档的一条分享（所有者或协作者）视图。
 *
 * @param userId       被授权用户 ID
 * @param username     用户名（登录标识）
 * @param displayName  显示名
 * @param role         角色：OWNER / EDITOR / VIEWER
 */
public record AclEntryVO(
        Long userId,
        String username,
        String displayName,
        String role
) {
}