package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentPermission;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 文档级权限计算器：判断当前用户对某份文档能否查看、编辑、管理。
 * <p>
 * 权限优先级：财务文件隔离 &gt; 系统管理员 &gt; 文档创建者（owner）&gt; ACL 授权（EDITOR/VIEWER）。
 * 财务/领导导入的财务文件（finance_required=true）仅 FINANCE 角色可见；
 * 普通员工和系统管理员不允许通过 ACL、分享、导出、回收站间接看到。
 */
@Service
public class ExcelPermissionService {

    private final ExcelDocumentMapper documentMapper;
    private final ExcelDocumentPermissionMapper permissionMapper;
    private final CurrentUserService currentUserService;

    public ExcelPermissionService(ExcelDocumentMapper documentMapper,
                                  ExcelDocumentPermissionMapper permissionMapper,
                                  CurrentUserService currentUserService) {
        this.documentMapper = documentMapper;
        this.permissionMapper = permissionMapper;
        this.currentUserService = currentUserService;
    }

    public CurrentUserService.CurrentUser currentUser() {
        return currentUserService.getRequiredCurrentUser();
    }

    public boolean isAdmin() {
        return currentUser().systemRole() == SystemRole.ADMIN;
    }

    /** 当前用户是否为财务/领导角色。 */
    public boolean isFinance() {
        return currentUser().systemRole() == SystemRole.FINANCE;
    }

    /** 文档是否财务文件（finance_required=true）。 */
    public boolean isFinanceDocument(ExcelDocument document) {
        return document != null && Boolean.TRUE.equals(document.getFinanceRequired());
    }

    /** 财务隔离门禁：财务文件仅 FINANCE 角色可进入后续权限判定。 */
    public boolean userCanAccessDocument(ExcelDocument document,
                                         CurrentUserService.CurrentUser user) {
        if (document == null || user == null) return false;
        if (isFinanceDocument(document)) {
            return user.systemRole() == SystemRole.FINANCE;
        }
        return true;
    }

    /** 计算当前用户对文档的权限等级（owner / editor / viewer / null）。 */
    public String permissionOf(Long documentId) {
        ExcelDocument document = documentId == null ? null : documentMapper.selectById(documentId);
        if (document == null) return null;
        return permissionOf(document, currentUser());
    }

    /**
     * 权限判定的核心：一个"短路链条"从高到低过四道关卡
     * （03 篇的酒店房卡比喻落地在这）：
     * ① 财务隔离（最高优先级）：财务文件只有 FINANCE 角色能过这道门，
     *    连系统管理员也被挡在外面——管理员账号被盗 ≠ 财务数据泄露；
     * ② 财务角色对财务文件直接视为 owner（财务文件由财务内部自治）；
     * ③ 系统管理员视为 owner（管系统不管数据，仅对非财务文件生效）；
     * ④ 创建者视为 owner；⑤ 最后查 ACL 授权表（viewer/editor）。
     * 任何一道命中立即返回，全不命中返回 null = 无权限。
     * <b>顺序就是安全等级</b>——把 ACL 挪到财务隔离前面就是越权漏洞。
     */
    public String permissionOf(ExcelDocument document, CurrentUserService.CurrentUser user) {
        if (document == null || user == null) return null;
        if (!userCanAccessDocument(document, user)) return null;
        if (isFinanceDocument(document) && user.systemRole() == SystemRole.FINANCE) {
            return "owner";
        }
        if (user.systemRole() == SystemRole.ADMIN) return "owner";
        if (user.userId().toString().equals(String.valueOf(document.getCreatorId()))) return "owner";
        ExcelDocumentPermission permission = findPermission(document.getId(), user.userId());
        if (permission == null) return null;
        return permission.getRole() == null ? "viewer" : permission.getRole().toLowerCase();
    }

    public boolean canView(Long documentId) {
        return permissionOf(documentId) != null;
    }

    /** canView = 权限等级非空（viewer 及以上）；canEdit = owner 或 editor；canManage = 仅 owner。 */
    public boolean canEdit(Long documentId) {
        String level = permissionOf(documentId);
        return "owner".equals(level) || "editor".equals(level) || isAdmin();
    }

    /**
     * 基于已加载的文档与用户计算查看权限，避免在 WebSocket 线程（无 SecurityContext）
     * 走 {@link #currentUser()}。复用 {@link #permissionOf(ExcelDocument, CurrentUserService.CurrentUser)}
     * 判定，不触碰财务隔离逻辑。
     */
    public boolean canView(Long documentId, CurrentUserService.CurrentUser user) {
        ExcelDocument document = documentId == null ? null : documentMapper.selectById(documentId);
        if (document == null || user == null) {
            return false;
        }
        return permissionOf(document, user) != null;
    }

