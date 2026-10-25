package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelSheetAccessService} 工作表归属校验行为锁。
 * <p>核心红线：持有工作簿 A 的权限，不等于可以读写挂在 A 路由下的工作表 B；
 * 所有 Sheet 级操作必须先证明 sheet.documentId == workbookId。</p>
 */
@DisplayName("工作表归属权限（ExcelSheetAccessService）")
class ExcelSheetAccessServiceTest {

    private final ExcelSheetMapper sheetMapper = mock(ExcelSheetMapper.class);
    private final ExcelPermissionService permissionService = mock(ExcelPermissionService.class);

    private ExcelSheetAccessService service;

    @BeforeEach
    void setUp() {
        service = new ExcelSheetAccessService(sheetMapper, permissionService);
    }

    private static CurrentUserService.CurrentUser user(Long id, SystemRole role) {
        return new CurrentUserService.CurrentUser(id, "U", "u", role, false);
    }

    private static ExcelSheet sheet(Long sheetId, Long documentId) {
        ExcelSheet sheet = new ExcelSheet();
        sheet.setId(sheetId);
        sheet.setDocumentId(documentId);
        sheet.setStatus(1);
        return sheet;
    }

    private void stubSheet(ExcelSheet sheet) {
        when(sheetMapper.selectById(any())).thenReturn(sheet);
    }

    @Test
    @DisplayName("工作表不属于该工作簿时拒绝访问")
    void sheetFromAnotherWorkbookIsRejected() {
        stubSheet(sheet(20L, 99L));
        when(permissionService.canView(11L, user(1L, SystemRole.USER))).thenReturn(true);

        assertThrows(ForbiddenException.class,
                () -> service.requireSheetView(11L, 20L, user(1L, SystemRole.USER)));
    }

    @Test
    @DisplayName("无工作簿查看权限时拒绝访问")
    void noWorkbookViewPermissionIsRejected() {
        stubSheet(sheet(20L, 11L));
        when(permissionService.canView(11L, user(1L, SystemRole.USER))).thenReturn(false);

        assertThrows(ForbiddenException.class,
                () -> service.requireSheetView(11L, 20L, user(1L, SystemRole.USER)));
    }

    @Test
    @DisplayName("归属一致且有查看权限时放行")
    void sheetInWorkbookWithViewPermissionPasses() {
        stubSheet(sheet(20L, 11L));
        when(permissionService.canView(11L, user(1L, SystemRole.USER))).thenReturn(true);

        assertDoesNotThrow(() -> service.requireSheetView(11L, 20L, user(1L, SystemRole.USER)));
    }

    @Test
    @DisplayName("Sheet 不存在或已删除时拒绝访问")
    void missingSheetIsRejected() {
        stubSheet(null);

        assertThrows(ForbiddenException.class,
                () -> service.requireSheetView(11L, 20L, user(1L, SystemRole.USER)));
    }

    @Test
    @DisplayName("编辑权限同时要求归属一致与 canEdit")
    void sheetEditRequiresOwnershipAndEditPermission() {
        stubSheet(sheet(20L, 11L));
        when(permissionService.canEdit(11L, user(1L, SystemRole.USER))).thenReturn(false);

        assertThrows(ForbiddenException.class,
                () -> service.requireSheetEdit(11L, 20L, user(1L, SystemRole.USER)));

        when(permissionService.canEdit(11L, user(1L, SystemRole.USER))).thenReturn(true);
        assertDoesNotThrow(() -> service.requireSheetEdit(11L, 20L, user(1L, SystemRole.USER)));
    }
}
