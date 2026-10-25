package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentPermission;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.argus.rag.excel.model.dto.AclGrantRequest;
import com.argus.rag.user.service.UserQueryService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentAccessService} 文档 ACL/授权契约（mock 4 协作方：permissionMapper/documentService/
 * userQuery/currentUser，纯 JVM/零 DB；查询用字符串 QueryWrapper 避开 MP 运行时缓存）：
 * - grant：文档缺失/授权用户不存在→EXCEL_NOT_FOUND、所有者无需重复分享→EXCEL_SHARE_INVALID、
 *   role 归一（null/乱值→VIEWER、EDITOR/OWNER 保留）、新增授权走 insert 落库（canShare=0）+ 结果视图。
 *   （已存在授权分支因 update 构造 LambdaUpdateWrapper 依赖 MP 运行时缓存，同 FinancePdfRenderer 先例不做单测）
 * - revoke：移除所有者权限→PERMISSION_DENIED，否则按 document_id+user_id 字符串式 delete；userId 空短路；
 * - listAcl：文档缺失→EXCEL_NOT_FOUND、owner 记录（OWNER）+ 逐授权记录 + role 空回退 VIEWER；
 * - transferOwner：文档缺失/新所有者不存在→EXCEL_NOT_FOUND、成功→documentService.transferOwner +
 *   原所有者降 EDITOR + 新所有者升 OWNER（均走新增 insert 支路）。
 */
@DisplayName("文档 ACL 授权（DocumentAccessService）")
class DocumentAccessServiceTest {

    private ExcelDocumentPermissionMapper permissionMapper;
    private ExcelDocumentService documentService;
    private UserQueryService userQueryService;
    private CurrentUserService currentUserService;
    private DocumentAccessService svc;

    @BeforeEach
    void setUp() {
        permissionMapper = mock(ExcelDocumentPermissionMapper.class);
        documentService = mock(ExcelDocumentService.class);
        userQueryService = mock(UserQueryService.class);
        currentUserService = mock(CurrentUserService.class);
        svc = new DocumentAccessService(permissionMapper, documentService, userQueryService, currentUserService);
    }

    private static ExcelDocument doc(Long id, String creatorId) {
        ExcelDocument d = new ExcelDocument();
        d.setId(id);
        d.setCreatorId(creatorId);
        d.setStatus(1);
        return d;
    }

    private static UserQueryService.UserRecord user(Long id, String code) {
        return new UserQueryService.UserRecord(id, code, "显示 " + id, SystemRole.USER, UserStatus.ACTIVE, false);
    }

    // ---------- grant ----------

