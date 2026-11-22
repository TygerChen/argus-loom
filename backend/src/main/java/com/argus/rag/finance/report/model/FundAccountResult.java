package com.argus.rag.finance.report.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 账户级日报结果（单 Sheet 单账户）。
 * 口径与技能 gen_fund_daily.calc_account 对齐：income/payout 为净额
 * （内部转账与理财已剔入各自桶），prev/balance 含定期余额。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FundAccountResult {

    private String name;

    private String company;

    private String sourceFile;

    private String currency;

    /** 是否发生外币折算 */
    private boolean converted;

    private BigDecimal prev;

    private BigDecimal income;

    private BigDecimal payout;

    private BigDecimal balance;

    private BigDecimal regularBalance;

    private BigDecimal fixedDepositBalance;

    private BigDecimal regularPrev;

    private BigDecimal fixedDepositPrev;

    private BigDecimal transferIncome;

    private BigDecimal transferPayout;

    private BigDecimal financeIncome;

    private BigDecimal financePayout;

    private boolean hasData;

    /** 结转账户：基准日无流水，余额取最近 prior 日收盘 */
    private boolean carriedForward;
}
