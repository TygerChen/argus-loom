package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.excel.audit.OperationLogService;
import com.argus.rag.excel.controller.ExcelDocumentController;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.service.ExcelDocumentService;
import com.argus.rag.excel.service.ExcelParserService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.excel.service.ExcelSheetAccessService;
import com.argus.rag.excel.service.ExcelSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sheet 级数据接口的越权防护回归：
 * 持有工作簿权限但 Sheet 归属不符时必须 403，不得返回单元格数据。
 */
@WebMvcTest(
        controllers = ExcelDocumentController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        }
)
@Import({ExcelDocumentController.class, GlobalExceptionHandler.class})
@DisplayName("Sheet 级接口越权防护（跨工作簿 IDOR）")
class SheetAccessEnforcementTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExcelDocumentService documentService;

    @MockitoBean
    private ExcelSheetService sheetService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private ExcelPermissionService permissionService;

    @MockitoBean
    private ExcelSheetAccessService sheetAccessService;

    @MockitoBean
    private ExcelParserService parserService;

    @MockitoBean
    private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        CurrentUserService.CurrentUser viewer =
                new CurrentUserService.CurrentUser(7L, "u007", "员工七", SystemRole.USER, false);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(viewer);
    }

    @Test
    @DisplayName("Sheet 归属不符时全量单元格接口返回 403")
    void loadAllCelldataRejectsForeignSheet() throws Exception {
        doThrow(new ForbiddenException("没有访问该工作表的权限"))
                .when(sheetAccessService)
                .requireSheetView(eq(11L), eq(20L), any());

        mockMvc.perform(get("/api/excel/document/11/sheet/20/all"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sheet 归属一致且有权时正常返回数据")
    void loadAllCelldataAllowsOwnedSheet() throws Exception {
        when(sheetService.loadMergedCelldata(20L)).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/excel/document/11/sheet/20/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("无工作簿权限时按块加载接口返回 403")
    void loadSheetChunksRejectsUnauthorizedWorkbook() throws Exception {
        doThrow(new ForbiddenException("没有访问该工作表的权限"))
                .when(sheetAccessService)
                .requireSheetView(eq(11L), eq(20L), any());

        mockMvc.perform(get("/api/excel/document/11/sheet/20/chunks"))
                .andExpect(status().isForbidden());
    }
}
