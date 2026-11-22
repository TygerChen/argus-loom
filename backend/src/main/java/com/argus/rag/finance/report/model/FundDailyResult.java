package com.argus.rag.finance.report.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 资金日报整体结果：顶层卡片 + 公司分组 + 警告与勾稽状态。
 * 同时是 report_execution.result_json 与钉钉推送的数据源（严格同源）。
 */
@Data
public class FundDailyResult {

    private String reportTitle;

    private LocalDate reportDate;

    private String displayUnit;

    private BigDecimal totalPrev;

    private BigDecimal totalIncome;

    private BigDecimal totalPayout;

    private BigDecimal totalBalance;

    /** 合计勾稽：total_balance == total_prev + total_income - total_payout */
    private boolean balanceCheckOk;

    /** 勾稽差额（balanceCheckOk=false 时排查用） */
    private BigDecimal balanceCheckDiff;

    private List<FundGroupResult> groups = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();
}
