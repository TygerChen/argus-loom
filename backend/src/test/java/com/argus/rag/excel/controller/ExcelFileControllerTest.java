package com.argus.rag.excel.controller;

import com.argus.rag.excel.model.vo.SheetMetaVO;
import com.argus.rag.excel.model.vo.UploadResultVO;
import com.argus.rag.excel.service.ExcelUploadService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 C 起控制层只做「收参→调 UploadService」的薄壳，这里守卫既有 REST 契约形态：
 * {@code POST /api/excel/upload} 返回 {success, data.documentId, data.sheetCount, data.sheets}，
 * 不再关注内部编排细节（编排行为移至 {@link ExcelUploadServiceTest} 守护）。
 */
@WebMvcTest(
        controllers = ExcelFileController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        }
)
@Import(ExcelFileController.class)
@DisplayName("Excel 文件上传控制器测试")
class ExcelFileControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExcelUploadService excelUploadService;

    @BeforeEach
    void setUp() {
        UploadResultVO result = new UploadResultVO(
                88L, "finance.xlsx", 1,
                List.of(new SheetMetaVO(655L, 0, "Sheet1", 3, 3, 1, 1)));
        when(excelUploadService.upload(any(MockMultipartFile.class))).thenReturn(result);
    }

    @Test
    @DisplayName("上传接口返回成功与 documentId/sheetCount")
    void uploadReturnsDocumentIdAndSheetCount() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "finance.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{(byte) 0x50, 0x4b, 0x03, 0x04}
        );

        mockMvc.perform(multipart("/api/excel/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.documentId").value(88))
                .andExpect(jsonPath("$.data.sheetCount").value(1))
                .andExpect(jsonPath("$.data.sheets[0].sheetId").value(655))
                .andExpect(jsonPath("$.data.sheets[0].sheetName").value("Sheet1"));
    }
}