    @Test
    void grantRejectsMissingDocument() {
        when(documentService.getById(10L)).thenThrow(new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.grant(10L, new AclGrantRequest(7L, null, "VIEWER")));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, e.getCode());
    }

    @Test
    void grantRejectsMissingTargetUser() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.grant(10L, new AclGrantRequest(7L, null, "VIEWER")));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, e.getCode());
    }

    @Test
    void grantRejectsSelfShare() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "7"));
        when(userQueryService.findById(7L)).thenReturn(user(7L, "u7"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.grant(10L, new AclGrantRequest(7L, null, "EDITOR")));
        assertEquals(ErrorCodes.EXCEL_SHARE_INVALID, e.getCode());
        assertEquals("文档所有者无需重复分享", e.getMessage());
    }

    @Test
    void grantResolvesByUsernameAndNormalizesRole() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findByUserCodeOrUsername("bob")).thenReturn(user(2L, "bob"));
        when(permissionMapper.selectOne(any())).thenReturn(null);   // 无既有授权 → insert 支路
        var out = svc.grant(10L, new AclGrantRequest(null, "bob", "viewer"));
        ArgumentCaptor<ExcelDocumentPermission> cap = ArgumentCaptor.forClass(ExcelDocumentPermission.class);
        verify(permissionMapper).insert(cap.capture());
        ExcelDocumentPermission p = cap.getValue();
        assertEquals(10L, p.getDocumentId());
        assertEquals(2L, p.getUserId());
        assertEquals("VIEWER", p.getRole());        // 乱码/小写 → 归一 VIEWER
        assertEquals(0, p.getCanShare());
        assertEquals(10L, out.documentId());
        assertEquals(2L, out.userId());
        assertEquals("bob", out.username());
        assertEquals("VIEWER", out.role());
    }

    @Test
    void grantKeepsEditorAndOwnerRoles() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(2L)).thenReturn(user(2L, "u2"));
        when(permissionMapper.selectOne(any())).thenReturn(null);
        svc.grant(10L, new AclGrantRequest(2L, null, "EDITOR"));
        ArgumentCaptor<ExcelDocumentPermission> cap = ArgumentCaptor.forClass(ExcelDocumentPermission.class);
        verify(permissionMapper).insert(cap.capture());
        assertEquals("EDITOR", cap.getValue().getRole());

        when(permissionMapper.selectOne(any())).thenReturn(null);
        svc.grant(10L, new AclGrantRequest(2L, null, "  OWNER  "));
        ArgumentCaptor<ExcelDocumentPermission> cap2 = ArgumentCaptor.forClass(ExcelDocumentPermission.class);
        verify(permissionMapper, org.mockito.Mockito.times(2)).insert(cap2.capture());
        assertEquals("OWNER", cap2.getAllValues().get(1).getRole());
    }

    // ---------- revoke ----------

    @Test
    void revokeRejectsRemovingOwnerPermission() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "7"));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.revoke(10L, 7L));
        assertEquals(ErrorCodes.PERMISSION_DENIED, e.getCode());
        verify(permissionMapper, never()).delete(any());
    }

    @Test
    void revokeDeletesByDocumentAndUserOthers() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        svc.revoke(10L, 2L);
        verify(permissionMapper).delete(any(QueryWrapper.class));
    }

    @Test
    void revokeStillDeletesEvenForNullUserId() {
        // revoke 的 owner 守卫仅当 userId 非空时检查；userId=null 时守卫跳过但 delete 仍会执行（user_id=null 过滤）。
        when(documentService.getById(10L)).thenReturn(doc(10L, "7"));
        svc.revoke(10L, null);
        verify(permissionMapper).delete(any(QueryWrapper.class));
    }

    // ---------- listAcl ----------

    @Test
    void listAclRejectsMissingDocument() {
        when(documentService.getById(10L)).thenThrow(new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在"));
        assertThrows(BusinessException.class, () -> svc.listAcl(10L));
    }

    @Test
    void listAclOwnerFirstThenPermissionsWithViewerFallback() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(1L)).thenReturn(user(1L, "owner"));
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm(5L, 10L, 2L, "EDITOR"), perm(6L, 10L, 3L, null)));
        when(userQueryService.findById(2L)).thenReturn(user(2L, "u2"));
        when(userQueryService.findById(3L)).thenReturn(user(3L, "u3"));
        var acl = svc.listAcl(10L);
        assertEquals(3, acl.size());
        assertEquals(1L, acl.get(0).userId());
        assertEquals("OWNER", acl.get(0).role());
        assertEquals(2L, acl.get(1).userId());
        assertEquals("EDITOR", acl.get(1).role());
        assertEquals(3L, acl.get(2).userId());
        assertEquals("VIEWER", acl.get(2).role());   // role 空 → 回退 VIEWER
    }

    @Test
    void listAclSkipsUnknownUsers() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(1L)).thenReturn(user(1L, "owner"));
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm(5L, 10L, 99L, "EDITOR")));
        when(userQueryService.findById(99L)).thenReturn(null);   // 已删除用户 → 跳过
        var acl = svc.listAcl(10L);
        assertEquals(1, acl.size());
        assertEquals("OWNER", acl.get(0).role());
    }

    // ---------- transferOwner ----------

    @Test
    void transferOwnerRejectsMissingDocument() {
        when(documentService.getById(10L)).thenThrow(new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在"));
        assertThrows(BusinessException.class, () -> svc.transferOwner(10L, 2L));
    }

    @Test
    void transferOwnerRejectsMissingNewOwner() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(null)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.transferOwner(10L, null));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, e.getCode());
    }

    @Test
    void transferOwnerUpdatesAndReassignsRoles() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(2L)).thenReturn(user(2L, "newowner"));
        when(permissionMapper.selectOne(any())).thenReturn(null);   // 新旧所有者均无既有授权 → insert 支路
        var out = svc.transferOwner(10L, 2L);
        verify(documentService).transferOwner(10L, 2L);
        ArgumentCaptor<ExcelDocumentPermission> cap = ArgumentCaptor.forClass(ExcelDocumentPermission.class);
        verify(permissionMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        ExcelDocumentPermission oldRole = cap.getAllValues().get(0);
        assertEquals(1L, oldRole.getUserId());
        assertEquals("EDITOR", oldRole.getRole());        // 原所有者降为编辑
        ExcelDocumentPermission newRole = cap.getAllValues().get(1);
        assertEquals(2L, newRole.getUserId());
        assertEquals("OWNER", newRole.getRole());         // 新所有者升为 OWNER
        assertEquals(10L, out.documentId());
        assertEquals(2L, out.newOwnerId());
        assertEquals("newowner", out.newOwnerName());
    }

    @Test
    void transferOwnerSameOwnerDoesNotMoveRoles() {
        when(documentService.getById(10L)).thenReturn(doc(10L, "1"));
        when(userQueryService.findById(1L)).thenReturn(user(1L, "owner"));
        svc.transferOwner(10L, 1L);
        verify(documentService).transferOwner(10L, 1L);
        // 仅新(同)所有者 OWNER 一条；因新==旧所有者，原所有者不触发降级 EDITOR
        verify(permissionMapper, org.mockito.Mockito.times(1)).insert(any(ExcelDocumentPermission.class));
    }

    // ---------- helpers ----------

    private static ExcelDocumentPermission perm(Long id, Long docId, Long userId, String role) {
        ExcelDocumentPermission p = new ExcelDocumentPermission();
        p.setId(id);
        p.setDocumentId(docId);
        p.setUserId(userId);
        p.setRole(role);
        p.setCanShare(0);
        return p;
    }
}