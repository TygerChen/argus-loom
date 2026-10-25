package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.entity.FinanceImportBatch;
import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.entity.FinancePeriod;
import com.argus.rag.excel.service.FinancePeriodService;
import com.argus.rag.excel.service.FinanceAnalysisService;
import com.argus.rag.excel.model.dto.FinanceRecordUpdateRequest;
import com.argus.rag.excel.service.FinanceImportService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务标准流水导入与查询接口。
 *
 * <p><b>控制器层的教学要点</b>（对照 13 篇的分层地图读）：</p>
 * <ul>
 *   <li>本类只做"接请求 → 鉴权 → 调 Service → 包 ApiResponse"四件事，
 *       所有业务规则在 FinanceImportService/FinancePeriodService——控制器薄，
 *       业务厚，是三层架构的分工原则；</li>
 *   <li>每个端点的方法注释都标了"谁能调"：财务接口统一 requireFinance
 *       （03 篇：财务数据只有财务角色可达，系统管理员也不行）；</li>
 *   <li>入参校验（月份格式、日期范围）在控制器层完成并给出明确错误信息——
 *       让用户在"进门"时就拿到可读的失败原因，而不是进了业务层才炸。</li>
 * </ul>
 */
@Tag(name = "财务导入")
@RestController
@RequestMapping("/api/finance")
public class FinanceImportController {

    private static final Logger log = LoggerFactory.getLogger(FinanceImportController.class);

    private final FinanceImportService financeImportService;
    private final FinancePeriodService financePeriodService;
    private final FinanceAnalysisService financeAnalysisService;
    private final CurrentUserService currentUserService;

    public FinanceImportController(FinanceImportService financeImportService,
                                   FinancePeriodService financePeriodService,
                                   FinanceAnalysisService financeAnalysisService,
                                   CurrentUserService currentUserService) {
        this.financeImportService = financeImportService;
        this.financePeriodService = financePeriodService;
        this.financeAnalysisService = financeAnalysisService;
        this.currentUserService = currentUserService;
    }

    /** 财务数据隔离守卫（服务层兜底）：仅财务/领导角色可操作财务，否则抛 403。 */
    private void requireFinanceAdmin() {
        currentUserService.requireFinance();
    }

