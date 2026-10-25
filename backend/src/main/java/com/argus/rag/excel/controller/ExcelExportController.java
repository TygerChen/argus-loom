package com.argus.rag.excel.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.entity.ExcelExportTask;
import com.argus.rag.excel.service.ExcelExportService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步导出：创建任务、轮询状态、取消、下载。
 */
@Tag(name = "异步导出任务")
@RestController
@RequestMapping("/api/excel/export")
public class ExcelExportController {

    private final ExcelExportService exportService;

    public ExcelExportController(ExcelExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping("/document/{documentId}")
    @Operation(summary = "创建导出任务")
    public ApiResponse<Map<String, Object>> create(@PathVariable Long documentId) {
        return ApiResponse.success(taskView(exportService.createTask(documentId)));
    }

    @GetMapping("/tasks")
    @Operation(summary = "导出任务分页列表")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                 @RequestParam(defaultValue = "20") int pageSize,
                                                 @RequestParam(required = false) String keyword) {
        Page<ExcelExportTask> page = exportService.listTasks(pageNum, pageSize, keyword);
        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelExportTask task : page.getRecords()) records.add(taskView(task));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("current", page.getCurrent());
        result.put("records", records);
        return ApiResponse.success(result);
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "导出任务详情")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long taskId) {
        return ApiResponse.success(taskView(exportService.getTaskForCurrentUser(taskId)));
    }

    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "取消导出任务")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long taskId) {
        return ApiResponse.success(taskView(exportService.cancelTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @Operation(summary = "重试失败的导出任务（以同一文档新建任务）")
    public ApiResponse<Map<String, Object>> retry(@PathVariable Long taskId) {
        return ApiResponse.success(taskView(exportService.retryTask(taskId)));
    }

    @GetMapping("/tasks/{taskId}/download")
    @Operation(summary = "下载导出产物")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long taskId) {
        ExcelExportTask task = exportService.getTaskForCurrentUser(taskId);
        InputStream stream = exportService.openResult(task);
        String fileName = task.getFileName() == null ? "export.xlsx" : task.getFileName();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new InputStreamResource(stream));
    }

    private Map<String, Object> taskView(ExcelExportTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("documentId", task.getDocumentId());
        view.put("taskNo", task.getTaskNo());
        view.put("username", task.getUsername());
        view.put("status", task.getStatus());
        view.put("progress", task.getProgress());
        view.put("fileName", task.getFileName());
        view.put("downloadUrl", task.getDownloadUrl());
        view.put("errorMessage", task.getErrorMessage());
        view.put("createTime", task.getCreateTime());
        view.put("updateTime", task.getUpdateTime());
        view.put("finishTime", task.getFinishTime());
        return view;
    }
}
