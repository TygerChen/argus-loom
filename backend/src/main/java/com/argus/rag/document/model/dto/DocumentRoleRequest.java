package com.argus.rag.document.model.dto;

import lombok.Data;

/**
 * 设置文档角色门槛请求 DTO。
 * <p>
 * 仅财务/领导（FINANCE 角色）可调用，将文档标记为指定 {@code required_role}：
 * USER（全员可读）/ FINANCE（财务/领导+管理员可读）/ ADMIN（仅管理员）。
 * </p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Data
public class DocumentRoleRequest {

    /** 文档所属群组 ID */
    private Long groupId;

    /** 目标角色门槛：USER / FINANCE / ADMIN */
    private String requiredRole;
}