package com.argus.rag.finance.report;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.finance.report.entity.ReportExecution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金日报接口（个人报表流水线第一个实例）。
 * 仅财务/领导角色可达；数据按 owner_user_id 隔离，只读写本人数据。
 */
@Tag(name = "资金日报")
@RestController
@RequestMapping("/api/finance/fund-daily")
public class FundReportController {

    private static final Logger log = LoggerFactory.getLogger(FundReportController.class);

    private final FundDailyReportService reportService;
    private final CurrentUserService currentUserService;

    public FundReportController(FundDailyReportService reportService,
                                CurrentUserService currentUserService) {
        this.reportService = reportService;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "上传资金报表 Excel（每文件=一公司，每 Sheet=一账户）")
    @PostMapping("/upload")
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file) {
        currentUserService.requireFinance();
        try {
            return ApiResponse.success(reportService.upload(file, ownerId()));
        } catch (Exception e) {
            log.warn("资金报表上传失败", e);
            return new ApiResponse<>(false, null, "上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "删除已上传的报表文件")
    @PostMapping("/delete-file")
    public ApiResponse<?> deleteFile(@RequestParam String fileName) {
        currentUserService.requireFinance();
        boolean deleted = reportService.deleteFile(fileName, ownerId());
        return ApiResponse.success(Map.of("deleted", deleted));
    }

    @Operation(summary = "已上传报表文件列表")
    @GetMapping("/files")
    public ApiResponse<?> files() {
        currentUserService.requireFinance();
        return ApiResponse.success(reportService.listFiles(ownerId()));
    }

    @Operation(summary = "生成/重跑指定基准日的资金日报并推送")
    @PostMapping("/generate")
    public ApiResponse<?> generate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        currentUserService.requireFinance();
        ReportExecution execution = reportService.generate(date, ownerId());
        return ApiResponse.success(toView(execution));
    }

    @Operation(summary = "单日日报（result_json 为看板数据源）")
    @GetMapping
    public ApiResponse<?> byDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        currentUserService.requireFinance();
        ReportExecution execution = reportService.getByDate(ownerId(), date);
        return ApiResponse.success(execution == null ? null : toView(execution));
    }

    @Operation(summary = "历史日报列表（不含大字段）")
    @GetMapping("/list")
    public ApiResponse<?> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        currentUserService.requireFinance();
        List<ReportExecution> executions = reportService.listExecutions(ownerId(), from, to);
        return ApiResponse.success(executions.stream().map(e -> {
            ReportExecution slim = new ReportExecution();
            slim.setId(e.getId());
            slim.setBizDate(e.getBizDate());
            slim.setStatus(e.getStatus());
            slim.setPushStatus(e.getPushStatus());
            slim.setPushDetail(e.getPushDetail());
            slim.setDurationMs(e.getDurationMs());
            slim.setErrorMessage(e.getErrorMessage());
            return slim;
        }).toList());
    }

    private Map<String, Object> toView(ReportExecution execution) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", execution.getId());
        data.put("bizDate", execution.getBizDate());
        data.put("status", execution.getStatus());
        data.put("pushStatus", execution.getPushStatus());
        data.put("pushDetail", execution.getPushDetail());
        data.put("errorMessage", execution.getErrorMessage());
        data.put("durationMs", execution.getDurationMs());
        data.put("result", execution.getResultJson());
        return data;
    }

    private Long ownerId() {
        return currentUserService.getRequiredCurrentUser().userId();
    }
}
