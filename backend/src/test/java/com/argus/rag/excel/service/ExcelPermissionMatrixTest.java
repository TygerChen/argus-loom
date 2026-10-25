package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentPermission;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExcelPermissionService#permissionOf(ExcelDocument, CurrentUserService.CurrentUser)}
 * 文档级权限矩阵行为锁（纯 JVM，mock 掉 DB 存取，**零生产改动**）。
 * <p>锁契约（照当前实现）：null 守卫 → 财务隔离门禁 → FINANCE 财务档=owner →
 * ADMIN=owner → 创建者=owner → ACL（role 空→viewer、否则小写化）；未命中 ACL→null。
 * canEdit/canManage 的文档+用户重载另锁：财务档对 ADMIN 隔离（canEdit/canManage 均 false）。</p>
 * <p>此测试仅只读锁定现有判定行为，不修改 {@code userCanAccessDocument} 判定实现（财务红线）。</p>
 */
@DisplayName("文档权限矩阵（ExcelPermissionService.permissionOf 等）")
class ExcelPermissionMatrixTest {

    private final ExcelDocumentMapper documentMapper = org.mockito.Mockito.mock(ExcelDocumentMapper.class);
    private final ExcelDocumentPermissionMapper permissionMapper =
            org.mockito.Mockito.mock(ExcelDocumentPermissionMapper.class);
    private final CurrentUserService currentUserService =
            org.mockito.Mockito.mock(CurrentUserService.class);

    private ExcelPermissionService svc;

    @BeforeEach
    void setUp() {
        svc = new ExcelPermissionService(documentMapper, permissionMapper, currentUserService);
    }

    private static CurrentUserService.CurrentUser user(Long id, SystemRole role) {
        return new CurrentUserService.CurrentUser(id, "U", "u", role, false);
    }

    private static ExcelDocument doc(boolean finance, String creatorId) {
        ExcelDocument d = new ExcelDocument();
        d.setId(11L);
        d.setFinanceRequired(finance);
        d.setCreatorId(creatorId);
        return d;
    }

    private static ExcelDocumentPermission acl(String role) {
        ExcelDocumentPermission p = new ExcelDocumentPermission();
        p.setRole(role);
        return p;
    }

    private void stubAcl(ExcelDocumentPermission p) {
        org.mockito.Mockito.when(permissionMapper.selectOne(
                        org.mockito.ArgumentMatchers.any(QueryWrapper.class)))
                .thenReturn(p);
    }

    // ---------- 守卫：null / 用户缺失 ----------

    @Test
    void nullDocOrNullUserYieldsNull() {
        assertNull(svc.permissionOf(null, user(1L, SystemRole.USER)));
        assertNull(svc.permissionOf(doc(false, "1"), null));
    }

    // ---------- 财务隔离门控 ----------

    @Test
    void financeDocHidesFromNonFinance() {
        ExcelDocument financeDoc = doc(true, "99");
        // 普通用户拿到财务档：不满足 userCanAccessDocument → null
        assertNull(svc.permissionOf(financeDoc, user(7L, SystemRole.USER)));
        // 系统管理员同样被财务隔离挡住
        assertNull(svc.permissionOf(financeDoc, user(9L, SystemRole.ADMIN)));
    }

    @Test
    void financeDocFinanceUserGetsOwner() {
        ExcelDocument financeDoc = doc(true, "99");
        assertEquals("owner", svc.permissionOf(financeDoc, user(8L, SystemRole.FINANCE)));
    }

    // ---------- 角色/归属优先级 ----------

    @Test
    void adminGetsOwnerOnNormalDoc() {
        assertEquals("owner", svc.permissionOf(doc(false, "5"), user(9L, SystemRole.ADMIN)));
    }

    @Test
    void creatorGetsOwner() {
        // creatorId 以 String 存，matches userId 的字符串
        assertEquals("owner", svc.permissionOf(doc(false, "7"), user(7L, SystemRole.USER)));
    }

    @Test
    void nonCreatorNonAclYieldsNull() {
        stubAcl(null);
        assertNull(svc.permissionOf(doc(false, "5"), user(7L, SystemRole.USER)));
    }

    // ---------- ACL 分支 ----------

    @Test
    void aclRoleLowercased() {
        stubAcl(acl("EDITOR"));
        assertEquals("editor", svc.permissionOf(doc(false, "5"), user(7L, SystemRole.USER)));
    }

