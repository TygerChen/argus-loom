package com.argus.rag.finance.report;

import com.argus.rag.finance.report.model.FundAccountResult;
import com.argus.rag.finance.report.model.FundDailyResult;
import com.argus.rag.finance.report.model.FundGroupResult;
import com.argus.rag.finance.report.model.FundReportConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 金标准比对：用 fund-daily-report 技能的样例 Excel 锁定 Java 移植口径。
 *
 * <p>基准值由技能 gen_fund_daily.py（2026-11-07 实测）在同一批样例上产出：
 * 基准日 2025-12-22，汇率表美元汇率 7.0965。样例目录不存在时本测试跳过，
 * 不影响无该仓库的机器。</p>
 */
class FundScannerSampleGoldenTest {

    private static final Path SAMPLES_DIR = Path.of("D:/DevTools/ChatGPT/fund-daily-report/samples");
    private static final LocalDate BENCH = LocalDate.of(2025, 12, 22);

    private static FundDailyResult result;

    @BeforeAll
    static void scanSamples() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES_DIR), "样例目录不存在，跳过金标准比对");
        List<Path> files;
        try (var stream = Files.list(SAMPLES_DIR)) {
            files = stream.filter(p -> p.toString().endsWith(".xlsx") || p.toString().endsWith(".xls")).toList();
        }
        FundReportConfig config = new FundReportConfig();
        config.getFxRates().put("USD", 7.0965);
        result = new FundWorkbookScanner().scan(files, BENCH, config);
    }

    @Test
    void grandTotalsMatchPythonSkill() {
        assertEquals(new BigDecimal("10680939.50"), result.getTotalPrev(), "上日余额");
        assertEquals(new BigDecimal("800000.00"), result.getTotalIncome(), "本日收入");
        assertEquals(new BigDecimal("0.00"), result.getTotalPayout(), "本日支出");
        assertEquals(new BigDecimal("11480939.50"), result.getTotalBalance(), "本日结余");
        assertTrue(result.isBalanceCheckOk(), "合计勾稽应通过");
    }

    @Test
    void groupSubtotalsMatchPythonSkill() {
        Map<String, FundGroupResult> byCompany = result.getGroups().stream()
                .collect(Collectors.toMap(FundGroupResult::getCompany, Function.identity()));
        assertEquals(3, byCompany.size());
        assertGroup(byCompany, "样例-上海公司", "2650000.00", "0.00", "0.00", "2650000.00");
        assertGroup(byCompany, "样例-北京公司", "6630939.50", "800000.00", "0.00", "7430939.50");
        assertGroup(byCompany, "样例-广州公司", "1400000.00", "0.00", "0.00", "1400000.00");
    }

    @Test
    void usdAccountConvertedWithSampleRate() {
        Map<String, FundAccountResult> byName = result.getGroups().stream()
                .flatMap(g -> g.getAccounts().stream())
                .collect(Collectors.toMap(FundAccountResult::getName, Function.identity(), (a, b) -> a));
        FundAccountResult usd = byName.get("交行美元户");
        assertEquals("USD", usd.getCurrency());
        assertTrue(usd.isConverted());
        assertEquals(new BigDecimal("103000.00"), usd.getRegularPrev(), "美元原币上日余额");
        assertEquals(new BigDecimal("730939.50"), usd.getPrev(), "折算后上日余额（103000 × 7.0965）");
    }

    private void assertGroup(Map<String, FundGroupResult> byCompany, String company,
                             String prev, String income, String payout, String balance) {
        FundGroupResult group = byCompany.get(company);
        assertTrue(group != null, "缺少公司分组: " + company);
        assertEquals(new BigDecimal(prev), group.getPrev(), company + " 上日余额");
        assertEquals(new BigDecimal(income), group.getIncome(), company + " 本日收入");
        assertEquals(new BigDecimal(payout), group.getPayout(), company + " 本日支出");
        assertEquals(new BigDecimal(balance), group.getBalance(), company + " 本日结余");
    }
}
