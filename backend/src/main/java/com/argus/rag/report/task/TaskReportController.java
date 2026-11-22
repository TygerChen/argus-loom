package com.argus.rag.report.task;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.finance.report.entity.ReportExecution;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 任务进度日报接口 —— 通用日报引擎实例 #2（在线表格数据源）。
 * <p>登录即可用（团队工作流数据，非财务隔离域）；数据按 owner 隔离。</p>
 */
@RestController
@RequestMapping("/api/reports/task-daily")
@Tag(name = "任务进度日报", description = "绑定在线任务表 → 聚合进度 → 简洁日报 → 钉钉推送")
public class TaskReportController {

    private final TaskDailyReportService reportService;
    private final CurrentUserService currentUserService;

    public TaskReportController(TaskDailyReportService reportService,
                                CurrentUserService currentUserService) {
        this.reportService = reportService;
        this.currentUserService = currentUserService;
    }

    /** 绑定任务表（取文档首个活跃 Sheet）。 */
    @PostMapping("/bind")
    public ApiResponse<Map<String, Object>> bind(@RequestBody Map<String, Long> body) {
        Long documentId = body.get("documentId");
        if (documentId == null || documentId <= 0) {
            throw new BusinessException("documentId 非法");
        }
        Long ownerId = currentUserService.getRequiredCurrentUser().userId();
        return ApiResponse.success(reportService.bindSource(ownerId, documentId));
    }

    /** 当前绑定。 */
    @GetMapping("/binding")
    public ApiResponse<Map<String, Object>> binding() {
        Long ownerId = currentUserService.getRequiredCurrentUser().userId();
        return ApiResponse.success(reportService.currentBinding(ownerId));
    }

    /** 生成/重跑当日（或指定日）日报。 */
    @PostMapping("/generate")
    public ApiResponse<ReportExecution> generate(@RequestParam(value = "date", required = false) String date) {
        Long ownerId = currentUserService.getRequiredCurrentUser().userId();
        return ApiResponse.success(reportService.generate(parseDate(date), ownerId));
    }

    /** 单日日报（含 Markdown 正文与 AI 解读）。 */
    @GetMapping
    public ApiResponse<ReportExecution> getByDate(@RequestParam(value = "date", required = false) String date) {
        Long ownerId = currentUserService.getRequiredCurrentUser().userId();
        return ApiResponse.success(reportService.getByDate(ownerId, parseDate(date)));
    }

    /** 历史列表。 */
    @GetMapping("/list")
    public ApiResponse<List<ReportExecution>> list(@RequestParam(value = "from", required = false) String from,
                                                   @RequestParam(value = "to", required = false) String to) {
        Long ownerId = currentUserService.getRequiredCurrentUser().userId();
        LocalDate to_ = parseDate(to, LocalDate.now());
        LocalDate from_ = parseDate(from, to_.minusDays(30));
        return ApiResponse.success(reportService.listExecutions(ownerId, from_, to_));
    }

    private LocalDate parseDate(String date) {
        return parseDate(date, LocalDate.now());
    }

    private LocalDate parseDate(String date, LocalDate fallback) {
        if (date == null || date.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException("日期格式非法：" + date);
        }
    }
}
