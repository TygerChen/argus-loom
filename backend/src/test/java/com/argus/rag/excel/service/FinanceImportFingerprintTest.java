package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.FinanceLedgerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FinanceImportService} 数据规整/去重指纹纯方法行为锁
 * （static 同包直调，零 mapper/DB）。
 * <p>锁契约：text 窄 null→""；amount 值为 null→""、否则 stripTrailingZeros().toPlainString()
 * 规整（去掉尾零小数点）；recordFingerprint 以 18 段「|」拼接去重指纹——字符串字段走 text、
 * businessDate 走 toString、金额字段走 amount，用于导入幂等去重。</p>
 */
@DisplayName("财务导入数据规整与去重指纹（FinanceImportService.text/amount/recordFingerprint）")
class FinanceImportFingerprintTest {

    // ---------- text ----------

    @Test
    void textNullYieldsEmpty() {
        assertEquals("", FinanceImportService.text(null));
        assertEquals("abc", FinanceImportService.text("abc"));
    }

    // ---------- amount ----------

    @Test
    void amountNullYieldsEmpty() {
        assertEquals("", FinanceImportService.amount(null));
    }

    @Test
    void amountStripsTrailingZeros() {
        assertEquals("100", FinanceImportService.amount(new BigDecimal("100.00")));
        assertEquals("100.5", FinanceImportService.amount(new BigDecimal("100.50")));
        assertEquals("5", FinanceImportService.amount(new BigDecimal("5")));
    }

    // ---------- recordFingerprint ----------

    private static FinanceLedgerRecord rec(String day, String summary, LocalDate date, BigDecimal debit, String currency) {
        FinanceLedgerRecord r = new FinanceLedgerRecord();
        r.setDayLabel(day);
        r.setSummary(summary);
        r.setBusinessDate(date);
        r.setDebit(debit);
        r.setCurrencyCode(currency);
        return r;
    }

    @Test
    void fingerprintJoinsAllSections() {
        FinanceLedgerRecord r = rec("D1", "S", LocalDate.of(2026, 2, 1), new BigDecimal("100.00"), "CNY");
        String fp = FinanceImportService.recordFingerprint(r);
        // D1|S|||||2026-02-01|100|||||||||CNY|
        assertEquals("D1|S|||||2026-02-01|100|||||||||CNY|", fp);
    }

    @Test
    void fingerprintNullFieldsCollapseToEmpty() {
        FinanceLedgerRecord r = new FinanceLedgerRecord();
        assertEquals("|||||||||||||||||", FinanceImportService.recordFingerprint(r));
    }
}