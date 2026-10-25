package com.argus.rag.excel.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocumentVersion;
import com.argus.rag.excel.service.ExcelDocumentLockService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.excel.service.ExcelVersionService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 文档版本：列表、详情、预览、手动保存、一键回滚。
 */
@Tag(name = "文档版本")
@RestController
@RequestMapping("/api/excel/document/{documentId}/versions")
public class ExcelVersionController {

    private final ExcelPermissionService permissionService;
    private final ExcelVersionService versionService;
    private final ExcelDocumentLockService lockService;

    public ExcelVersionController(ExcelPermissionService permissionService,
                                  ExcelVersionService versionService,
                                  ExcelDocumentLockService lockService) {
        this.permissionService = permissionService;
        this.versionService = versionService;
        this.lockService = lockService;
    }

    @GetMapping
    @Operation(summary = "分页查询文档版本")
    public ApiResponse<Page<ExcelDocumentVersion>> list(@PathVariable Long documentId,
                                                        @RequestParam(defaultValue = "1") int pageNum,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        permissionService.requireView(documentId);
        return ApiResponse.success(versionService.pageVersions(documentId, pageNum, pageSize));
    }

    @GetMapping("/{versionId}")
    @Operation(summary = "版本详情")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long documentId,
                                                   @PathVariable Long versionId) {
        permissionService.requireView(documentId);
        Map<String, Object> data = versionService.versionDetail(documentId, versionId);
        if (data == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "版本不存在");
        return ApiResponse.success(data);
    }

    @GetMapping("/{versionId}/preview")
    @Operation(summary = "预览版本")
    public ApiResponse<Map<String, Object>> preview(@PathVariable Long documentId,
                                                    @PathVariable Long versionId) {
        permissionService.requireView(documentId);
        Map<String, Object> data = versionService.previewVersion(documentId, versionId);
        if (data == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "版本不存在");
        return ApiResponse.success(data);
    }

    @PostMapping
    @Operation(summary = "手动创建版本")
    public ApiResponse<ExcelDocumentVersion> create(@PathVariable Long documentId,
                                                    @RequestBody(required = false) Map<String, String> body) {
        permissionService.requireEdit(documentId);
        String summary = body == null ? null : body.get("summary");
        return ApiResponse.success(versionService.manualSnapshot(documentId, summary));
    }

    @PostMapping("/{versionId}/restore")
    @Operation(summary = "回滚到指定版本")
    public ApiResponse<Map<String, Object>> restore(@PathVariable Long documentId,
                                                    @PathVariable Long versionId) {
        permissionService.requireEdit(documentId);
        Map<String, Object> result = lockService.withLock(
                documentId, () -> versionService.restoreVersion(documentId, versionId));
        return ApiResponse.success(result);
    }
}
