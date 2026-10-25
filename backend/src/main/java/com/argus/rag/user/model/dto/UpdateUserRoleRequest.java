package com.argus.rag.user.model.dto;

import com.argus.rag.common.enums.SystemRole;
import jakarta.validation.constraints.NotNull;

/**
 * 管理员调整用户系统角色请求。
 */
public record UpdateUserRoleRequest(
        @NotNull(message = "系统角色不能为空") SystemRole systemRole
) {
}
