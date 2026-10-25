package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentPermission;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.argus.rag.excel.model.dto.AclGrantRequest;
import com.argus.rag.excel.model.vo.AclEntryVO;
import com.argus.rag.excel.model.vo.GrantResultVO;
import com.argus.rag.excel.model.vo.TransferOwnerVO;
import com.argus.rag.user.service.UserQueryService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分享（ACL）操作：按用户授权 viewer/editor、撤销、查看列表、转移所有权。
 *
 * <p><b>ACL 是什么</b>（03 篇权限模型的最后一层）：权限优先级是
 * "财务隔离 > 系统管理员 > 创建者 > ACL 授权"。前两级和创建者是"身份自带"的，
 * ACL（Access Control List，访问控制列表）则是创建者主动授予的：
 * excel_document_permission 表里一行 = "某用户对某文档是 viewer 还是 editor"。
 * 权限判定（ExcelPermissionService.permissionOf）最后一步就是查这张表。</p>
 *
 * <p><b>本类只管"授权动作"本身</b>：能不能授权（只有 owner 能分享）、
 * 给谁授权什么角色、撤销、转移所有权。判定"某用户当前对文档有什么权限"
 * 在 ExcelPermissionService——动作与判定分离，职责清晰。</p>
 */
@Service
public class DocumentAccessService {

    private final ExcelDocumentPermissionMapper permissionMapper;
    private final ExcelDocumentService documentService;
    private final UserQueryService userQueryService;
    private final CurrentUserService currentUserService;

    public DocumentAccessService(ExcelDocumentPermissionMapper permissionMapper,
                                 ExcelDocumentService documentService,
                                 UserQueryService userQueryService,
                                 CurrentUserService currentUserService) {
        this.permissionMapper = permissionMapper;
        this.documentService = documentService;
        this.userQueryService = userQueryService;
        this.currentUserService = currentUserService;
    }

    @Transactional(rollbackFor = Exception.class)
    public GrantResultVO grant(Long documentId, AclGrantRequest request) {
        ExcelDocument document = documentService.getById(documentId);
        if (document == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        UserQueryService.UserRecord target = resolveUser(request);
        if (target == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "授权用户不存在");
        if (String.valueOf(target.userId()).equals(String.valueOf(document.getCreatorId()))) {
            throw new BusinessException(ErrorCodes.EXCEL_SHARE_INVALID, "文档所有者无需重复分享");
        }
        String role = normalizeRole(request.role());
        ExcelDocumentPermission exist = findPermission(documentId, target.userId());
        if (exist == null) {
            ExcelDocumentPermission permission = new ExcelDocumentPermission();
            permission.setDocumentId(documentId);
            permission.setUserId(target.userId());
            permission.setRole(role);
            permission.setCanShare(0);
            permissionMapper.insert(permission);
        } else {
            LambdaUpdateWrapper<ExcelDocumentPermission> uw = new LambdaUpdateWrapper<>();
            uw.eq(ExcelDocumentPermission::getDocumentId, documentId)
                    .eq(ExcelDocumentPermission::getUserId, target.userId())
                    .set(ExcelDocumentPermission::getRole, role);
            permissionMapper.update(null, uw);
        }
        return new GrantResultVO(documentId, target.userId(), target.userCode(), role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long documentId, Long userId) {
        ExcelDocument document = documentService.getById(documentId);
        if (document != null && userId != null
                && String.valueOf(userId).equals(String.valueOf(document.getCreatorId()))) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "不能移除文档所有者的权限");
        }
        permissionMapper.delete(new QueryWrapper<ExcelDocumentPermission>()
                .eq("document_id", documentId)
                .eq("user_id", userId));
    }

    public List<AclEntryVO> listAcl(Long documentId) {
        ExcelDocument document = documentService.getById(documentId);
        if (document == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        List<AclEntryVO> records = new ArrayList<>();
        Long creatorId = parseLong(document.getCreatorId());
        if (creatorId != null) {
            UserQueryService.UserRecord owner = userQueryService.findById(creatorId);
            if (owner != null) {
                records.add(aclView(owner, "OWNER"));
            }
        }
        List<ExcelDocumentPermission> permissions = permissionMapper.selectList(
                new QueryWrapper<ExcelDocumentPermission>()
                        .eq("document_id", documentId)
                        .orderByAsc("id"));
        for (ExcelDocumentPermission permission : permissions) {
            UserQueryService.UserRecord user = userQueryService.findById(permission.getUserId());
            if (user != null) {
                records.add(aclView(user, permission.getRole() == null ? "VIEWER" : permission.getRole()));
            }
        }
        return records;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransferOwnerVO transferOwner(Long documentId, Long newOwnerId) {
        ExcelDocument document = documentService.getById(documentId);
        if (document == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        UserQueryService.UserRecord target = newOwnerId == null ? null : userQueryService.findById(newOwnerId);
        if (target == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "新所有者不存在");
        Long oldOwnerId = parseLong(document.getCreatorId());
        documentService.transferOwner(documentId, target.userId());
        // 原所有者降为可编辑协作者；新所有者升级为 OWNER。
        if (oldOwnerId != null && !oldOwnerId.equals(target.userId())) {
            grantInternal(documentId, oldOwnerId, "EDITOR");
        }
        grantInternal(documentId, target.userId(), "OWNER");
        return new TransferOwnerVO(documentId, target.userId(), target.userCode());
    }

    private void grantInternal(Long documentId, Long userId, String role) {
        ExcelDocumentPermission exist = findPermission(documentId, userId);
        if (exist == null) {
            ExcelDocumentPermission permission = new ExcelDocumentPermission();
            permission.setDocumentId(documentId);
            permission.setUserId(userId);
            permission.setRole(role);
            permission.setCanShare(0);
            permissionMapper.insert(permission);
        } else {
            LambdaUpdateWrapper<ExcelDocumentPermission> uw = new LambdaUpdateWrapper<>();
            uw.eq(ExcelDocumentPermission::getDocumentId, documentId)
                    .eq(ExcelDocumentPermission::getUserId, userId)
                    .set(ExcelDocumentPermission::getRole, role);
            permissionMapper.update(null, uw);
        }
    }

    private ExcelDocumentPermission findPermission(Long documentId, Long userId) {
        return permissionMapper.selectOne(new QueryWrapper<ExcelDocumentPermission>()
                .eq("document_id", documentId)
                .eq("user_id", userId)
                .last("LIMIT 1"));
    }

    private UserQueryService.UserRecord resolveUser(AclGrantRequest request) {
        if (request.userId() != null) {
            return userQueryService.findById(request.userId());
        }
        if (request.username() != null && !request.username().isBlank()) {
            return userQueryService.findByUserCodeOrUsername(request.username());
        }
        return null;
    }

    private static String normalizeRole(String role) {
        if (role == null) return "VIEWER";
        String upper = role.trim().toUpperCase();
        return "EDITOR".equals(upper) || "OWNER".equals(upper) ? upper : "VIEWER";
    }

    private static AclEntryVO aclView(UserQueryService.UserRecord user, String role) {
        return new AclEntryVO(user.userId(), user.userCode(), user.displayName(), role);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