    /** {@code canView} 的文档+用户重载（基于已加载文档，避免重复回查）。 */
    public boolean canView(ExcelDocument document, CurrentUserService.CurrentUser user) {
        if (document == null || user == null) {
            return false;
        }
        return permissionOf(document, user) != null;
    }

    /**
     * {@code canEdit} 的显式用户重载，供 WebSocket 通道复用既有判定：
     * {@link #canEdit(ExcelDocument, CurrentUserService.CurrentUser)}。
     */
    public boolean canEdit(Long documentId, CurrentUserService.CurrentUser user) {
        ExcelDocument document = documentId == null ? null : documentMapper.selectById(documentId);
        if (document == null || user == null) {
            return false;
        }
        return canEdit(document, user);
    }

    /** require 系列：不满足直接抛 403，控制器里一行完成"门禁"。 */
    public void requireView(Long documentId, CurrentUserService.CurrentUser user) {
        if (!canView(documentId, user)) throw new ForbiddenException("没有查看该文档的权限");
    }

    public void requireEdit(Long documentId, CurrentUserService.CurrentUser user) {
        if (!canEdit(documentId, user)) throw new ForbiddenException("没有编辑该文档的权限");
    }

    /** 基于已加载的文档与用户计算编辑权限，避免页面上逐条回查数据库。 */
    public boolean canEdit(ExcelDocument document, CurrentUserService.CurrentUser user) {
        String level = permissionOf(document, user);
        return "owner".equals(level) || "editor".equals(level)
                || (user != null && user.systemRole() == SystemRole.ADMIN
                    && userCanAccessDocument(document, user));
    }

    public boolean canManage(Long documentId) {
        return "owner".equals(permissionOf(documentId)) || isAdmin();
    }

    /** 基于已加载的文档与用户计算管理权限（删除、分享、转移所有权）。 */
    public boolean canManage(ExcelDocument document, CurrentUserService.CurrentUser user) {
        return "owner".equals(permissionOf(document, user))
                || (user != null && user.systemRole() == SystemRole.ADMIN
                    && userCanAccessDocument(document, user));
    }

    public void requireView(Long documentId) {
        if (!canView(documentId)) throw new ForbiddenException("没有查看该文档的权限");
    }

    public void requireEdit(Long documentId) {
        if (!canEdit(documentId)) throw new ForbiddenException("没有编辑该文档的权限");
    }

    public void requireManage(Long documentId) {
        if (!canManage(documentId)) throw new ForbiddenException("没有管理该文档的权限");
    }

    /** 当前用户可访问的文档 ID 列表（自己的 + 被分享的，已剔除不可见的财务文件）。 */
    /**
     * 可访问文档 ID 清单（批量场景用，如回收站列表过滤）。
     * 注意 admin 返回空列表的语义：不是"没有权限"，而是"admin 走普通查询
     * 不需要这份清单"（调用方对 admin 直接通查）。SQL 子查询把财务文件
     * 从非财务用户的 ACL 结果里剔除——权限过滤下沉到 SQL，避免查回来再筛。
     */
    public List<Long> accessibleDocumentIds() {
        CurrentUserService.CurrentUser user = currentUser();
        if (user.systemRole() == SystemRole.ADMIN) {
            return Collections.emptyList(); // admin 按普通可见口径直接通查
        }
        QueryWrapper<ExcelDocumentPermission> qw = new QueryWrapper<>();
        qw.eq("user_id", user.userId()).select("document_id");
        if (user.systemRole() != SystemRole.FINANCE) {
            qw.inSql("document_id",
                    "SELECT id FROM excel_document WHERE finance_required = FALSE");
        }
        return permissionMapper.selectList(qw).stream()
                .map(ExcelDocumentPermission::getDocumentId)
                .distinct()
                .toList();
    }

    public ExcelDocumentPermission findPermission(Long documentId, Long userId) {
        QueryWrapper<ExcelDocumentPermission> qw = new QueryWrapper<>();
        qw.eq("document_id", documentId).eq("user_id", userId).last("LIMIT 1");
        return permissionMapper.selectOne(qw);
    }

    public ExcelDocument requireNormalDocument(Long documentId) {
        ExcelDocument document = documentMapper.selectById(documentId);
        if (document == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        if (document.getStatus() != null && document.getStatus() == 3) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        if (document.getStatus() != null && document.getStatus() == 2) throw new BusinessException(ErrorCodes.EXCEL_TRASH_STATE, "文档已移入回收站");
        return document;
    }

}
