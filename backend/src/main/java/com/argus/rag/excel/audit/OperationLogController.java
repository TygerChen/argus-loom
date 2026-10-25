package com.argus.rag.excel.audit;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.service.ExcelPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 操作日志 — 查询 + 撤销。
 */
@RestController
@RequestMapping("/api/excel/{documentId}/audit")
@Tag(name = "操作审计日志", description = "单元格变更记录 + 撤销操作")
public class OperationLogController {

    private final OperationLogService logService;
    private final CurrentUserService currentUserService;
    private final ExcelPermissionService permissionService;

    public OperationLogController(OperationLogService logService,
                                  CurrentUserService currentUserService,
                                  ExcelPermissionService permissionService) {
        this.logService = logService;
        this.currentUserService = currentUserService;
        this.permissionService = permissionService;
    }

    @Operation(summary = "查询操作日志", description = "获取最近 500 条变更记录，含操作类型/新旧值/操作人/时间")
    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> getLogs(@PathVariable Long documentId) {
        permissionService.requireView(documentId);
        List<OperationLog> logs = logService.getRecentLogs(documentId);
        List<Map<String, Object>> result = logs.stream().map(log -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("sheetId", log.getSheetId());
            item.put("rowIndex", log.getRowIndex());
            item.put("colIndex", log.getColIndex());
            item.put("operationType", log.getOperationType());
            item.put("newValue", log.getNewValue());
            item.put("userId", log.getUserId());
            item.put("userDisplayName", log.getUserDisplayName());
            item.put("createTime", log.getCreateTime());
            return item;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /** 撤销最近一次编辑（当前用户自己的操作） */
    @Operation(summary = "撤销最近一次编辑", description = "撤销当前用户在该文档的最近一次 CELL_EDIT，返回被撤销单元格的旧值")
    @PostMapping("/undo")
    public ApiResponse<Map<String, Object>> undoLast(
            @PathVariable Long documentId) {
        permissionService.requireEdit(documentId);
        Long userId = currentUserService.getRequiredCurrentUser().userId();
        OperationLog undone = logService.undoLast(documentId, userId);
        if (undone == null) throw new BusinessException("无可撤销的操作");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", undone.getId());
        result.put("rowIndex", undone.getRowIndex());
        result.put("colIndex", undone.getColIndex());
        result.put("sheetId", undone.getSheetId());
        result.put("restoredValue", undone.getOldValue());
        return ApiResponse.success(result);
    }

    /** 重做最近一次撤销 */
    @Operation(summary = "重做最近一次撤销", description = "重做当前用户最近一次撤销的编辑内容")
    @PostMapping("/redo")
    public ApiResponse<Map<String, Object>> redoLast(@PathVariable Long documentId) {
        permissionService.requireEdit(documentId);
        Long userId = currentUserService.getRequiredCurrentUser().userId();
        OperationLog redone = logService.redoLast(documentId, userId);
        if (redone == null) throw new BusinessException("无可重做的操作");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", redone.getId());
        result.put("rowIndex", redone.getRowIndex());
        result.put("colIndex", redone.getColIndex());
        result.put("sheetId", redone.getSheetId());
        result.put("restoredValue", redone.getNewValue());
        return ApiResponse.success(result);
    }
}
