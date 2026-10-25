package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 财务标准流水：从 Excel 工作簿抽取后的结构化事实数据。
 */
@Data
@TableName("finance_ledger_record")
public class FinanceLedgerRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private Long documentId;

    private Integer sheetIndex;

    private String sheetName;

    /** Excel 原始行号（1 起始） */
    private Integer excelRow;

    /** DATA=流水 OPENING=期初 */
    private String recordType;

    private LocalDate businessDate;

    private String dayLabel;

    private String summary;

    private String counterparty;

    private String department;

    private String category;

    private String expenseName;

    private BigDecimal debit;

    private BigDecimal credit;

    private BigDecimal balance;

    private BigDecimal fixedPurchase;

    private BigDecimal fixedRedemption;

    private BigDecimal fixedBalance;

    /** 汇总表专用：上日余额 */
    private BigDecimal prevBalance;

    /** 汇总表专用：收入发生额 */
    private BigDecimal income;

    /** 汇总表专用：支出发生额 */
    private BigDecimal expense;

    private String currencyCode;

    /** 该行映射列的原始值 JSON，用于对账 */
    private String rawJson;

    /** 该行主要公式文本（如 =J3+H4-I4） */
    private String formulaText;

    /** 1=正常 2=字段不完整 3=跳过 */
    private Integer rowStatus;

    private String warnings;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
