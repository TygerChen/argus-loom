package com.argus.rag.finance.report;

import com.argus.rag.finance.report.model.FundAccountResult;
import com.argus.rag.finance.report.model.FundDailyResult;
import com.argus.rag.finance.report.model.FundReportConfig;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资金报表扫描器口径锁 —— 与 fund-daily-report 技能 gen_fund_daily.calc_account
 * 对齐的核心行为：双切片取数、净额口径、转账/理财剔除、结转回退、占位行防御。
 */
class FundWorkbookScannerTest {

    @TempDir
    Path tempDir;

    private final FundWorkbookScanner scanner = new FundWorkbookScanner();

    private static final LocalDate BENCH = LocalDate.of(2026, 11, 6);

    /** 建一个标准布局 Sheet：表头 + 期初行 + 指定日流水行。 */
    private Sheet buildSheet(XSSFWorkbook wb, String name, String[][] rows) {
        Sheet sheet = wb.createSheet(name);
        String[] header = {"交易日期", "摘要", "借方", "贷方", "余额", "费用性质", "定期余额", "定期购买", "定期赎回"};
        Row head = sheet.createRow(0);
        for (int i = 0; i < header.length; i++) {
            head.createCell(i).setCellValue(header[i]);
        }
        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) {
                if (rows[r][c] != null) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
        }
        return sheet;
    }

    private FundAccountResult calc(Sheet sheet) {
        return scanner.calcAccount(sheet, scanner.detectLayout(sheet), BENCH, "test.xlsx", "test", new FundReportConfig());
    }

    @Test
    void layoutDetectsStandardHeader() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "北京银行0001", new String[][]{{}});
        FundWorkbookScanner.SheetLayout layout = scanner.detectLayout(sheet);
        assertEquals(0, layout.date());
        assertEquals(2, layout.debit());
        assertEquals(3, layout.credit());
        assertEquals(4, layout.balance());
        assertEquals(5, layout.nature());
    }

    @Test
    void basicIncomePayoutAndPrev() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000"},
                {"2026-11-05", "收货款", "500", null, "1500"},
                {"2026-11-06", "付货款", null, "300", "1200"},
                {"2026-11-06", "收货款", "200", null, "1400"},
        });
        FundAccountResult r = calc(sheet);
        // 上日余额 = 基准日首行（付货款行）紧邻之上那行的余额 = 1500，非期初 1000
        assertEquals(new BigDecimal("1500.00"), r.getPrev());
        assertEquals(new BigDecimal("200.00"), r.getIncome());
        assertEquals(new BigDecimal("300.00"), r.getPayout());
        assertEquals(new BigDecimal("1400.00"), r.getBalance());
        assertTrue(r.isHasData());
        assertFalse(r.isCarriedForward());
    }

    @Test
    void transferExcludedFromNetIncome() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000"},
                {"2026-11-06", "收货款", "500", null, "1500"},
                {"2026-11-06", "转款", "800", null, "2300", "转款"},
        });
        FundAccountResult r = calc(sheet);
        assertEquals(new BigDecimal("500.00"), r.getIncome());
        assertEquals(new BigDecimal("800.00"), r.getTransferIncome());
        assertEquals(BigDecimal.ZERO.compareTo(r.getPayout()), 0);
    }

    @Test
    void financeBucketDirectionMatchesSkill() {
        // 费用性质=理财：借方（定赎/到期）→ financePayout；贷方（买入）→ financeIncome
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000"},
                {"2026-11-06", "理财", null, "600", "400", "理财"},
                {"2026-11-06", "收货款", "200", null, "600"},
        });
        FundAccountResult r = calc(sheet);
        assertEquals(new BigDecimal("600.00"), r.getFinanceIncome());
        assertEquals(new BigDecimal("200.00"), r.getIncome());
        // 余额列以表格末笔基准日行为准（600），理财行已从收入净额剔除
        assertEquals(new BigDecimal("600.00"), r.getBalance());
    }

    @Test
    void fixedDepositFallbackWithoutNatureText() {
        // 费用性质为空但「定期购买」非空 → 理财本金流兜底支路
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000", null, null, "500", null},
                {"2026-11-06", "买理财", "500", null, "500", null, "500", "500", null},
        });
        FundAccountResult r = calc(sheet);
        assertEquals(new BigDecimal("500.00"), r.getFinancePayout());
        assertEquals(new BigDecimal("500.00"), r.getFixedDepositBalance());
    }

    @Test
    void carriedForwardWhenNoBenchmarkRows() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000"},
                {"2026-11-04", "收货款", "300", null, "1300"},
        });
        FundAccountResult r = calc(sheet);
        assertTrue(r.isCarriedForward());
        assertTrue(r.isHasData());
        assertEquals(new BigDecimal("1300.00"), r.getPrev());
        assertEquals(new BigDecimal("1300.00"), r.getBalance());
        assertEquals(BigDecimal.ZERO.compareTo(r.getIncome()), 0);
        assertEquals(BigDecimal.ZERO.compareTo(r.getPayout()), 0);
    }

    @Test
    void placeholderRowWithZeroBalanceIgnored() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000"},
                {"2026-11-06", "收货款", "200", null, "1200"},
                {"2026-11-06", null, null, null, "0"},
        });
        FundAccountResult r = calc(sheet);
        assertEquals(new BigDecimal("1200.00"), r.getBalance());
    }

    @Test
    void compactTextDateParsed() {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = buildSheet(wb, "户A", new String[][]{
                {null, "期初", null, null, "1000"},
                {"20261106", "收货款", "200", null, "1200"},
        });
        FundAccountResult r = calc(sheet);
        assertEquals(new BigDecimal("200.00"), r.getIncome());
    }

    @Test
    void companyNameParsingStripsYearAndSuffix() {
        FundReportConfig config = new FundReportConfig();
        assertEquals("北京公司", scanner.parseCompanyName("2026年北京公司资金报表.xlsx", config));
        assertEquals("广州领上源公司", scanner.parseCompanyName("广州领上源公司2026.xls", config));
        assertEquals("新加坡公司", scanner.parseCompanyName("新加坡公司 2026.xls", config));
    }

    @Test
    void currencyDetectionBySheetNameAndOverride() {
        FundReportConfig config = new FundReportConfig();
        config.getCurrencyOverrides().put("a.xlsx::中信华泰证券", "USD");
        assertEquals("USD", scanner.resolveCurrency("a.xlsx", "美元账户", config));
        assertEquals("USD", scanner.resolveCurrency("a.xlsx", "中信华泰证券", config));
        assertEquals("RMB", scanner.resolveCurrency("a.xlsx", "北京银行0001", config));
    }

    @Test
    void scanAggregatesGroupsTotalsAndIdentity() throws Exception {
        Path f1 = tempDir.resolve("北京公司2026.xlsx");
        Path f2 = tempDir.resolve("广州公司2026.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(f1)) {
            buildSheet(wb, "北京银行0001", new String[][]{
                    {null, "期初", null, null, "1000"},
                    {"2026-11-06", "收货款", "500", null, "1500"},
            });
            wb.write(out);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(f2)) {
            buildSheet(wb, "广州银行0002", new String[][]{
                    {null, "期初", null, null, "2000"},
                    {"2026-11-06", "付货款", null, "800", "1200"},
            });
            wb.write(out);
        }
        FundDailyResult result = scanner.scan(List.of(f1, f2), BENCH, new FundReportConfig());
        assertEquals(2, result.getGroups().size());
        assertEquals(new BigDecimal("3000.00"), result.getTotalPrev());
        assertEquals(new BigDecimal("500.00"), result.getTotalIncome());
        assertEquals(new BigDecimal("800.00"), result.getTotalPayout());
        assertEquals(new BigDecimal("2700.00"), result.getTotalBalance());
        assertTrue(result.isBalanceCheckOk());
        assertEquals("北京公司", result.getGroups().get(0).getCompany());
        assertEquals("广州公司", result.getGroups().get(1).getCompany());
    }

    @Test
    void fxRateConversionIntoRmbTotal() throws Exception {
        Path f = tempDir.resolve("香港子公司2026.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(f)) {
            buildSheet(wb, "美元账户", new String[][]{
                    {null, "期初", null, null, "1000"},
                    {"2026-11-06", "收款", "100", null, "1100"},
            });
            wb.write(out);
        }
        FundReportConfig config = new FundReportConfig();
        config.getFxRates().put("USD", 7.2);
        FundDailyResult result = scanner.scan(List.of(f), BENCH, config);
        assertEquals("USD", result.getGroups().get(0).getAccounts().get(0).getCurrency());
        assertTrue(result.getGroups().get(0).getAccounts().get(0).isConverted());
        assertEquals(new BigDecimal("7200.00"), result.getTotalPrev());
        assertEquals(new BigDecimal("7920.00"), result.getTotalBalance());
    }

    @Test
    void excludedFilePatternKeepsStaleCopiesOutOfTotals() throws Exception {
        Path f1 = tempDir.resolve("北京公司2026.xlsx");
        Path stale = tempDir.resolve("北京公司2026 - 副本.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(f1)) {
            buildSheet(wb, "户A", new String[][]{
                    {null, "期初", null, null, "1000"},
                    {"2026-11-06", "收货款", "500", null, "1500"},
            });
            wb.write(out);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(stale)) {
            buildSheet(wb, "户A", new String[][]{
                    {null, "期初", null, null, "999999"},
            });
            wb.write(out);
        }
        FundReportConfig config = new FundReportConfig();
        config.getExcludeFiles().add("*副本*");
        FundDailyResult result = scanner.scan(List.of(f1, stale), BENCH, config);
        assertEquals(1, result.getGroups().size());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("副本")));
    }
}
