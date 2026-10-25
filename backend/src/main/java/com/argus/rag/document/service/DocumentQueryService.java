package com.argus.rag.document.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.dto.DocumentQuery;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.model.vo.DocumentListItemVO;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.group.service.GroupMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 文档查询服务。
 */
@Service
public class DocumentQueryService {

    private final DocumentMapper documentMapper;
    private final GroupMembershipService groupMembershipService;
    private final CurrentUserService currentUserService;
    private final DocumentRoleGate roleGate;

    public DocumentQueryService(DocumentMapper documentMapper,
                                GroupMembershipService groupMembershipService,
                                CurrentUserService currentUserService,
                                DocumentRoleGate roleGate) {
        this.documentMapper = documentMapper;
        this.groupMembershipService = groupMembershipService;
        this.currentUserService = currentUserService;
        this.roleGate = roleGate;
    }

    /** 查询文档列表，按查询条件筛选当前用户有权查看的文档 */
    public List<DocumentListItemVO> listDocuments(DocumentQuery query) {
        DocumentQuery validatedQuery = normalizeQuery(query);
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        SystemRole role = currentUser.systemRole();
        // 读侧强制：列表逐项过角色门槛（与检索层共用 DocumentRoleGate 单一判定点），
        // 未达门槛的文档对当前用户如同不存在，防止经列表泄露。
        return documentMapper.selectReadableDocuments(validatedQuery).stream()
                .filter(item -> roleGate.canRead(role, item.getRequiredRole()))
                .toList();
    }

    /**
     * 设置文档的角色门槛（USER/FINANCE/ADMIN）。调用方需已通过财务/领导校验（FINANCE 角色）。
     * 只修改 required_role 列，不动文档内容，检索层与读侧即时生效。
     */
    public void setRequiredRole(Long documentId, Long groupId, String requiredRole) {
        requireGroupId(groupId);
        if (documentId == null || documentId <= 0) {
            throw new BusinessException("文档ID非法");
        }
        if (!StringUtils.hasText(requiredRole)) {
            throw new BusinessException("requiredRole 不能为空");
        }
        String normalized;
        try {
            normalized = SystemRole.valueOf(requiredRole.trim().toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("requiredRole 非法，仅支持 USER/FINANCE/ADMIN");
        }
        DocumentEntity document = documentMapper.selectByIdAndGroupId(documentId, groupId);
        if (document == null) {
            throw new BusinessException(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在或已删除");
        }
        document.setRequiredRole(normalized);
        documentMapper.updateById(document);
    }

    private DocumentQuery normalizeQuery(DocumentQuery query) {
        DocumentQuery safeQuery = query == null ? new DocumentQuery() : query;
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        safeQuery.setCurrentUserId(currentUser.userId());
        if (safeQuery.getGroupId() != null) {
            requireGroupId(safeQuery.getGroupId());
            groupMembershipService.requireGroupReadable(safeQuery.getGroupId());
        }
        if (safeQuery.getUploaderUserId() != null && safeQuery.getUploaderUserId() <= 0) {
            throw new BusinessException("uploaderUserId 非法");
        }
        if (safeQuery.getUploadedFrom() != null
                && safeQuery.getUploadedTo() != null
                && safeQuery.getUploadedFrom().isAfter(safeQuery.getUploadedTo())) {
            throw new BusinessException("uploadedFrom 不能晚于 uploadedTo");
        }
        if (StringUtils.hasText(safeQuery.getGroupRelation())) {
            safeQuery.setGroupRelation(normalizeGroupRelation(safeQuery.getGroupRelation()));
        }
        if (StringUtils.hasText(safeQuery.getStatus())) {
            safeQuery.setStatus(normalizeStatus(safeQuery.getStatus()));
        }
        if (StringUtils.hasText(safeQuery.getFileName())) {
            safeQuery.setFileName(safeQuery.getFileName().trim());
        }
        return safeQuery;
    }

    private static String normalizeGroupRelation(String groupRelation) {
        String normalized = groupRelation.trim().toUpperCase();
        return switch (normalized) {
            case "OWNER", "OWNED" -> "OWNED";
            case "MEMBER", "JOINED" -> "JOINED";
            default -> throw new BusinessException("groupRelation 非法");
        };
    }

    private static String normalizeStatus(String status) {
        try {
            return DocumentStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("status 非法");
        }
    }

    private void requireGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException("groupId 非法");
        }
    }
}
