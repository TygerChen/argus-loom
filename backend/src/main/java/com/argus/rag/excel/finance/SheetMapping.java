package com.argus.rag.excel.finance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个 Sheet 的表头识别结果：列映射、表头行、数据起始行、报表日期。
 */
public class SheetMapping {

    private final String sheetName;
    private final int headerRow;
    private final int dataStartRow;
    private final Map<FinanceColumn, Integer> columnMap = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private LocalDate reportDate;

    public SheetMapping(String sheetName, int headerRow) {
        this.sheetName = sheetName;
        this.headerRow = headerRow;
        this.dataStartRow = headerRow + 1;
    }

    public void put(FinanceColumn column, int columnIndex) {
        // putIfAbsent：先识别到的列优先，重复出现的表头别名不再覆盖
        columnMap.putIfAbsent(column, columnIndex);
    }

    public Integer column(FinanceColumn column) {
        return columnMap.get(column);
    }

    public boolean has(FinanceColumn column) {
        return columnMap.containsKey(column);
    }

    public String getSheetName() {
        return sheetName;
    }

    public int getHeaderRow() {
        return headerRow;
    }

    public int getDataStartRow() {
        return dataStartRow;
    }

    public Map<FinanceColumn, Integer> getColumnMap() {
        return columnMap;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
