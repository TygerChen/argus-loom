package com.argus.rag.finance.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.finance.entity.EmployeeAttendance;
import com.argus.rag.finance.entity.EmployeePayroll;
import com.argus.rag.finance.entity.FinanceReport;
import com.argus.rag.finance.service.FinanceReportPdfService;
import com.argus.rag.finance.service.PayrollService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 财务工作台：考勤薪酬台账维护、员工自助、AI 财务简报与 PDF。
 * <p>写操作与财务数据读取由 SecurityConfig + requireFinance 双重保护；
 * 员工自助接口只允许查看本人数据。</p>
 *
 * <p><b>注意 URL 设计</b>：本类和 FinanceImportController 共用 /api/finance 前缀
 * 但路径不重叠（本类管"人"的薪酬考勤，那个管"钱"的流水台账）。
 * PDF 下载端点是文件下载的标准写法：Content-Disposition 头 + URL 编码文件名
 * （中文文件名必须编码，否则浏览器会乱码）。</p>
 */
@Tag(name = "财务与薪酬")
@RestController
@RequestMapping("/api/finance")
public class FinanceReportController {

    private final PayrollService payrollService;
    private final FinanceReportPdfService pdfService;

    public FinanceReportController(PayrollService payrollService,
                                   FinanceReportPdfService pdfService) {
        this.payrollService = payrollService;
        this.pdfService = pdfService;
    }

    // ─────────────── 薪酬台账（财务维护） ───────────────

    @Operation(summary = "按月份分页查询薪酬台账")
    @GetMapping("/payroll")
    public ApiResponse<Page<EmployeePayroll>> payrollPage(
            @RequestParam String month,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "100") int pageSize) {
        return ApiResponse.success(payrollService.pagePayrollByMonth(month, pageNum, pageSize));
    }

    @Operation(summary = "批量维护薪酬（财务/领导）")
    @PutMapping("/payroll/batch")
    public ApiResponse<Map<String, Object>> upsertPayroll(
            @RequestBody List<EmployeePayroll> rows) {
        int count = payrollService.upsertPayroll(rows);
        return ApiResponse.success(Map.of("updatedCount", count));
    }

    @Operation(summary = "批量维护考勤（财务/领导）")
    @PutMapping("/attendance/batch")
    public ApiResponse<Map<String, Object>> upsertAttendance(
            @RequestBody List<EmployeeAttendance> rows) {
        int count = payrollService.upsertAttendance(rows);
        return ApiResponse.success(Map.of("updatedCount", count));
    }

    // ─────────────── 员工自助（仅本人） ───────────────

    @Operation(summary = "员工查看本人当月薪酬")
    @GetMapping("/hr/self/payroll")
    public ApiResponse<EmployeePayroll> selfPayroll(
            @RequestParam(required = false) String month) {
        return ApiResponse.success(payrollService.selfPayroll(month));
    }

    @Operation(summary = "员工查看本人考勤")
    @GetMapping("/hr/self/attendance")
    public ApiResponse<Map<String, Object>> selfAttendance(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month) {
        return ApiResponse.success(Map.of(
                "rows", payrollService.selfAttendance(year, month)));
    }

    @Operation(summary = "员工查看本人月度考勤汇总")
    @GetMapping("/hr/self/attendance/summary")
    public ApiResponse<Map<String, Object>> selfSummary(
            @RequestParam(required = false) String month) {
        return ApiResponse.success(payrollService.selfMonthSummary(month));
    }

    // ─────────────── AI 财务简报 ───────────────

    @Operation(summary = "财务/薪资汇总快照")
    @GetMapping("/summary/compact")
    public ApiResponse<Map<String, Object>> compactSummary() {
        return ApiResponse.success(payrollService.financeSummary());
    }

    @Operation(summary = "生成 AI 财务简报")
    @PostMapping("/report/generate")
    public ApiResponse<FinanceReport> generateReport(
            @RequestParam(required = false) String month) {
        return ApiResponse.success(payrollService.generateReport(month));
    }

    @Operation(summary = "财务简报列表")
    @GetMapping("/reports")
    public ApiResponse<Page<FinanceReport>> reports(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(payrollService.pageReports(pageNum, pageSize));
    }

    @Operation(summary = "导出财务简报 PDF")
    @PostMapping("/reports/{reportId}/pdf")
    public ResponseEntity<ByteArrayResource> reportPdf(@PathVariable Long reportId) {
        FinanceReport report = payrollService.getReport(reportId);
        List<Map<String, Object>> payrollRows =
                payrollService.payrollRowsByMonth(report.getReportMonth() == null
                        ? payrollService.latestPayrollMonthOrCurrent()
                        : report.getReportMonth());
        String path = pdfService.exportReportPdf(report, payrollRows,
                payrollService.financeSummary(report.getReportMonth() == null
                        ? payrollService.latestPayrollMonthOrCurrent()
                        : report.getReportMonth()));
        byte[] bytes = pdfService.readPdf(path);
        String fileName = pdfService.fileNameFor(report);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new ByteArrayResource(bytes));
    }
}
