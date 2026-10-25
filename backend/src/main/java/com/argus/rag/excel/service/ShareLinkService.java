package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.excel.entity.ExcelDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分享链接业务服务。
 *
 * <p>收敛「生成 / 撤销 / 解析分享链接」的持久化（DB 生效 + 7 天过期）与财务门控，
 * 使 {@code ShareController} 不再直接持有 {@link JdbcTemplate} 手写 SQL，也不散落
 * {@code systemRole==FINANCE} 字面量判断——统一委托 {@link ExcelPermissionService} 的
 * 财务隔离门禁。</p>
 */
@Service
public class ShareLinkService {

    private static final List<String> ALLOWED_ROLES = List.of("VIEWER", "EDITOR");

    private final JdbcTemplate jdbcTemplate;
    private final ExcelDocumentService documentService;
    private final CurrentUserService currentUserService;
    private final ExcelPermissionService permissionService;

    public ShareLinkService(JdbcTemplate jdbcTemplate,
                            ExcelDocumentService documentService,
                            CurrentUserService currentUserService,
                            ExcelPermissionService permissionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
        this.currentUserService = currentUserService;
        this.permissionService = permissionService;
    }

    /** 生成分享链接。 */
    public ShareLink createShare(Long documentId, String role) {
        ExcelDocument document = documentService.getById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        }
        // 分享会把内容授予他人，属管理动作：仅 owner（或有权管理员）可发起。
        if (!permissionService.canManage(documentId)) {
            throw new ForbiddenException("没有管理该文档的权限");
        }
        // 财务文件禁止通过分享链接外泄：仅财务 / 领导可创建。
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        if (permissionService.isFinanceDocument(document)
                && !permissionService.userCanAccessDocument(document, user)) {
            throw new BusinessException(ErrorCodes.EXCEL_FINANCE_FORBIDDEN, "财务文件不允许生成分享链接");
        }
        if (!ALLOWED_ROLES.contains(role)) {
            throw new BusinessException(ErrorCodes.EXCEL_SHARE_INVALID, "角色无效，可选 VIEWER 或 EDITOR");
        }

        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update(
                "INSERT INTO excel_document_share (token, document_id, role, created_by, expires_at)"
                        + " VALUES (?,?,?,?, NOW() + INTERVAL '7 days')",
                token, documentId, role, user.userId()
        );
        return new ShareLink(token, role);
    }

    /** 撤销分享链接。 */
    public void revokeShare(Long documentId, String token) {
        if (!permissionService.canManage(documentId)) {
            throw new ForbiddenException("没有管理该文档的权限");
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM excel_document_share WHERE token=? AND document_id=?", token, documentId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCodes.EXCEL_SHARE_INVALID, "分享链接不存在");
        }
    }

    /** 解析分享链接，返回目标文档与角色；无效/过期/无权限访问财务内容时抛业务异常。 */
    public ShareResolved resolveShare(String token) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT document_id, role, expires_at FROM excel_document_share"
                        + " WHERE token=? AND expires_at > NOW()", token);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXCEL_SHARE_INVALID, "分享链接无效或已过期");
        }
        Map<String, Object> row = rows.get(0);
        Long documentId = ((Number) row.get("document_id")).longValue();
        ExcelDocument document = documentService.optionalById(documentId);
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        if (document != null
                && permissionService.isFinanceDocument(document)
                && !permissionService.userCanAccessDocument(document, user)) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "无权访问该分享内容");
        }
        return new ShareResolved(documentId, (String) row.get("role"));
    }

    /** 分享链接（token + 角色）。 */
    public record ShareLink(String token, String role) {
    }

    /** 分享链接解析结果（目标文档 + 角色）。 */
    public record ShareResolved(Long documentId, String role) {
    }
}
