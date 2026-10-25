package com.argus.rag.excel.model.dto;

/** 文档分享授权请求：按 userId 或 username 授权 viewer/editor。 */
public record AclGrantRequest(
        Long userId,
        String username,
        String role
) {
}
