package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.excel.entity.ExcelDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link ShareLinkService} 业务/财务分享门控契约：
 * 生成（文档存在、角色白名单、财务文件仅财务/领导可建分享）与解析（无效/过期拒、财务无权限拒）
 * 均须走正确错误码；成功路径落地 SQL 并返回对应结果。
 * 全部 mock，不接 DB。含财务红线反例（EXCEL_FINANCE_FORBIDDEN / PERMISSION_DENIED）。
 */
@DisplayName("分享链接业务与财务门控（ShareLinkService）")
class ShareLinkServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ExcelDocumentService documentService;
    private ExcelPermissionService permissionService;
    private ShareLinkService service;

    private final CurrentUserService.CurrentUser viewer =
            new CurrentUserService.CurrentUser(7L, "U7", "会计");

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        documentService = mock(ExcelDocumentService.class);
        permissionService = mock(ExcelPermissionService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(viewer);
        service = new ShareLinkService(jdbcTemplate, documentService, currentUserService, permissionService);
        // 默认放行管理权，只有专门验证管理权红线的用例才显式置 false。
        lenient().when(permissionService.canManage(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    private ExcelDocument financeDocument() {
        ExcelDocument doc = new ExcelDocument();
        doc.setId(1L);
        when(permissionService.isFinanceDocument(doc)).thenReturn(true);
        return doc;
    }

    @Test
    void createShareRejectsMissingDocument() {
        when(documentService.getById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createShare(1L, "VIEWER"));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void createShareOnFinanceDocumentDeniedWithoutAccess() {
        ExcelDocument doc = financeDocument();
        when(documentService.getById(1L)).thenReturn(doc);
        when(permissionService.userCanAccessDocument(doc, viewer)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createShare(1L, "VIEWER"));
        assertEquals(ErrorCodes.EXCEL_FINANCE_FORBIDDEN, ex.getCode());
    }

    @Test
    void createShareOnFinanceDocumentAllowedWithAccess() {
        ExcelDocument doc = financeDocument();
        when(documentService.getById(1L)).thenReturn(doc);
        when(permissionService.userCanAccessDocument(doc, viewer)).thenReturn(true);

        ShareLinkService.ShareLink link = service.createShare(1L, "EDITOR");

        assertNotNull(link.token());
        assertEquals("EDITOR", link.role());
        verify(jdbcTemplate).update(anyString(), any(), eq(1L), eq("EDITOR"), any());
    }

    @Test
    void createShareOnNonFinanceDocumentSucceeds() {
        ExcelDocument doc = new ExcelDocument();
        doc.setId(2L);
        when(documentService.getById(2L)).thenReturn(doc);
        when(permissionService.isFinanceDocument(doc)).thenReturn(false);

        ShareLinkService.ShareLink link = service.createShare(2L, "VIEWER");
        assertEquals("VIEWER", link.role());
    }

    @Test
    void createShareRejectsIllegalRole() {
        ExcelDocument doc = new ExcelDocument();
        when(documentService.getById(1L)).thenReturn(doc);
        when(permissionService.isFinanceDocument(doc)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createShare(1L, "OWNER"));
        assertEquals(ErrorCodes.EXCEL_SHARE_INVALID, ex.getCode());
    }

    @Test
    void revokeShareThrowsWhenNoneDeleted() {
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.revokeShare(1L, "tok"));
        assertEquals(ErrorCodes.EXCEL_SHARE_INVALID, ex.getCode());
    }

    @Test
    void createShareRequiresManagePermission() {
        ExcelDocument doc = new ExcelDocument();
        when(documentService.getById(1L)).thenReturn(doc);
        when(permissionService.isFinanceDocument(doc)).thenReturn(false);
        when(permissionService.canManage(1L)).thenReturn(false);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.createShare(1L, "VIEWER"));
        assertEquals("没有管理该文档的权限", ex.getMessage());
    }

    @Test
    void revokeShareRequiresManagePermission() {
        when(permissionService.canManage(1L)).thenReturn(false);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.revokeShare(1L, "tok"));
        assertEquals("没有管理该文档的权限", ex.getMessage());
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }

    @Test
    void resolveShareRejectsEmptyRows() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.resolveShare("tok"));
        assertEquals(ErrorCodes.EXCEL_SHARE_INVALID, ex.getCode());
    }

    @Test
    void resolveShareOnFinanceDocumentDeniedWithoutAccess() {
        ExcelDocument doc = new ExcelDocument();
        when(documentService.optionalById(1L)).thenReturn(doc);
        when(permissionService.isFinanceDocument(doc)).thenReturn(true);
        when(permissionService.userCanAccessDocument(doc, viewer)).thenReturn(false);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("document_id", 1L, "role", "VIEWER")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.resolveShare("tok"));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
    }

    @Test
    void resolveShareOnNonFinanceDocumentSucceeds() {
        ExcelDocument doc = new ExcelDocument();
        when(documentService.optionalById(1L)).thenReturn(doc);
        when(permissionService.isFinanceDocument(doc)).thenReturn(false);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("document_id", Long.valueOf(9L), "role", "VIEWER")));

        ShareLinkService.ShareResolved resolved = service.resolveShare("tok");
        assertEquals(9L, resolved.documentId());
        assertEquals("VIEWER", resolved.role());
    }
}
