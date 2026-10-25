package com.argus.rag.excel.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FinanceColumn} 财务标准字段表头识别契约（纯 JVM/零 mock/零 IO）。
 * matches：下划线无关（别名去空格+小写归一）、大小写不敏感、中英文别名命中、
 * null/空白→false、空白不同别名不误匹配；fromHeader：命中返回标准列、未命中返回 null。
 * （本类为「表头文本→标准财务字段」纯映射，不涉及财务数据访问判定，不触碰财务隔离红线。）
 */
@DisplayName("财务标准字段表头识别（FinanceColumn）")
class FinanceColumnTest {

    @Test
    void aliasesNormalizeWhitespaceAndCase() {
        assertTrue(FinanceColumn.SUMMARY.matches("交 易 说 明"));       // 别名去空格后命中
        assertTrue(FinanceColumn.TRANSACTION_DATE.matches("  Transaction Date ")); // 大小写+空白
        assertTrue(FinanceColumn.TRANSACTION_DATE.matches("交易时间")); // 中英别名任一命中
    }

    @Test
    void nullAndBlankHeadersDoNotMatch() {
        assertFalse(FinanceColumn.DEBIT.matches(null));
        assertFalse(FinanceColumn.DEBIT.matches("   "));
        assertFalse(FinanceColumn.DEBIT.matches(""));
    }

    @Test
    void unmatchedHeaderReturnsFalse() {
        assertFalse(FinanceColumn.DEBIT.matches("税额"));
        assertFalse(FinanceColumn.INCOME.matches("营业外收入")); // 非完整别名命中
    }

    @Test
    void fromHeaderResolvesMatch() {
        assertEquals(FinanceColumn.DEBIT, FinanceColumn.fromHeader("借方金额"));
        assertEquals(FinanceColumn.BALANCE, FinanceColumn.fromHeader(" 结余 "));
        assertEquals(FinanceColumn.EXPENSE, FinanceColumn.fromHeader("EXPENSE AMOUNT"));
    }

    @Test
    void fromHeaderMissReturnsNull() {
        assertNull(FinanceColumn.fromHeader("未知列"));
        assertNull(FinanceColumn.fromHeader(null));
    }

    @Test
    void singleAliasColumnsStillMatchTheirExactText() {
        assertTrue(FinanceColumn.MEETING_NOTE.matches("会议费备注"));
        assertFalse(FinanceColumn.MEETING_NOTE.matches("会议费"));
        assertTrue(FinanceColumn.ROW_NO.matches("序号"));
        assertTrue(FinanceColumn.FIXED_PURCHASE.matches("定期购买"));
    }
}