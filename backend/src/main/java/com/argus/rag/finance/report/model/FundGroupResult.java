package com.argus.rag.finance.report.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司分组小计（严格一个 Excel 文件 = 一个分组），金额为人民币口径。
 */
@Data
public class FundGroupResult {

    private String company;

    private String sourceFile;

    private BigDecimal prev;

    private BigDecimal income;

    private BigDecimal payout;

    private BigDecimal balance;

    private BigDecimal transferIncome;

    private BigDecimal transferPayout;

    private BigDecimal financeIncome;

    private BigDecimal financePayout;

    private List<FundAccountResult> accounts = new ArrayList<>();
}
