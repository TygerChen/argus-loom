package com.argus.rag.excel.controller;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.audit.OperationLogService;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.model.dto.RenameDocumentRequest;
import com.argus.rag.excel.model.dto.SaveWorkbookRequest;
import com.argus.rag.excel.model.vo.*;
import com.argus.rag.excel.service.ExcelDocumentService;
import com.argus.rag.excel.service.ExcelParserService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.excel.service.ExcelSheetAccessService;
import com.argus.rag.excel.service.ExcelSheetService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Excel 文档接口 — 文档 CRUD + 工作簿保存 + 单元格批量更新。
 * 遵循 Argus 标准三层架构：Controller → DTO/VO 类型安全，Service 封装业务逻辑。
 *
 * <p><b>读这个类的最佳姿势</b>：挑 saveWorkbook 和 batchUpdateCells 两个
 * 最重的端点，跟着请求走一遍"权限判定（03 篇）→ 乐观锁版本检查（05 篇）
 * → Service 落数据 → Outbox 待广播事件（05 篇）→ 包 VO 返回"的完整链路，
 * 就能把三篇教程的知识全部串起来。其余端点都是这个模式的不同变体。</p>
 */
@RestController
@RequestMapping("/api/excel/document")
@Tag(name = "Excel 文档管理", description = "文档 CRUD、工作簿保存、单元格批量更新")
public class ExcelDocumentController {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentController.class);

    private final ExcelDocumentService documentService;
    private final ExcelSheetService sheetService;
    private final CurrentUserService currentUserService;
    private final ExcelPermissionService permissionService;
    private final ExcelSheetAccessService sheetAccessService;
    private final ExcelParserService parserService;
    private final OperationLogService operationLogService;

    public ExcelDocumentController(ExcelDocumentService documentService,
                                    ExcelSheetService sheetService,
                                    CurrentUserService currentUserService,
                                    ExcelPermissionService permissionService,
                                    ExcelSheetAccessService sheetAccessService,
                                    ExcelParserService parserService,
                                    OperationLogService operationLogService) {
        this.documentService = documentService;
        this.sheetService = sheetService;
        this.currentUserService = currentUserService;
        this.permissionService = permissionService;
        this.sheetAccessService = sheetAccessService;
        this.parserService = parserService;
        this.operationLogService = operationLogService;
    }

    @Operation(summary = "文档列表", description = "分页查询文档列表，仅返回元数据，不含单元格数据")
    @GetMapping("/list")
    public ApiResponse<ExcelDocumentPageVO> list(@RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        boolean admin = permissionService.isAdmin();
        boolean financeUser = permissionService.isFinance();
        List<Long> sharedIds = admin ? java.util.Collections.emptyList() : permissionService.accessibleDocumentIds();
        Page<ExcelDocument> page = documentService.listAccessibleByPage(
                pageNum, pageSize, user.userId(), admin, sharedIds, financeUser);

        List<ExcelDocumentListItemVO> records = page.getRecords().stream()
                .map(d -> new ExcelDocumentListItemVO(
                        d.getId(), d.getName(), d.getSheetCount(), d.getSheetNames(),
                        d.getVersion(), d.getFileSize(), d.getCreateTime(), d.getUpdateTime(),
                        permissionService.permissionOf(d, user),
                        permissionService.canEdit(d, user),
                        permissionService.canManage(d, user)))
                .collect(Collectors.toList());

        return ApiResponse.success(new ExcelDocumentPageVO(
                page.getTotal(), page.getPages(), page.getCurrent(), records));
    }

    @Operation(summary = "文档详情", description = "获取文档及各 Sheet 元信息（含合并单元格、列宽、图表等配置）")
    @GetMapping("/{id}")
    public ApiResponse<DocumentDetailVO> detail(@PathVariable Long id) {
        permissionService.requireView(id);
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        ExcelDocument doc = documentService.getById(id);
        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(id);

        List<SheetInfoVO> sheetInfoList = sheets.stream().map(s -> {
            Map<String, Object> mergeConfig = parseObjectOrEmpty(s.getMergeConfigJson());
            Map<String, Object> columnLen = parseObjectOrEmpty(s.getColumnLenJson());
            Map<String, Object> rowLen = parseObjectOrEmpty(s.getRowLenJson());
            Map<String, Object> config = parseObjectOrEmpty(s.getConfigJson());
            if (config.isEmpty()) {
                config.put("merge", mergeConfig);
                config.put("columnlen", columnLen);
                config.put("rowlen", rowLen);
            }
            return new SheetInfoVO(
                    s.getId(), s.getSheetIndex(), s.getSheetName(),
                    s.getTotalRows(), s.getTotalCols(), s.getChunkCount(), s.getActive(),
                    config, mergeConfig, columnLen, rowLen,
                    parseObjectOrEmpty(s.getHyperlinkConfigJson()),
                    parseObjectOrEmpty(s.getImagesConfigJson()),
                    parseArrayOrEmpty(s.getConditionFormatJson()),
                    parseArrayOrEmpty(s.getChartJson()));
        }).collect(Collectors.toList());

        return ApiResponse.success(new DocumentDetailVO(
                doc.getId(), doc.getName(), doc.getSheetCount(), doc.getVersion(), sheetInfoList,
                permissionService.permissionOf(doc, user),
                permissionService.canEdit(doc, user),
                permissionService.canManage(doc, user)));
    }

    @Operation(summary = "加载全量单元格数据", description = "获取指定 Sheet 的全部 celldata（合并所有分块）")
    @GetMapping("/{id}/sheet/{sheetId}/all")
    public ApiResponse<CelldataVO> loadAllCelldata(@PathVariable Long id,
                                                     @PathVariable Long sheetId) {
        // 必须同时校验：工作簿可见 + 该 Sheet 确实挂在此工作簿下（防止跨工作簿 IDOR）。
        sheetAccessService.requireSheetView(id, sheetId,
                currentUserService.getRequiredCurrentUser());
        List<Object> mergedCelldata = sheetService.loadMergedCelldata(sheetId);

        return ApiResponse.success(new CelldataVO(sheetId, mergedCelldata, mergedCelldata.size()));
    }

    @Operation(summary = "按块索引范围加载单元格数据", description = "start/end 均为块序号（含端点），增量变更已合并")
    @GetMapping("/{id}/sheet/{sheetId}/chunks")
    public ApiResponse<Map<String, Object>> loadSheetChunks(@PathVariable Long id,
                                                             @PathVariable Long sheetId,
                                                             @RequestParam(defaultValue = "0") int start,
                                                             @RequestParam(defaultValue = "2147483647") int end) {
        sheetAccessService.requireSheetView(id, sheetId,
                currentUserService.getRequiredCurrentUser());
        List<ExcelSheetChunk> chunks = sheetService.loadChunksByIndexRange(sheetId, start, end);
        List<Object> result = new ArrayList<>();
        for (ExcelSheetChunk chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkIndex", chunk.getChunkIndex());
            item.put("rowStart", chunk.getRowStart());
            item.put("rowEnd", chunk.getRowEnd());
            item.put("celldata", chunk.getCelldataJson() == null || chunk.getCelldataJson().isEmpty()
                    ? new ArrayList<>() : JsonCompat.parseArray(chunk.getCelldataJson()));
            result.add(item);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("chunks", result);
        payload.put("total", result.size());
        return ApiResponse.success(payload);
    }

    @Operation(summary = "修复数字格式", description = "读取磁盘上的原始 Excel，为旧版本导入时丢失数字格式的数值单元格回填 ct.fa / m")
    @PostMapping("/{id}/repair-number-format")
    public ApiResponse<?> repairNumberFormat(@PathVariable Long id) {
        permissionService.requireEdit(id);
        try {
            ExcelDocument doc = documentService.getById(id);
            int repaired = parserService.repairNumberFormats(doc);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("repairedCells", repaired);
            log.info("数字格式修复成功: doc={}, repaired={}", id, repaired);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("数字格式修复失败: doc={}, error={}", id, e.getMessage(), e);
            return new ApiResponse<>(false, null, "数字格式修复失败: " + e.getMessage());
        }
    }

    @Operation(summary = "批量增量更新单元格", description = "按 Chunk 分组后逐块回写，仅影响相关分块，适合纯数据修改场景")
    @PutMapping("/{id}/cells/batch")
    public ApiResponse<String> batchUpdateCells(@PathVariable Long id,
                                                 @RequestBody List<Map<String, Object>> updates) {
        permissionService.requireEdit(id);
        if (updates == null || updates.isEmpty()) {
            return ApiResponse.success("没有需要保存的修改");
        }
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        operationLogService.recordBatchEditAndApply(id, updates, user.userId(), user.displayName());
        log.info("批量更新成功: doc={}, count={}", id, updates.size());
        return ApiResponse.success("保存成功");
    }

    @Operation(summary = "全量快照保存", description = "替换文档下所有 Sheet 和 Chunk，适用于结构变化（增删 Sheet、合并单元格等）")
    @PutMapping("/{id}/workbook")
    public ApiResponse<WorkbookSaveVO> saveWorkbook(@PathVariable Long id,
                                                     @Valid @RequestBody SaveWorkbookRequest body) {
        permissionService.requireEdit(id);
        Map<Integer, Long> sheetIdMapping = sheetService.replaceWorkbook(id, body.sheets());
        log.info("工作簿全量保存成功: doc={}, sheetCount={}", id, body.sheets().size());
        return ApiResponse.success(new WorkbookSaveVO(body.sheets().size(), sheetIdMapping));
    }

    @PutMapping("/{id}/name")
    public ApiResponse<String> rename(@PathVariable Long id,
                                       @Valid @RequestBody RenameDocumentRequest body) {
        permissionService.requireEdit(id);
        documentService.rename(id, body.name().trim());
        log.info("文档重命名成功: doc={}, name={}", id, body.name());
        return ApiResponse.success("重命名成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        permissionService.requireManage(id);
        String operator = currentUserService.getRequiredCurrentUser().userCode();
        documentService.moveToTrash(id, operator);
        log.info("文档已移入回收站: doc={}", id);
        return ApiResponse.success("已移入回收站");
    }

    private Map<String, Object> parseObjectOrEmpty(String json) {
        return JsonCompat.parseObject(json);
    }

    private List<Object> parseArrayOrEmpty(String json) {
        return JsonCompat.parseArray(json);
    }
}
