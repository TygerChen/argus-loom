package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.model.dto.BatchCountResult;
import com.argus.rag.excel.model.dto.BatchOperationResult;
import com.argus.rag.excel.model.dto.IdsRequest;
import com.argus.rag.excel.service.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收站接口：批量移入、分页列表、批量恢复、彻底删除、清空。
 *
 * <p><b>批量上限 MAX_BATCH_IDS=200</b>：批量接口必须有上限，否则一次传
 * 十万个 ID 既拖垮数据库 IN 查询也拖垮响应时间（05 篇 Q5：防护要同时考虑
 * "意外"和"故意的合理使用"）。每个批量端点都先鉴权（是 owner 或管理员
 * 才能动）、再逐个处理、最后返回"成功 + 失败清单"结构化结果。</p>
 */
@RestController
@RequestMapping("/api/excel/document")
@Tag(name = "文档回收站", description = "回收站列表、恢复、彻底删除与清空")
public class ExcelRecycleBinController {

    private static final int MAX_BATCH_IDS = 200;

    private final ExcelDocumentService documentService;
    private final RecycleBinService recycleBinService;
    private final CurrentUserService currentUserService;
    private final ExcelPermissionService permissionService;

    public ExcelRecycleBinController(ExcelDocumentService documentService,
                                     RecycleBinService recycleBinService,
                                     CurrentUserService currentUserService,
                                     ExcelPermissionService permissionService) {
        this.documentService = documentService;
        this.recycleBinService = recycleBinService;
        this.currentUserService = currentUserService;
        this.permissionService = permissionService;
    }

    @PostMapping("/batch-delete")
    @Operation(summary = "批量移入回收站")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody IdsRequest request) {
        List<Long> ids = parseIds(request);
        ensureRecycleScope(ids);
        ensureManageEach(ids);
        String operator = currentUserService.getRequiredCurrentUser().userCode();
        return ApiResponse.success(documentService.batchMoveToTrash(ids, operator));
    }

    @GetMapping("/recycle")
    @Operation(summary = "回收站分页列表")
    public ApiResponse<Map<String, Object>> recycle(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    @RequestParam(required = false) String keyword) {
        Page<ExcelDocument> page = recycleBinService.listRecycleByPage(
                pageNum, pageSize, keyword, currentUserService.getRequiredCurrentUser());
        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelDocument doc : page.getRecords()) {
            records.add(toView(doc));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("current", page.getCurrent());
        result.put("records", records);
        return ApiResponse.success(result);
    }

    @PostMapping("/batch-restore")
    @Operation(summary = "批量恢复")
    public ApiResponse<BatchOperationResult> batchRestore(@RequestBody IdsRequest request) {
        List<Long> ids = parseIds(request);
        ensureRecycleScope(ids);
        ensureManageEach(ids);
        return ApiResponse.success(recycleBinService.batchRestore(ids));
    }

    @PostMapping("/batch-purge")
    @Operation(summary = "批量彻底删除")
    public ApiResponse<BatchOperationResult> batchPurge(@RequestBody IdsRequest request) {
        List<Long> ids = parseIds(request);
        ensureRecycleScope(ids);
        ensureManageEach(ids);
        return ApiResponse.success(recycleBinService.batchPurge(ids));
    }

    @PostMapping("/recycle/clear")
    @Operation(summary = "清空回收站")
    public ApiResponse<BatchCountResult> clear() {
        ensureRecycleScope(null);
        return ApiResponse.success(recycleBinService.clearRecycle(
                currentUserService.getRequiredCurrentUser()));
    }

    /** 财务文件仅财务/领导角色可在回收站操作；普通用户与系统管理员不得触及。 */
    private void ensureRecycleScope(List<Long> ids) {
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        if (CurrentUserService.isFinanceUser(user)) {
            return;
        }
        if (ids == null || ids.isEmpty()) {
            return; // clear 按角色过滤由 RecycleBinService 处理
        }
        List<Long> financeIds = documentService.listIds(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExcelDocument>()
                        .eq("finance_required", true)
                        .in("id", ids)
                        .select("id"));
        if (financeIds != null && !financeIds.isEmpty()) {
            throw new ForbiddenException("财务文件仅限财务/领导角色操作");
        }
    }

    /**
     * 逐个校验文档管理权（创建者/管理员），防止按 ID 批量操作他人文档。
     * 已在回收站中的文档权限矩阵按正常口径判定；存在性错误由下层 Service 报出。
     */
    private void ensureManageEach(List<Long> ids) {
        for (Long id : ids) {
            ExcelDocument doc = documentService.getById(id);
            if (doc != null) {
                permissionService.requireManage(id);
            }
        }
    }

    private List<Long> parseIds(IdsRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new BusinessException("ids 不能为空");
        }
        List<Long> ids = request.ids().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) throw new BusinessException("ids 必须是文档 ID 数组");
        if (ids.size() > MAX_BATCH_IDS) {
            throw new BusinessException("单次最多批量处理 " + MAX_BATCH_IDS + " 个文档");
        }
        return ids;
    }

    private Map<String, Object> toView(ExcelDocument doc) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", doc.getId());
        item.put("name", doc.getName());
        item.put("creatorId", doc.getCreatorId());
        item.put("fileSize", doc.getFileSize());
        item.put("sheetCount", doc.getSheetCount());
        item.put("version", doc.getVersion());
        item.put("deletedBy", doc.getDeletedBy());
        item.put("deletedAt", doc.getDeletedAt());
        if (doc.getDeletedAt() != null) {
            item.put("expireAt", doc.getDeletedAt().plusDays(RecycleBinService.RETENTION_DAYS));
        }
        item.put("createTime", doc.getCreateTime());
        return item;
    }
}
