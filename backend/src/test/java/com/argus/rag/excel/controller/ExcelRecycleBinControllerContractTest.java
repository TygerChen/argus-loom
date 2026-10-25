package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.excel.model.dto.BatchOperationResult;
import com.argus.rag.excel.model.dto.BatchRejectEntry;
import com.argus.rag.excel.service.ExcelDocumentService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.excel.service.RecycleBinService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回收站批量接口的端到端 JSON 契约（HTTP 层）：
 * {@code POST /api/excel/document/batch-restore} 返回的 {@code data} 必须恰好是
 * DTO 序列化的字段（successIds/rejected[*].reason 等），改 Map→DTO 的 key 即红。
 */
@WebMvcTest(
        controllers = ExcelRecycleBinController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        }
)
@Import({ExcelRecycleBinController.class, GlobalExceptionHandler.class})
@DisplayName("回收站 Controller JSON 契约测试")
class ExcelRecycleBinControllerContractTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExcelDocumentService documentService;

    @MockitoBean
    private RecycleBinService recycleBinService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private ExcelPermissionService permissionService;

    @BeforeEach
    void setUp() {
        // 财务用户 → ensureRecycleScope 提前放行，不触发 documentService 财务查询。
        when(currentUserService.getRequiredCurrentUser())
                .thenReturn(new CurrentUserService.CurrentUser(
                        1L, "U100001", "财务", SystemRole.FINANCE, false));
    }

    @Test
    @DisplayName("batch-restore 返回 BatchOperationResult 的 JSON key")
    void batchRestoreSerializesDtoShape() throws Exception {
        when(recycleBinService.batchRestore(List.of(1L, 2L)))
                .thenReturn(new BatchOperationResult(2, 1, 1,
                        List.of(1L), List.of(new BatchRejectEntry(2L, "彻底删除失败"))));

        mockMvc.perform(post("/api/excel/document/batch-restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.successIds[0]").value(1))
                .andExpect(jsonPath("$.data.rejected[0].id").value(2))
                .andExpect(jsonPath("$.data.rejected[0].reason").value("彻底删除失败"));
    }
}
