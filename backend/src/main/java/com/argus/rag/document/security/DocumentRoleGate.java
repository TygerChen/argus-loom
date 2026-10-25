package com.argus.rag.document.security;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档级角色门槛的单一授权节点。
 * <p>
 * 只道主：RAG 检索层唯一的“谁可读哪份文档”判定点。
 * <ul>
 *   <li>角色优先级（单点定义，禁止在其它处再复制）：{@code USER < FINANCE < ADMIN}，
 *       由 {@link #allowedRoleNames(SystemRole)} 一处实现，杜绝优先级散落导致错配。</li>
 *   <li>fail-closed 缺省：未认证/角色缺失 → 按最低权限 {@link SystemRole#USER} 处理；
 *       {@code required_role = null} 视为公开（等同 USER）。</li>
 *   <li>反负向（防存在性泄露）：<b>不允许访问 ≠ 明确拒绝</b>，而是让受限文档从检索结果中
 *       “消失”（等同无此信息）。调用方只拿到一份空的或更少的候选集，无法区分
 *       “无权”与“查无此文/无相关信息”，杜绝通过报错/空集差异试探受限文档的存在性。</li>
 * </ul>
 * </p>
 */
@Service
public class DocumentRoleGate {

    private final DocumentMapper documentMapper;

    public DocumentRoleGate(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /**
     * 当前请求的系统角色。未认证或角色缺失 → {@link SystemRole#USER}（最低权限，fail-closed）。
     */
    public SystemRole currentRole() {
        AuthenticatedUser user = UserContext.get();
        if (user == null || user.systemRole() == null) {
            return SystemRole.USER;
        }
        return user.systemRole();
    }

    /**
     * 当前用户（取 {@link UserContext}）在指定群组内可读的文档 ID 集合。
     * 只包含满足 required_role ≤ 用户角色的非删除 READY 文档。
     *
     * @param groupId 群组 ID
     * @return 允许检索的文档 ID 集合；groupId 非法时返回空集（无可检索）。不会返回 null。
     */
    public Set<Long> allowedDocumentIds(Long groupId) {
        return allowedDocumentIds(groupId, currentRole());
    }

    /**
     * 指定角色在指定群组内可读的文档 ID 集合（便于测试直接给定角色）。
     */
    public Set<Long> allowedDocumentIds(Long groupId, SystemRole role) {
        if (groupId == null || groupId <= 0) {
            return Set.of();
        }
        List<String> roleNames = allowedRoleNames(role);
        Set<Long> ids = new HashSet<>();
        for (DocumentEntity doc : documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getGroupId, groupId)
                .in(DocumentEntity::getRequiredRole, roleNames)
                .eq(DocumentEntity::getStatus, "READY")
                .eq(DocumentEntity::getDeleted, false))) {
            ids.add(doc.getId());
        }
        return ids;
    }

    /**
     * 角色优先级（单一权威）。返回该角色可穿透的全部 {@code required_role} 门槛值。
     * <pre>
     *   USER    → {USER}
     *   FINANCE → {USER, FINANCE}
     *   ADMIN   → {USER, FINANCE, ADMIN}
     * </pre>
     */
    public List<String> allowedRoleNames(SystemRole role) {
        if (role == null) {
            return List.of(SystemRole.USER.name());
        }
        return switch (role) {
            case ADMIN   -> List.of(SystemRole.USER.name(), SystemRole.FINANCE.name(), SystemRole.ADMIN.name());
            case FINANCE -> List.of(SystemRole.USER.name(), SystemRole.FINANCE.name());
            case USER    -> List.of(SystemRole.USER.name());
        };
    }

    /**
     * 判断某角色能否读取某 {@code required_role} 门槛的文档。
     * 门槛为 null 时视为公开。用于双保险：除检索过滤外，直接访问单个文档前再确认一次。
     */
    public boolean canRead(SystemRole role, String requiredRole) {
        if (requiredRole == null) {
            return true;
        }
        return allowedRoleNames(role).contains(requiredRole);
    }
}