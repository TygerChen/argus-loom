package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.entity.ExcelAuditLog;
import com.argus.rag.excel.entity.ExcelCollabLog;
import com.argus.rag.excel.entity.ExcelCollabRoom;
import com.argus.rag.excel.service.ExcelSystemConfigService;
import com.argus.rag.excel.service.OperationAdminService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营中心：审计、协同日志、在线房间、版本策略。
 *
 * <p><b>这是管理员看"系统里发生了什么"的窗口</b>：操作审计日志（谁改了什么）、
 * 协同日志（谁和谁在哪个房间编辑）、在线房间列表、版本保留策略配置。
 * 全部端点仅管理员可达（控制器内统一校验），且只读为主——
 * 审计数据只增不改，管理员也没有"修改历史"的权限。</p>
 */
@Tag(name = "运营中心")
@RestController
@RequestMapping("/api/admin/ops")
public class OperationAdminController {

    private final CurrentUserService currentUserService;
    private final OperationAdminService operationService;
    private final ExcelSystemConfigService configService;

    public OperationAdminController(CurrentUserService currentUserService,
                                    OperationAdminService operationService,
                                    ExcelSystemConfigService configService) {
        this.currentUserService = currentUserService;
        this.operationService = operationService;
        this.configService = configService;
    }

    private void requireAdmin() {
        currentUserService.requireSystemAdmin();
    }

    @GetMapping("/summary")
    @Operation(summary = "运营概览")
    public ApiResponse<Map<String, Object>> summary() {
        requireAdmin();
        return ApiResponse.success(operationService.summary());
    }

    @GetMapping("/audit")
    @Operation(summary = "审计日志分页")
    public ApiResponse<Map<String, Object>> audit(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime end) {
        requireAdmin();
        Page<ExcelAuditLog> page = operationService.pageAudit(
                pageNum, pageSize, username, action, targetType, start, end);
        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelAuditLog log : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("userId", log.getUserId());
            item.put("username", log.getUsername());
            item.put("action", log.getAction());
            item.put("targetType", log.getTargetType());
            item.put("targetId", log.getTargetId());
            item.put("detail", log.getDetail());
            item.put("ip", log.getIp());
            item.put("createTime", log.getCreateTime());
            records.add(item);
        }
        return ApiResponse.success(pageResult(page, records));
    }

    @GetMapping("/collab")
    @Operation(summary = "协同操作日志分页")
    public ApiResponse<Map<String, Object>> collab(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Boolean merged,
            @RequestParam(required = false) Boolean conflict) {
        requireAdmin();
        Page<ExcelCollabLog> page = operationService.pageCollabLog(
                pageNum, pageSize, documentId, username, merged, conflict);
        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelCollabLog log : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("documentId", log.getDocumentId());
            item.put("sheetId", log.getSheetId());
            item.put("rowNo", log.getRowNo());
            item.put("colNo", log.getColNo());
            item.put("actorId", log.getActorId());
            item.put("actorName", log.getActorName());
            item.put("merged", log.getMerged());
            item.put("conflict", log.getConflict());
            item.put("createTime", log.getCreateTime());
            records.add(item);
        }
        return ApiResponse.success(pageResult(page, records));
    }

    @GetMapping("/rooms")
    @Operation(summary = "在线房间分页")
    public ApiResponse<Map<String, Object>> rooms(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String username) {
        requireAdmin();
        Page<ExcelCollabRoom> page = operationService.pageRooms(pageNum, pageSize, documentId, username);
        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelCollabRoom room : page.getRecords()) records.add(operationService.roomView(room));
        return ApiResponse.success(pageResult(page, records));
    }

    @PostMapping("/rooms/{roomId}/kick")
    @Operation(summary = "强制下线在线协作会话")
    public ApiResponse<String> kick(@PathVariable Long roomId) {
        requireAdmin();
        operationService.kickRoom(roomId);
        return ApiResponse.success("已强制下线");
    }

    @GetMapping("/version-policy")
    @Operation(summary = "版本留存策略")
    public ApiResponse<Map<String, Object>> versionPolicy() {
        requireAdmin();
        return ApiResponse.success(configService.policy());
    }

    @PutMapping("/version-policy")
    @Operation(summary = "更新版本留存策略")
    public ApiResponse<Map<String, Object>> updateVersionPolicy(@RequestBody Map<String, Object> body) {
        requireAdmin();
        int retention = intValue(body.get("retentionCount"), configService.getRetentionCount());
        int interval = intValue(body.get("autoCellIntervalMinutes"), configService.getAutoCellIntervalMinutes());
        return ApiResponse.success(configService.updatePolicy(retention, interval));
    }

    @GetMapping("/version-stats")
    @Operation(summary = "版本容量统计")
    public ApiResponse<Map<String, Object>> versionStats() {
        requireAdmin();
        return ApiResponse.success(operationService.versionStats());
    }

    private int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Map<String, Object> pageResult(Page<?> page, List<Map<String, Object>> records) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("current", page.getCurrent());
        result.put("records", records);
        return result;
    }
}
