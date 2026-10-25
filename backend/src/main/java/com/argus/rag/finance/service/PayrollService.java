package com.argus.rag.finance.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.util.DateFormats;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.service.ExcelAuditService;
import com.argus.rag.finance.entity.EmployeeAttendance;
import com.argus.rag.finance.entity.EmployeePayroll;
import com.argus.rag.finance.entity.FinanceReport;
import com.argus.rag.finance.mapper.EmployeeAttendanceMapper;
import com.argus.rag.finance.mapper.EmployeePayrollMapper;
import com.argus.rag.finance.mapper.FinanceReportMapper;
import com.argus.rag.llm.ChatResult;
import com.argus.rag.llm.ModelClient;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考勤薪酬台账与 AI 财务简报。
 * <p>员工只能查询本人数据；写操作仅财务/领导角色可执行并写入审计日志。</p>
 *
 * <p><b>权限模型（03 篇 IDOR 防御的教科书场景）</b>：薪酬是全系统敏感度最高的数据，
 * 本类的每一个查询方法都分两个世界——"财务统一维护"区（财务角色看所有人）
 * 和"员工自助"区（self 前缀，userId 强制取自登录态而不是前端参数）。
 * 员工自助方法从不接收 userId 参数，从根上消灭了"改参数查别人工资"的可能，
 * 这比"接收 userId 再校验"更安全——**不存在可以被篡改的输入**。</p>
 *
 * <p><b>AI 简报的可信设计</b>：generateReport 调大模型生成月度经营简报，
 * 但喂给模型的只是聚合数字（financeSummary 的 SUM 结果），
 * 模型负责"把数字讲成人话"，不接触任何个人薪酬明细；生成失败也落库
 * （status=FAILED + errorMessage）再抛业务异常——失败记录本身就是审计素材。</p>
 */
@Service
public class PayrollService {

    private static final DateTimeFormatter MONTH_FORMAT = DateFormats.MONTH;

