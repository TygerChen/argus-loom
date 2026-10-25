package com.argus.rag.excel.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.model.vo.UploadResultVO;
import com.argus.rag.excel.service.ExcelUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Excel 文件上传入口。
 * <p>
 * 自阶段 C 起仅作为薄壳：收参后统一委托 {@link ExcelUploadService#upload(MultipartFile)}，
 * 校验 / 存盘 / 大小路由 / 落库 / 失败清理全部下沉到 service（P1-01 真流式、P1-02 校验）。
 * 既有 {@code POST /api/excel/upload} 与响应体 {@link UploadResultVO} 形态保持不变。
 */
@RestController
@RequestMapping("/api/excel")
@Tag(name = "Excel 文件上传", description = "上传 Excel 文件，自动解析并分块入库")
public class ExcelFileController {

    private final ExcelUploadService excelUploadService;

    public ExcelFileController(ExcelUploadService excelUploadService) {
        this.excelUploadService = excelUploadService;
    }

    @Operation(summary = "上传 Excel 文件", description = "支持 .xlsx/.xls，POI 流式解析后分块写入数据库，大于 50MB 自动切换 SAX 流式解析防 OOM")
    @PostMapping("/upload")
    public ApiResponse<UploadResultVO> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(excelUploadService.upload(file));
    }
}