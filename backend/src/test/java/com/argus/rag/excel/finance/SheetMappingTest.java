package com.argus.rag.excel.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SheetMapping} 单 Sheet 表头映射契约（纯 JVM/零 mock/零 IO）。
 * 构造即 dataStartRow=headerRow+1；put 是 putIfAbsent（同列首次识别优先，重复别名不覆盖）；
 * column/has/getColumnMap 透传；addWarning 追加、getWarnings 返回原列表；reportDate 可设可读。
 */
@DisplayName("Sheet 表头映射（SheetMapping）")
class SheetMappingTest {

    @Test
    void constructorDerivesDataStartRowFromHeaderRow() {
        SheetMapping mapping = new SheetMapping("收支表", 3);
        assertEquals("收支表", mapping.getSheetName());
        assertEquals(3, mapping.getHeaderRow());
        assertEquals(4, mapping.getDataStartRow());
    }

    @Test
    void putIsFirstWinsForRepeatedColumnAlias() {
        SheetMapping mapping = new SheetMapping("t", 0);
        mapping.put(FinanceColumn.DEBIT, 2);
        mapping.put(FinanceColumn.DEBIT, 5); // 第二次出现：不覆盖首次
        assertEquals(2, mapping.column(FinanceColumn.DEBIT));
    }

    @Test
    void putIfAbsentKeepsDistinctColumnsIndependent() {
        SheetMapping mapping = new SheetMapping("t", 0);
        mapping.put(FinanceColumn.DEBIT, 2);
        mapping.put(FinanceColumn.CREDIT, 3);
        mapping.put(FinanceColumn.BALANCE, 4);
        assertEquals(2, mapping.column(FinanceColumn.DEBIT));
        assertEquals(3, mapping.column(FinanceColumn.CREDIT));
        assertEquals(4, mapping.column(FinanceColumn.BALANCE));
        assertTrue(mapping.has(FinanceColumn.CREDIT));
        assertFalse(mapping.has(FinanceColumn.SUMMARY));
    }

    @Test
    void missingColumnReturnsNull() {
        SheetMapping mapping = new SheetMapping("t", 0);
        assertNull(mapping.column(FinanceColumn.EXPENSE));
        assertFalse(mapping.has(FinanceColumn.INCOME));
    }

    @Test
    void columnMapReflectsAllPutEntriesAndInsertionOrder() {
        SheetMapping mapping = new SheetMapping("t", 0);
        mapping.put(FinanceColumn.DEBIT, 2);
        mapping.put(FinanceColumn.CREDIT, 3);
        assertEquals(2, mapping.getColumnMap().size());
        // LinkedHashMap 保持插入序
        assertEquals(List.of(FinanceColumn.DEBIT, FinanceColumn.CREDIT),
                List.copyOf(mapping.getColumnMap().keySet()));
    }

    @Test
    void warningsAppendInOrderAndReturnUnderlyingList() {
        SheetMapping mapping = new SheetMapping("t", 0);
        mapping.addWarning("第一行缺日期");
        mapping.addWarning("存在未识别列");
        assertEquals(2, mapping.getWarnings().size());
        assertEquals(List.of("第一行缺日期", "存在未识别列"), mapping.getWarnings());
        // 返回的是内部列表引用
        assertSame(mapping.getWarnings(), mapping.getWarnings());
    }

    @Test
    void reportDateIsSettableAndReadable() {
        SheetMapping mapping = new SheetMapping("t", 0);
        LocalDate date = LocalDate.of(2026, 9, 19);
        assertNull(mapping.getReportDate());
        mapping.setReportDate(date);
        assertEquals(date, mapping.getReportDate());
    }
}