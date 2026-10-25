package com.argus.rag.excel.finance;

import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将工作簿中的标准资金台账抽取为财务流水。
 *
 * <p>流程三步：1) 用 FinanceSheetResolver 识别某个 Sheet 的表头行和列映射；
 * 2) 从表头下一行开始逐行读数据；3) 把每个单元格翻译成 FinanceLedgerRecord
 * （日期、金额、摘要、往来单位等）。识别不了表头或整行没内容的 sheet 会被跳过。</p>
 */
@Component
public class FinanceWorkbookExtractor {

    private static final Logger log = LoggerFactory.getLogger(FinanceWorkbookExtractor.class);

    private final FinanceSheetResolver resolver;
    private final ObjectMapper objectMapper;

    public FinanceWorkbookExtractor(FinanceSheetResolver resolver, ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    /** 遍历工作簿里所有 Sheet，把能识别的都抽成流水，汇总到 ExtractionResult */
    public ExtractionResult extract(Workbook workbook, String defaultCurrencyName) {
        ExtractionResult result = new ExtractionResult();
        result.defaultCurrencyCode = normalizeCurrency(defaultCurrencyName);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                continue;
            }
            SheetMapping mapping = resolver.resolve(sheet);
            if (mapping == null) {
                result.skippedSheets.put(sheet.getSheetName(), "未识别到财务标准表头");
                continue;
            }
            int rows = extractSheet(sheet, sheetIndex, mapping, evaluator, formatter, result);
            if (rows > 0) {
                result.importedSheets++;
                result.sheetRows.put(sheet.getSheetName(), rows);
            } else {
                result.skippedSheets.put(sheet.getSheetName(), "已识别表头但没有有效数据行");
            }
        }
        result.mappedRows = result.records.size();
        return result;
    }

    /** 抽取一个 Sheet：从数据起始行读到末尾，返回成功抽取的行数 */
    private int extractSheet(Sheet sheet,
                             int sheetIndex,
                             SheetMapping mapping,
                             FormulaEvaluator evaluator,
                             DataFormatter formatter,
                             ExtractionResult result) {
        int rows = 0;
        for (int rowIndex = mapping.getDataStartRow(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            FinanceLedgerRecord record = buildRecord(
                    sheet, sheetIndex, mapping, row, evaluator, formatter, result.defaultCurrencyCode);
            if (record == null) {
                continue;
            }
            if (record.getRowStatus() != null && record.getRowStatus() == 3) {
                result.skippedRows++;
                continue;
            }
            result.records.add(record);
            rows++;
        }
        result.totalRows += rows;
        return rows;
    }

    /** 把一行 Excel 翻译成一条结构化流水记录 */
    private FinanceLedgerRecord buildRecord(Sheet sheet,
                                            int sheetIndex,
                                            SheetMapping mapping,
                                            Row row,
                                            FormulaEvaluator evaluator,
                                            DataFormatter formatter,
                                            String defaultCurrencyCode) {
        if (isBlankRow(row)) {
            return null;
        }
        FinanceLedgerRecord record = new FinanceLedgerRecord();
        record.setSheetIndex(sheetIndex);
        record.setSheetName(sheet.getSheetName());
        record.setExcelRow(row.getRowNum() + 1);
        record.setRecordType("DATA");
        record.setCurrencyCode(resolveCurrency(sheet.getSheetName(), mapping, row, evaluator, formatter, defaultCurrencyCode));

        String dateText = cellTextAt(row, mapping.column(FinanceColumn.TRANSACTION_DATE), evaluator, formatter);
        LocalDate date = dateCellAt(row, mapping, evaluator, formatter);
        if (date != null) {
            record.setBusinessDate(date);
        } else if (mapping.getReportDate() != null) {
            record.setBusinessDate(mapping.getReportDate());
        }
        String dayLabel = dateText == null ? "" : dateText.trim();
        if (date != null && isDateSerialLabel(dayLabel)) {
            dayLabel = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        record.setDayLabel(dayLabel);

        record.setSummary(textAt(row, mapping.column(FinanceColumn.SUMMARY), evaluator, formatter));
        record.setCounterparty(textAt(row, mapping.column(FinanceColumn.COUNTERPARTY), evaluator, formatter));
        record.setDepartment(textAt(row, mapping.column(FinanceColumn.DEPARTMENT), evaluator, formatter));
        record.setCategory(textAt(row, mapping.column(FinanceColumn.CATEGORY), evaluator, formatter));
        record.setExpenseName(textAt(row, mapping.column(FinanceColumn.EXPENSE_NAME), evaluator, formatter));
        record.setDebit(numberAt(row, mapping.column(FinanceColumn.DEBIT), evaluator));
        record.setCredit(numberAt(row, mapping.column(FinanceColumn.CREDIT), evaluator));
        record.setBalance(numberAt(row, mapping.column(FinanceColumn.BALANCE), evaluator));
        record.setFixedPurchase(numberAt(row, mapping.column(FinanceColumn.FIXED_PURCHASE), evaluator));
        record.setFixedRedemption(numberAt(row, mapping.column(FinanceColumn.FIXED_REDEMPTION), evaluator));
        record.setFixedBalance(numberAt(row, mapping.column(FinanceColumn.FIXED_BALANCE), evaluator));
        record.setPrevBalance(numberAt(row, mapping.column(FinanceColumn.PREVIOUS_BALANCE), evaluator));
        record.setIncome(numberAt(row, mapping.column(FinanceColumn.INCOME), evaluator));
        record.setExpense(numberAt(row, mapping.column(FinanceColumn.EXPENSE), evaluator));
        record.setRawJson(rawJson(row, evaluator, formatter));

        if (isOpeningRow(record)) {
            record.setRecordType("OPENING");
        }

        List<String> warnings = new ArrayList<>(mapping.getWarnings());
        record.setFormulaText(financeFormula(row, mapping, evaluator, warnings));
        if (record.getSummary() == null && notBlank(record.getDayLabel())) {
            record.setSummary(record.getDayLabel());
        }
        record.setWarnings(String.join("；", warnings));
        record.setRowStatus(rowStatus(record));
        return record;
    }

    private boolean isOpeningRow(FinanceLedgerRecord record) {
        return containsOpeningLabel(record.getDayLabel())
                || containsOpeningLabel(record.getSummary());
    }

    private boolean containsOpeningLabel(String text) {
        return text != null && (text.contains("期初") || text.contains("上年结转"));
    }

    private int rowStatus(FinanceLedgerRecord record) {
        boolean hasContent = record.getBusinessDate() != null
                || notBlank(record.getDayLabel())
                || notBlank(record.getSummary())
                || notBlank(record.getCounterparty())
                || record.getDebit() != null
                || record.getCredit() != null
                || record.getBalance() != null
                || record.getFixedBalance() != null
                || record.getPrevBalance() != null
                || record.getIncome() != null
                || record.getExpense() != null;
        if (!hasContent) {
            return 3;
        }
        if ("DATA".equals(record.getRecordType()) && record.getBusinessDate() == null) {
            return 2;
        }
        return 1;
    }

    /** 解析交易日期：支持真正的日期单元格，也支持“43000”这类 Excel 日期序列号 */
    private LocalDate dateCellAt(Row row,
                                 SheetMapping mapping,
                                 FormulaEvaluator evaluator,
                                 DataFormatter formatter) {
        Integer col = mapping.column(FinanceColumn.TRANSACTION_DATE);
        if (col == null) {
            return mapping.getReportDate();
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return mapping.getReportDate();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell) && cell.getDateCellValue() != null) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            double value = cell.getNumericCellValue();
            if (value > 30000 && value < 100000) {
                return DateUtil.getJavaDate(value).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            return null;
        }
        String text = cellTextAt(row, col, evaluator, formatter);
        if (text == null || text.isBlank()) {
            return mapping.getReportDate();
        }
        return parseDateText(text);
    }

    LocalDate parseDateText(String text) {
        return resolver.parseDateText(text);
    }

    private String textAt(Row row,
                          Integer col,
                          FormulaEvaluator evaluator,
                          DataFormatter formatter) {
        if (col == null) {
            return null;
        }
        return cellText(row.getCell(col), evaluator, formatter);
    }

    private String cellTextAt(Row row,
                              Integer col,
                              FormulaEvaluator evaluator,
                              DataFormatter formatter) {
        if (col == null) {
            return "";
        }
        return cellText(row.getCell(col), evaluator, formatter);
    }

    private String cellText(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        if (formatter == null) {
            formatter = new DataFormatter(Locale.CHINA);
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private BigDecimal numberAt(Row row, Integer colIndex, FormulaEvaluator evaluator) {
        if (colIndex == null) {
            return null;
        }
        return numberAtCell(row.getCell(colIndex), evaluator);
    }

    /** 把单元格内容转成金额 BigDecimal；公式会先计算，文本会尽量转成数字 */
    private BigDecimal numberAtCell(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            return BigDecimal.valueOf(value);
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                org.apache.poi.ss.usermodel.CellValue value = evaluator.evaluate(cell);
                if (value != null && value.getCellType() == CellType.NUMERIC) {
                    return BigDecimal.valueOf(value.getNumberValue());
                }
                if (value != null && value.getCellType() == CellType.STRING) {
                    return parseNumberText(value.getStringValue());
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        if (cell.getCellType() == CellType.STRING) {
            String text = cell.getStringCellValue();
            if (text != null && text.startsWith("=")) {
                return null;
            }
            return parseNumberText(text);
        }
        return null;
    }

    BigDecimal parseNumberText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.replace(",", "").replace(" ", "").trim();
        try {
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把一行里所有公式汇总（如 DEBIT=J3+H4-I4），并标记 #REF! 断链告警 */
    private String financeFormula(Row row,
                                  SheetMapping mapping,
                                  FormulaEvaluator evaluator,
                                  List<String> warnings) {
        List<String> formulas = new ArrayList<>();
        for (Map.Entry<FinanceColumn, Integer> entry : mapping.getColumnMap().entrySet()) {
            Cell cell = row.getCell(entry.getValue());
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                String formula = cell.getCellFormula();
                if (formula != null && formula.contains("#REF!")) {
                    warnings.add("存在断链公式: " + formula);
                }
                formulas.add(entry.getKey().name() + "=" + formula);
            }
        }
        return formulas.isEmpty() ? null : String.join(";", formulas);
    }

    /** 整行原始值快照：以“列号 -> 值”存 JSON，之后对账时能核对源数据 */
    private String rawJson(Row row, FormulaEvaluator evaluator, DataFormatter formatter) {
        ObjectNode node = objectMapper.createObjectNode();
        for (Cell cell : row) {
            if (cell == null) {
                continue;
            }
            String key = String.valueOf(cell.getColumnIndex());
            if (cell.getCellType() == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell) && cell.getDateCellValue() != null) {
                    node.put(key, cell.getDateCellValue().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDate().toString());
                } else {
                    node.put(key, cell.getNumericCellValue());
                }
            } else if (cell.getCellType() == CellType.FORMULA) {
                node.put(key, "=" + cell.getCellFormula());
            } else {
                String text = cellText(cell, evaluator, formatter);
                if (text != null && !text.isBlank()) {
                    node.put(key, text);
                }
            }
        }
        return node.isEmpty() ? null : node.toString();
    }

    /**
     * 确定币种：优先取该行币种列；没有就按 Sheet 名猜（美元/新币/港币/欧元）；
     * 都猜不到才用接口默认币种。
     */
    private String resolveCurrency(String sheetName,
                                   SheetMapping mapping,
                                   Row row,
                                   FormulaEvaluator evaluator,
                                   DataFormatter formatter,
                                   String defaultCurrencyCode) {
        if (mapping.has(FinanceColumn.CURRENCY)) {
            String value = textAt(row, mapping.column(FinanceColumn.CURRENCY), evaluator, formatter);
            String code = normalizeCurrency(value);
            if (code != null) {
                return code;
            }
        }
        if (sheetName.contains("美元")) {
            return "USD";
        }
        if (sheetName.contains("新币")) {
            return "SGD";
        }
        if (sheetName.contains("港币")) {
            return "HKD";
        }
        if (sheetName.contains("欧元")) {
            return "EUR";
        }
        return defaultCurrencyCode == null ? "CNY" : defaultCurrencyCode;
    }

    String normalizeCurrency(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (String code : new String[]{"CNY", "USD", "HKD", "SGD", "EUR", "JPY", "GBP"}) {
            if (upper.contains(code)) {
                return code;
            }
        }
        if (upper.contains("人民币")) {
            return "CNY";
        }
        if (upper.contains("美元")) {
            return "USD";
        }
        return null;
    }

    private boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    boolean isDateSerialLabel(String label) {
        if (label == null || !label.matches("\\d+(\\.\\d+)?")) {
            return false;
        }
        try {
            double value = Double.parseDouble(label);
            return value > 30000 && value < 100000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 整行都没有有效内容，视为空行直接跳过 */
    private boolean isBlankRow(Row row) {
        for (Cell cell : row) {
            if (cell == null) {
                continue;
            }
            if (cell.getCellType() == CellType.BLANK) {
                continue;
            }
            String text = new DataFormatter(Locale.CHINA).formatCellValue(cell);
            if (text != null && !text.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 抽取结果汇总，供导入服务持久化使用。
     */
    public static class ExtractionResult {
        public String defaultCurrencyCode;
        public final List<FinanceLedgerRecord> records = new ArrayList<>();
        public final Map<String, Integer> sheetRows = new LinkedHashMap<>();
        public final Map<String, String> skippedSheets = new LinkedHashMap<>();
        public int importedSheets;
        public int totalRows;
        public int mappedRows;
        public int skippedRows;
    }
}
