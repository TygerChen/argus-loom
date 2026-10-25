package com.argus.rag.excel.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 财务台账里“编辑某一行流水”时可能修改的字段。
 * 字段为 null 表示本次不改。
 */
public record FinanceRecordUpdateRequest(
        Long id,
        LocalDate businessDate,
        String dayLabel,
        String summary,
        String counterparty,
        String department,
        String category,
        String expenseName,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance,
        BigDecimal prevBalance,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal fixedPurchase,
        BigDecimal fixedRedemption,
        BigDecimal fixedBalance,
        String currencyCode,
        String warnings,
        String expectedFingerprint
) {
}