    private final EmployeePayrollMapper payrollMapper;
    private final EmployeeAttendanceMapper attendanceMapper;
    private final FinanceReportMapper reportMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final ExcelAuditService auditService;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public PayrollService(EmployeePayrollMapper payrollMapper,
                          EmployeeAttendanceMapper attendanceMapper,
                          FinanceReportMapper reportMapper,
                          JdbcTemplate jdbcTemplate,
                          CurrentUserService currentUserService,
                          ExcelAuditService auditService,
                          ModelClient modelClient,
                          ObjectMapper objectMapper) {
        this.payrollMapper = payrollMapper;
        this.attendanceMapper = attendanceMapper;
        this.reportMapper = reportMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    // ────────────────────────── 财务统一维护 ──────────────────────────

    /** 财务视角：按月分页看全员薪酬（控制器层已用 requireFinance 拦过一道，这里不再重复）。 */
    public Page<EmployeePayroll> pagePayrollByMonth(String month, int pageNum, int pageSize) {
        validateMonth(month);
        Page<EmployeePayroll> page = new Page<>(Math.max(pageNum, 1), Math.min(pageSize, 200));
        QueryWrapper<EmployeePayroll> qw = new QueryWrapper<>();
        qw.eq("payroll_month", month).orderByDesc("id");
        return payrollMapper.selectPage(page, qw);
    }

    @Transactional(rollbackFor = Exception.class)
    /**
     * 批量维护薪酬（有则更新、无则插入 = upsert）。
     * 每行都做三件事：必填校验 → 月份格式标准化 → 重算实发工资
     * （computeNetSalary 在服务端算，绝不信任前端传的 netSalary）。
     * 整批一个事务，最后写一条审计日志——改工资必须有据可查。
     */
    public int upsertPayroll(List<EmployeePayroll> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCodes.FINANCE_DATA_EMPTY, "薪酬数据不能为空");
        }
        CurrentUserService.CurrentUser operator = currentUserService.getRequiredCurrentUser();
        int changed = 0;
        for (EmployeePayroll row : rows) {
            requireUserId(row.getUserId(), "员工不能为空");
            String month = validateMonth(row.getPayrollMonth());
            row.setPayrollMonth(month);
            normalizeAmounts(row);
            row.setNetSalary(computeNetSalary(row));
            row.setUpdatedBy(operator.userId());
            row.setUpdatedByName(operator.userCode());
            row.setUpdateTime(LocalDateTime.now());

            EmployeePayroll exist = queryPayroll(row.getUserId(), month);
            if (exist == null) {
                row.setCreatedBy(operator.userId());
                row.setCreatedByName(operator.userCode());
                row.setCreateTime(LocalDateTime.now());
                payrollMapper.insert(row);
            } else {
                row.setId(exist.getId());
                row.setCreatedBy(exist.getCreatedBy());
                row.setCreatedByName(exist.getCreatedByName());
                payrollMapper.updateById(row);
            }
            changed++;
        }
        auditService.record("PAYROLL_UPSERT", "employee_payroll", null,
                "批量维护薪酬台账 " + changed + " 条，月份=" + rows.get(0).getPayrollMonth());
        return changed;
    }

    @Transactional(rollbackFor = Exception.class)
    /** 批量维护考勤：与薪酬同款的 upsert + 审计模式，唯一键是 (user_id, work_date)。 */
    public int upsertAttendance(List<EmployeeAttendance> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCodes.FINANCE_DATA_EMPTY, "考勤数据不能为空");
        }
        CurrentUserService.CurrentUser operator = currentUserService.getRequiredCurrentUser();
        int changed = 0;
        for (EmployeeAttendance row : rows) {
            requireUserId(row.getUserId(), "员工不能为空");
            if (row.getWorkDate() == null) {
                throw new BusinessException(ErrorCodes.FINANCE_DATA_EMPTY, "考勤日期不能为空");
            }
            normalizeAttendanceRow(row);
            row.setUpdatedBy(operator.userId());
            row.setUpdatedByName(operator.userCode());
            row.setUpdateTime(LocalDateTime.now());

            EmployeeAttendance exist = attendanceMapper.selectOne(
                    new QueryWrapper<EmployeeAttendance>()
                            .eq("user_id", row.getUserId())
                            .eq("work_date", row.getWorkDate())
                            .last("LIMIT 1"));
            if (exist == null) {
                row.setCreatedBy(operator.userId());
                row.setCreatedByName(operator.userCode());
                row.setCreateTime(LocalDateTime.now());
                attendanceMapper.insert(row);
            } else {
                row.setId(exist.getId());
                row.setCreatedBy(exist.getCreatedBy());
                row.setCreatedByName(exist.getCreatedByName());
                attendanceMapper.updateById(row);
            }
            changed++;
        }
        auditService.record("ATTENDANCE_UPSERT", "employee_attendance", null,
                "批量维护考勤 " + changed + " 条");
        return changed;
    }

    // ────────────────────────── 员工自助（仅本人） ─────────────────────────

    /** 员工查自己当月（或指定月）工资单。userId 取自登录态——见类注释的权限模型。 */
    public EmployeePayroll selfPayroll(String month) {
        Long userId = currentUserService.getRequiredCurrentUser().userId();
        String targetMonth = month == null || month.isBlank()
                ? YearMonth.now().format(MONTH_FORMAT) : validateMonth(month);
        EmployeePayroll payroll = queryPayroll(userId, targetMonth);
        if (payroll == null) {
            throw new BusinessException(ErrorCodes.FINANCE_PAYROLL_NOT_AVAILABLE, "该月薪酬尚未发布或不存在");
        }
        return payroll;
    }

    /** 员工查自己某月的考勤明细，按日期升序。 */
    public List<EmployeeAttendance> selfAttendance(int year, int month) {
        Long userId = currentUserService.getRequiredCurrentUser().userId();
        if (year <= 0) year = LocalDate.now().getYear();
        if (month <= 0) month = LocalDate.now().getMonthValue();
        YearMonth ym = YearMonth.of(year, month);
        QueryWrapper<EmployeeAttendance> qw = new QueryWrapper<>();
        qw.eq("user_id", userId)
                .ge("work_date", ym.atDay(1))
                .le("work_date", ym.atEndOfMonth())
                .orderByAsc("work_date");
        return attendanceMapper.selectList(qw);
    }

    /** 员工月度自助汇总：出勤天数 + 迟到/早退/加班分钟数聚合（在 Java 侧求和）。 */
    public Map<String, Object> selfMonthSummary(String month) {
        String targetMonth = month == null || month.isBlank()
                ? YearMonth.now().format(MONTH_FORMAT) : validateMonth(month);
        Long userId = currentUserService.getRequiredCurrentUser().userId();
        YearMonth ym = YearMonth.parse(targetMonth, MONTH_FORMAT);
        List<Map<String, Object>> attendanceRows = attendanceMapper.selectMaps(
                new QueryWrapper<EmployeeAttendance>()
                        .select("work_date", "status", "late_minutes", "early_minutes",
                                "overtime_minutes", "leave_type", "leave_hours")
                        .eq("user_id", userId)
                        .ge("work_date", ym.atDay(1))
                        .le("work_date", ym.atEndOfMonth())
                        .orderByAsc("work_date"));
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("days", attendanceRows.size());
        total.put("lateMinutes", sumInt(attendanceRows, "late_minutes"));
        total.put("earlyMinutes", sumInt(attendanceRows, "early_minutes"));
        total.put("overtimeMinutes", sumInt(attendanceRows, "overtime_minutes"));
        return Map.of("month", targetMonth, "attendanceSummary", total, "rows", attendanceRows);
    }

    // ────────────────────────── AI 简报 ──────────────────────────

    public Map<String, Object> financeSummary() {
        return financeSummary(latestPayrollMonthOrCurrent());
    }

    /** 指定月的经营汇总：流水按业务日期过滤，薪酬按结算月份过滤。 */
    public Map<String, Object> financeSummary(String month) {
        String targetMonth = validateMonth(month);
        YearMonth ym = YearMonth.parse(targetMonth, MONTH_FORMAT);
        Map<String, Object> ledger = jdbcTemplate.queryForList(
                "SELECT COALESCE(SUM(debit),0) AS debit, COALESCE(SUM(credit),0) AS credit, "
                        + "COALESCE(SUM(income),0) AS income, COALESCE(SUM(expense),0) AS expense, "
                        + "COUNT(*) AS records FROM finance_ledger_record "
                        + "WHERE business_date >= ? AND business_date <= ?",
                ym.atDay(1), ym.atEndOfMonth())
                .stream().findFirst().orElseGet(this::zeroLedgerSummary);
        Map<String, Object> payroll = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS headcount, COALESCE(SUM(base_salary),0) AS baseSalary, "
                        + "COALESCE(SUM(net_salary),0) AS netSalary, "
                        + "COALESCE(SUM(performance_bonus + attendance_bonus + overtime_pay + allowance + subsidy),0) AS bonus "
                        + "FROM employee_payroll WHERE payroll_month = ?", targetMonth)
                .stream().findFirst().orElseGet(this::zeroPayrollMap);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", targetMonth);
        result.put("ledger", ledger);
        result.put("payroll", payroll);
        return result;
    }

    /**
     * AI 财务简报生成。三个值得注意的工程细节：
     * 1. 失败也落库：模型调用抛异常时先存一条 FAILED 报告（带错误摘要）再抛业务异常，
     *    管理页面能看到"失败历史"，而不是无声失败；
     * 2. 全程审计：成功/失败都写 ExcelAuditService，谁在什么时候生成了简报可追溯；
     * 3. 提示词分层：system 级指令（financeReportInstructions，定角色和输出格式）
     *    与用户消息（buildBriefPrompt，塞当月聚合数据）分开传。
     */
    public FinanceReport generateReport(String month) {
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        String targetMonth = month == null || month.isBlank()
                ? YearMonth.now().format(MONTH_FORMAT) : validateMonth(month);
        ChatResult result;
        try {
            result = modelClient.chat(
                    List.of(new com.argus.rag.llm.ModelClient.ChatMessage("user",
                            buildBriefPrompt(targetMonth))),
                    financeReportInstructions());
        } catch (RuntimeException e) {
            FinanceReport failed = new FinanceReport();
            failed.setTitle("财务经营简报 " + targetMonth);
            failed.setReportMonth(targetMonth);
            failed.setGeneratedBy(user.userId());
            failed.setGeneratedByName(user.userCode());
            failed.setStatus("FAILED");
            failed.setErrorMessage(cut(e.getMessage() == null ? "模型生成失败" : e.getMessage(), 900));
            reportMapper.insert(failed);
            auditService.record("FINANCE_REPORT_FAILED", "finance_report", "", "AI 简报生成失败");
            throw new BusinessException(ErrorCodes.FINANCE_AI_REPORT_FAILED, "AI 简报生成失败：" + failed.getErrorMessage());
        }
        String content = result.content() == null ? "（模型未返回内容）" : result.content();
        FinanceReport report = new FinanceReport();
        report.setTitle("财务经营简报 " + targetMonth);
        report.setReportMonth(targetMonth);
        report.setContentMarkdown(content);
        report.setModel(result.modelName() == null ? "default" : result.modelName());
        report.setGeneratedBy(user.userId());
        report.setGeneratedByName(user.userCode());
        report.setStatus("SUCCESS");
        report.setCreateTime(LocalDateTime.now());
        reportMapper.insert(report);
        auditService.record("FINANCE_REPORT_GENERATE", "finance_report",
                report.getId() == null ? "" : report.getId().toString(),
                "AI 财务简报生成，长度 " + content.length());
        return report;
    }

    public Page<FinanceReport> pageReports(int pageNum, int pageSize) {
        Page<FinanceReport> page = new Page<>(Math.max(pageNum, 1), Math.min(pageSize, 50));
        QueryWrapper<FinanceReport> qw = new QueryWrapper<>();
        qw.orderByDesc("create_time").orderByDesc("id");
        return reportMapper.selectPage(page, qw);
    }

    public FinanceReport getReport(Long reportId) {
        FinanceReport report = reportMapper.selectById(reportId);
        if (report == null) throw new BusinessException(ErrorCodes.FINANCE_REPORT_NOT_FOUND, "财务简报不存在");
        return report;
    }

    public List<Map<String, Object>> payrollRowsByMonth(String month) {
        validateMonth(month);
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT p.user_id AS userId, u.user_code AS userCode, u.display_name AS displayName, "
                        + "p.base_salary AS baseSalary, p.performance_bonus AS performanceBonus, "
                        + "p.attendance_bonus AS attendanceBonus, p.overtime_pay AS overtimePay, "
                        + "p.allowance, p.subsidy, p.other_bonus AS otherBonus, p.social_security AS socialSecurity, "
                        + "p.housing_fund AS housingFund, p.personal_tax AS personalTax, p.deduction, "
                        + "p.other_deduction AS otherDeduction, p.net_salary AS netSalary, "
                        + "p.pay_status AS payStatus, p.work_days AS workDays "
                        + "FROM employee_payroll p JOIN users u ON u.id = p.user_id "
                        + "WHERE p.payroll_month = ? ORDER BY p.user_id",
                (rs, rowNum) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("userId", rs.getLong("userId"));
                    r.put("userCode", rs.getString("userCode"));
                    r.put("displayName", rs.getString("displayName"));
                    r.put("workDays", rs.getInt("workDays"));
                    r.put("baseSalary", rs.getBigDecimal("baseSalary"));
                    r.put("performanceBonus", rs.getBigDecimal("performanceBonus"));
                    r.put("attendanceBonus", rs.getBigDecimal("attendanceBonus"));
                    r.put("overtimePay", rs.getBigDecimal("overtimePay"));
                    r.put("allowance", rs.getBigDecimal("allowance"));
                    r.put("subsidy", rs.getBigDecimal("subsidy"));
                    r.put("otherBonus", rs.getBigDecimal("otherBonus"));
                    r.put("socialSecurity", rs.getBigDecimal("socialSecurity"));
                    r.put("housingFund", rs.getBigDecimal("housingFund"));
                    r.put("personalTax", rs.getBigDecimal("personalTax"));
                    r.put("deduction", rs.getBigDecimal("deduction"));
                    r.put("otherDeduction", rs.getBigDecimal("otherDeduction"));
                    r.put("netSalary", rs.getBigDecimal("netSalary"));
                    r.put("payStatus", rs.getString("payStatus"));
                    return r;
                }, month);
        return rows;
    }

    public String latestPayrollMonthOrCurrent() {
        String latest = latestPayrollMonth();
        return latest == null ? YearMonth.now().format(MONTH_FORMAT) : latest;
    }

    private EmployeePayroll queryPayroll(Long userId, String month) {
        return payrollMapper.selectOne(new QueryWrapper<EmployeePayroll>()
                .eq("user_id", userId)
                .eq("payroll_month", month)
                .last("LIMIT 1"));
    }

    private String financeReportInstructions() {
        return "你是企业财务分析助手。只能基于提供的经营数据输出简明中国财务简报，严禁编造数据。"
                + "结构固定：## 经营概况、## 人员与薪酬成本、## 风险与异常提示、## 管理建议。"
                + "金额保留两位小数，Markdown 输出，不要添加免责声明之外的虚构数字。";
    }

    private String buildBriefPrompt(String month) {
        String targetMonth = validateMonth(month);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", targetMonth);
        data.put("ledger", financeOverviewForPrompt(targetMonth));
        List<Map<String, Object>> payrollRows = payrollRowsByMonth(targetMonth);
        data.put("payroll", payrollRows);
        data.put("payrollSummary", financeSummary(targetMonth).get("payroll"));
        try {
            return "请基于以下财务摘要生成企业财务简报（月份 " + targetMonth + "）：\n"
                    + "本月薪酬合计：员工 " + payrollRows.size() + " 人，明细见 payroll，"
                    + "汇总口径见 payrollSummary，务必在这一节体现人均与人力成本。\n"
                    + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            return "请基于以下财务摘要生成企业财务简报：" + data;
        }
    }

    private Map<String, Object> financeOverviewForPrompt(String month) {
        YearMonth ym = YearMonth.parse(validateMonth(month), MONTH_FORMAT);
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT COALESCE(SUM(debit),0) AS debit, COALESCE(SUM(credit),0) AS credit, "
                            + "COALESCE(SUM(income),0) AS income, COALESCE(SUM(expense),0) AS expense, "
                            + "COUNT(*) AS records FROM finance_ledger_record "
                            + "WHERE business_date >= ? AND business_date <= ?",
                    ym.atDay(1), ym.atEndOfMonth());
        } catch (EmptyResultDataAccessException e) {
            return zeroLedgerMap();
        }
    }

    private Map<String, Object> zeroLedgerMap() {
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("debit", BigDecimal.ZERO);
        zero.put("credit", BigDecimal.ZERO);
        zero.put("income", BigDecimal.ZERO);
        zero.put("expense", BigDecimal.ZERO);
        zero.put("records", 0);
        return zero;
    }

    private Map<String, Object> zeroLedgerSummary() {
        return zeroLedgerMap();
    }

    private Map<String, Object> zeroPayrollMap() {
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("headcount", 0);
        zero.put("baseSalary", BigDecimal.ZERO);
        zero.put("netSalary", BigDecimal.ZERO);
        zero.put("bonus", BigDecimal.ZERO);
        return zero;
    }

    private String validateMonth(String month) {
        String value = month == null || month.isBlank() ? YearMonth.now().format(MONTH_FORMAT) : month.trim();
        try {
            YearMonth.parse(value, MONTH_FORMAT);
            return value;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.FINANCE_MONTH_FORMAT_INVALID, "月份格式非法，应为 YYYY-MM");
        }
    }

    private static void requireUserId(Long userId, String message) {
        if (userId == null || userId <= 0) throw new BusinessException(ErrorCodes.FINANCE_DATA_EMPTY, message);
    }

    /** 包内静态（无实例态），供 JVM 行为锁直接调用。 */
    static void normalizeAmounts(EmployeePayroll row) {
        row.setBaseSalary(amount(row.getBaseSalary()));
        row.setPerformanceBonus(amount(row.getPerformanceBonus()));
        row.setAttendanceBonus(amount(row.getAttendanceBonus()));
        row.setOvertimePay(amount(row.getOvertimePay()));
        row.setAllowance(amount(row.getAllowance()));
        row.setSubsidy(amount(row.getSubsidy()));
        row.setOtherBonus(amount(row.getOtherBonus()));
        row.setSocialSecurity(amount(row.getSocialSecurity()));
        row.setHousingFund(amount(row.getHousingFund()));
        row.setPersonalTax(amount(row.getPersonalTax()));
        row.setDeduction(amount(row.getDeduction()));
        row.setOtherDeduction(amount(row.getOtherDeduction()));
        if (row.getWorkDays() == null) row.setWorkDays(0);
        if (row.getPayStatus() == null || row.getPayStatus().isBlank()) row.setPayStatus("DRAFT");
    }

    /** 包内可见 static（无实例态），供 JVM 行为锁直接调用。 */
    static BigDecimal computeNetSalary(EmployeePayroll r) {
        return r.getBaseSalary()
                .add(r.getPerformanceBonus())
                .add(r.getAttendanceBonus())
                .add(r.getOvertimePay())
                .add(r.getAllowance())
                .add(r.getSubsidy())
                .add(r.getOtherBonus())
                .subtract(r.getSocialSecurity())
                .subtract(r.getHousingFund())
                .subtract(r.getPersonalTax())
                .subtract(r.getDeduction())
                .subtract(r.getOtherDeduction());
    }

    /** 包内可见 static（无实例态），供 JVM 行为测试直接调用。 */
    static void normalizeAttendanceRow(EmployeeAttendance row) {
        if (row.getLateMinutes() == null) row.setLateMinutes(0);
        if (row.getEarlyMinutes() == null) row.setEarlyMinutes(0);
        if (row.getOvertimeMinutes() == null) row.setOvertimeMinutes(0);
        if (row.getLeaveHours() == null) row.setLeaveHours(BigDecimal.ZERO);
        row.setStatus(row.getStatus() == null || row.getStatus().isBlank()
                ? "NORMAL" : row.getStatus().trim().toUpperCase());
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int sumInt(List<Map<String, Object>> rows, String key) {
        int total = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (value instanceof Number number) total += number.intValue();
        }
        return total;
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String latestPayrollMonth() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT payroll_month FROM employee_payroll ORDER BY payroll_month DESC LIMIT 1",
                    String.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