    @Test
    void aclViewerLowercased() {
        stubAcl(acl("VIEWER"));
        assertEquals("viewer", svc.permissionOf(doc(false, "5"), user(7L, SystemRole.USER)));
    }

    @Test
    void aclNullRoleStillYieldsViewer() {
        // permission.getRole()==null → 三值兜底 "viewer"（区别于无 ACL 记录的 null）
        stubAcl(acl(null));
        assertEquals("viewer", svc.permissionOf(doc(false, "5"), user(7L, SystemRole.USER)));
    }

    @Test
    void creatorTakesPrecedenceOverConflictingAcl() {
        // 既是创建者又有编辑 ACL → 仍 owner（创建者分支先命中）
        stubAcl(acl("VIEWER"));
        assertEquals("owner", svc.permissionOf(doc(false, "7"), user(7L, SystemRole.USER)));
    }

    // ---------- canEdit / canManage 派生的财务隔离 ----------

    @Test
    void adminCannotEditOrManageFinanceDoc() {
        ExcelDocument financeDoc = doc(true, "99");
        assertFalse(svc.canEdit(financeDoc, user(9L, SystemRole.ADMIN)),
                "系统管理员对财务档不应可编辑");
        assertFalse(svc.canManage(financeDoc, user(9L, SystemRole.ADMIN)),
                "系统管理员对财务档不应可管理");
    }

    @Test
    void viewerRoleCannotEdit() {
        stubAcl(acl("VIEWER"));
        assertFalse(svc.canEdit(doc(false, "5"), user(7L, SystemRole.USER)));
    }

    @Test
    void editorRoleCanEdit() {
        stubAcl(acl("EDITOR"));
        assertTrue(svc.canEdit(doc(false, "5"), user(7L, SystemRole.USER)));
    }

    // ---------- 显式用户重载（WebSocket 通道：无 SecurityContext） ----------

    private void stubDoc(ExcelDocument d) {
        org.mockito.Mockito.when(documentMapper.selectById(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(d);
    }

    @Test
    void canViewByIdOwnerTrue() {
        stubDoc(doc(false, "7"));
        assertTrue(svc.canView(11L, user(7L, SystemRole.USER)), "创建者应可查看");
    }

    @Test
    void canViewByIdReaderTrue() {
        stubDoc(doc(false, "5"));
        stubAcl(acl("VIEWER"));
        assertTrue(svc.canView(11L, user(7L, SystemRole.USER)), "VIEWER 授权可查看");
    }

    @Test
    void canViewByIdUnauthorizedFalse() {
        stubDoc(doc(false, "5"));
        stubAcl(null);
        assertFalse(svc.canView(11L, user(7L, SystemRole.USER)), "无 ACL 且非创建者不可查看");
    }

    @Test
    void canViewByIdFinanceDocHiddenFromNonFinance() {
        stubDoc(doc(true, "5"));
        // 财务档对普通用户/管理员均隔离，显式用户判定保持一致
        assertFalse(svc.canView(11L, user(7L, SystemRole.USER)));
        assertFalse(svc.canView(11L, user(9L, SystemRole.ADMIN)));
        assertTrue(svc.canView(11L, user(8L, SystemRole.FINANCE)), "FINANCE 可查看财务档");
    }

    @Test
    void canEditByIdEditorTrueViewerFalse() {
        stubDoc(doc(false, "5"));
        stubAcl(acl("EDITOR"));
        assertTrue(svc.canEdit(11L, user(7L, SystemRole.USER)));
        stubAcl(acl("VIEWER"));
        assertFalse(svc.canEdit(11L, user(7L, SystemRole.USER)));
    }

    @Test
    void canEditByIdAdminDeniedOnFinanceDoc() {
        stubDoc(doc(true, "99"));
        assertFalse(svc.canEdit(11L, user(9L, SystemRole.ADMIN)), "管理员对财务档不可编辑（财务隔离）");
    }

    @Test
    void canViewByIdNullDocumentOrUserFalse() {
        assertFalse(svc.canView((Long) null, user(1L, SystemRole.USER)));
        stubDoc(doc(false, "1"));
        assertFalse(svc.canView(11L, (com.argus.rag.auth.CurrentUserService.CurrentUser) null));
    }
}