    @PostMapping("/import")
    @Operation(summary = "财务工作簿首次导入",
            description = "上传财务工作簿，生成 Excel 分块展示数据并同步抽取标准财务流水")
    public ApiResponse<?> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String bookKey,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String currencyCode) {
        requireFinanceAdmin();
        try {
            FinanceImportBatch batch = financeImportService.importFile(
                    file, bookKey, regionName, currencyCode);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("batchId", batch.getId());
            data.put("documentId", batch.getDocumentId());
            data.put("bookKey", batch.getBookKey());
            data.put("regionName", batch.getRegionName());
            data.put("importedSheets", batch.getImportedSheets());
            data.put("totalSheets", batch.getTotalSheets());
            data.put("totalRows", batch.getTotalRows());
            data.put("mappedRows", batch.getMappedRows());
            data.put("skippedRows", batch.getSkippedRows());
            data.put("status", batch.getStatus());
            data.put("errorSummary", batch.getErrorSummary());
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("财务导入失败", e);
            return new ApiResponse<>(false, null, "财务导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/import-batches")
    @Operation(summary = "财务导入批次列表")
    public ApiResponse<?> batches(@RequestParam(defaultValue = "1") int pageNum,
                                  @RequestParam(defaultValue = "20") int pageSize) {
        requireFinanceAdmin();
        return ApiResponse.success(buildPage(financeImportService.pageBatches(pageNum, pageSize)));
    }

    @GetMapping("/import-batches/{batchId}/records")
    @Operation(summary = "批次财务流水明细")
    public ApiResponse<?> records(@PathVariable Long batchId,
                                  @RequestParam(defaultValue = "1") int pageNum,
                                  @RequestParam(defaultValue = "100") int pageSize) {
        requireFinanceAdmin();
        return ApiResponse.success(buildPage(financeImportService.pageRecords(batchId, pageNum, pageSize)));
    }

    @GetMapping("/overview")
    @Operation(summary = "财务总览指标")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeAll) {
        requireFinanceAdmin();
        return ApiResponse.success(financeImportService.financeOverview(
                regionName, batchId, currencyCode, from, to, includeAll));
    }

    @GetMapping("/batch-versions")
    @Operation(summary = "同一账本的导入版本历史")
    public ApiResponse<List<FinanceImportBatch>> batchVersions(@RequestParam String bookKey) {
        requireFinanceAdmin();
        return ApiResponse.success(financeImportService.listBatchVersions(bookKey));
    }

    @PostMapping("/batches/{batchId}/activate")
    @Operation(summary = "将历史批次设为当前版本")
    public ApiResponse<?> activateBatch(@PathVariable Long batchId) {
        requireFinanceAdmin();
        try {
            FinanceImportBatch batch = financeImportService.activateBatch(batchId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("batchId", batch.getId());
            data.put("bookKey", batch.getBookKey());
            data.put("batchVersion", batch.getBatchVersion());
            data.put("isCurrent", batch.getIsCurrent());
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.warn("财务批次切换失败: batchId={}, error={}", batchId, e.getMessage());
            return new ApiResponse<>(false, null, "切换失败: " + e.getMessage());
        }
    }

    @GetMapping("/summary/accounts")
    @Operation(summary = "账户维度汇总")
    public ApiResponse<List<Map<String, Object>>> accountSummary(
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(defaultValue = "false") boolean includeAll) {
        requireFinanceAdmin();
        return ApiResponse.success(financeImportService.accountSummaries(
                regionName, batchId, currencyCode, includeAll));
    }

    @GetMapping("/summary/daily")
    @Operation(summary = "每日发生额汇总")
    public ApiResponse<List<Map<String, Object>>> dailySummary(
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeAll) {
        requireFinanceAdmin();
        return ApiResponse.success(financeImportService.dailySummaries(
                regionName, batchId, currencyCode, from, to, includeAll));
    }

    @GetMapping("/records")
    @Operation(summary = "财务明细查询")
    public ApiResponse<Map<String, Object>> filteredRecords(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String sheetName,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeAll,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "100") int pageSize) {
        requireFinanceAdmin();
        Page<FinanceLedgerRecord> page = financeImportService.pageFilteredRecords(
                batchId, documentId, regionName, sheetName, currencyCode,
                recordType, keyword, from, to, includeAll, pageNum, pageSize);
        return ApiResponse.success(buildPage(page));
    }

    @PutMapping("/records/batch")
    @Operation(summary = "批量更新财务明细")
    public ApiResponse<?> updateRecords(
            @RequestBody List<FinanceRecordUpdateRequest> requests) {
        requireFinanceAdmin();
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("updatedCount", financeImportService.batchUpdateRecords(requests));
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.warn("财务明细批量更新失败: error={}", e.getMessage());
            return new ApiResponse<>(false, null, "更新失败: " + e.getMessage());
        }
    }

    @PostMapping("/periods/{periodMonth}/close")
    @Operation(summary = "关闭财务期间",
            description = "关闭后该月流水禁止导入与编辑；原因必填并计入审计")
    public ApiResponse<?> closePeriod(@PathVariable String periodMonth,
                                      @org.springframework.web.bind.annotation.RequestBody
                                      Map<String, String> body) {
        requireFinanceAdmin();
        try {
            FinancePeriod period = financePeriodService.close(
                    periodMonth, currentUserService.getRequiredCurrentUser().userId(),
                    body == null ? null : body.get("reason"));
            return ApiResponse.success(period);
        } catch (Exception e) {
            log.warn("关闭财务期间失败: period={}, error={}", periodMonth, e.getMessage());
            return new ApiResponse<>(false, null, "关闭失败: " + e.getMessage());
        }
    }

    @PostMapping("/periods/{periodMonth}/reopen")
    @Operation(summary = "重新打开财务期间",
            description = "仅财务角色可执行；原因必填并计入审计")
    public ApiResponse<?> reopenPeriod(@PathVariable String periodMonth,
                                       @org.springframework.web.bind.annotation.RequestBody
                                       Map<String, String> body) {
        requireFinanceAdmin();
        try {
            FinancePeriod period = financePeriodService.reopen(
                    periodMonth, currentUserService.getRequiredCurrentUser().userId(),
                    body == null ? null : body.get("reason"));
            return ApiResponse.success(period);
        } catch (Exception e) {
            log.warn("重新打开财务期间失败: period={}, error={}", periodMonth, e.getMessage());
            return new ApiResponse<>(false, null, "重开失败: " + e.getMessage());
        }
    }

    @GetMapping("/periods/{periodMonth}")
    @Operation(summary = "查询期间开关状态")
    public ApiResponse<?> periodStatus(@PathVariable String periodMonth) {
        requireFinanceAdmin();
        return ApiResponse.success(financePeriodService.findByMonth(periodMonth));
    }

    @GetMapping("/analysis/period-summary")
    @Operation(summary = "期间汇总（收入/支出/净额 + 环比，可下钻批次）")
    public ApiResponse<?> periodSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireFinanceAdmin();
        return ApiResponse.success(financeAnalysisService.periodSummary(from, to));
    }

    @GetMapping("/analysis/dimension")
    @Operation(summary = "维度汇总 Top N（counterparty/department/category，可下钻）")
    public ApiResponse<?> dimensionSummary(
            @RequestParam(defaultValue = "counterparty") String dimension,
            @RequestParam(defaultValue = "10") int topN) {
        requireFinanceAdmin();
        return ApiResponse.success(financeAnalysisService.dimensionSummary(dimension, topN));
    }

    @GetMapping("/analysis/anomalies")
    @Operation(summary = "异常提醒（规则码 + 阈值 + 命中原因 + 原始记录定位）")
    public ApiResponse<?> anomalies() {
        requireFinanceAdmin();
        return ApiResponse.success(financeAnalysisService.detectAnomalies());
    }

    @GetMapping("/analysis/ai-context")
    @Operation(summary = "AI 问答聚合上下文（不含原始明细行）")
    public ApiResponse<?> aiContext(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String question) {
        requireFinanceAdmin();
        return ApiResponse.success(financeAnalysisService.aiQuestionContext(from, to, question));
    }

    private Map<String, Object> buildPage(Page<?> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getCurrent());
        result.put("pageSize", page.getSize());
        result.put("records", page.getRecords());
        return result;
    }
}
