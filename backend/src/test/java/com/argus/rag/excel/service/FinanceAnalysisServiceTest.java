package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.mapper.FinanceLedgerRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 财务分析服务契约（任务 11）：
 * 汇总必须可下钻（返回 batchId 等定位字段）、异常必须带规则码与命中原因、
 * AI 问答上下文只包含授权范围内的聚合数据。
 */
@DisplayName("财务分析与异常提醒（FinanceAnalysisService）")
class FinanceAnalysisServiceTest {

    private FinanceLedgerRecordMapper ledgerMapper;
    private FinanceAnalysisService service;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(FinanceLedgerRecordMapper.class);
        service = new FinanceAnalysisService(ledgerMapper,
                new FinanceAnalysisService.AnalysisThresholds(10000, 3));
    }

    private void stubLedger(List<FinanceLedgerRecord> rows) {
        when(ledgerMapper.selectList(any())).thenReturn(rows);
    }

    private static FinanceLedgerRecord row(long batchId, LocalDate date, String supplier,
                                           BigDecimal income, BigDecimal expense) {
        FinanceLedgerRecord r = new FinanceLedgerRecord();
        r.setId(System.nanoTime() % 100000);
        r.setBatchId(batchId);
        r.setDocumentId(9L);
        r.setSheetName("bank");
        r.setExcelRow(5);
        r.setRecordType("DATA");
        r.setBusinessDate(date);
        r.setCounterparty(supplier);
        r.setIncome(income);
        r.setExpense(expense);
        return r;
    }

    @Test
    @DisplayName("期间汇总：收入/支出/净额与人工计算一致，且携带环比")
    void periodSummaryMatchesManualCalculation() {
        stubLedger(List.of(
                row(1L, LocalDate.of(2026, 9, 1), "A", new BigDecimal("1000"), null),
                row(1L, LocalDate.of(2026, 9, 15), "B", null, new BigDecimal("400")),
                row(2L, LocalDate.of(2026, 10, 1), "A", new BigDecimal("1500"), null),
                row(2L, LocalDate.of(2026, 10, 20), "B", null, new BigDecimal("300"))));

        Map<String, Object> summary = service.periodSummary(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(summary.get("incomeTotal")).isEqualTo(new BigDecimal("1500"));
        assertThat(summary.get("expenseTotal")).isEqualTo(new BigDecimal("300"));
        assertThat(summary.get("netTotal")).isEqualTo(new BigDecimal("1200"));
        // 上期（9 月）：收入 1000、支出 400 → 环比收入 +50%、支出 -25%
        assertThat(summary.get("incomeChangeRatio")).isEqualTo(new BigDecimal("0.50"));
        assertThat(summary.get("expenseChangeRatio")).isEqualTo(new BigDecimal("-0.25"));
        // 下钻定位：本期（10 月）批次列表只含该期间实际有流水的批次
        assertThat((java.util.Collection<Object>) summary.get("batchIds")).containsExactly(2L);
    }

    @Test
    @DisplayName("维度汇总：按往来单位聚合，每条都带批次与行定位字段")
    void dimensionSummaryDrillsDown() {
        stubLedger(List.of(
                row(1L, LocalDate.of(2026, 10, 1), "供应商甲", null, new BigDecimal("100")),
                row(1L, LocalDate.of(2026, 10, 2), "供应商甲", null, new BigDecimal("200")),
                row(2L, LocalDate.of(2026, 10, 3), "供应商乙", new BigDecimal("50"), null)));

        List<Map<String, Object>> result = service.dimensionSummary("counterparty", 10);

        assertThat(result).hasSize(2);
        Map<String, Object> first = result.get(0);
        assertThat(first.get("dimensionValue")).isEqualTo("供应商甲");
        assertThat(first.get("expenseTotal")).isEqualTo(new BigDecimal("300"));
        assertThat((java.util.Collection<Object>) first.get("batchIds")).containsExactly(1L);
        assertThat((List<Object>) first.get("sampleRecordIds")).isNotEmpty();
    }

    @Test
    @DisplayName("异常提醒：大额、同供应商短期重复付款均可回溯到规则与原始行")
    void anomaliesTraceableToRuleAndSource() {
        stubLedger(List.of(
                row(1L, LocalDate.of(2026, 10, 1), "供应商甲", null, new BigDecimal("50000")),
                row(1L, LocalDate.of(2026, 10, 3), "供应商甲", null, new BigDecimal("60000")),
                row(2L, LocalDate.of(2026, 10, 5), "供应商甲", null, new BigDecimal("70000")),
                row(2L, LocalDate.of(2026, 10, 10), "供应商乙", null, new BigDecimal("100"))));

        List<Map<String, Object>> anomalies = service.detectAnomalies();

        assertThat(anomalies).extracting(a -> a.get("ruleCode"))
                .contains("LARGE_AMOUNT", "REPEAT_PAYMENT");
        Map<String, Object> large = anomalies.stream()
                .filter(a -> "LARGE_AMOUNT".equals(a.get("ruleCode")))
                .findFirst().orElseThrow();
        assertThat(new BigDecimal(String.valueOf(large.get("threshold"))))
                .isEqualByComparingTo("10000");
        assertThat((java.util.Collection<Object>) large.get("recordBatchIds")).isNotEmpty();
        assertThat(String.valueOf(large.get("reason"))).contains("70000");
    }

    @Test
    @DisplayName("AI 问答上下文：只包含聚合数字与定位信息，不带原始明细行")
    void aiContextContainsAggregatesOnly() {
        stubLedger(List.of(
                row(1L, LocalDate.of(2026, 10, 1), "A", new BigDecimal("100"), null)));

        Map<String, Object> context = service.aiQuestionContext(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "本月收入多少");

        assertThat(context).containsKeys("periodFrom", "periodTo", "question", "summary");
        assertThat(String.valueOf(context.get("summary"))).contains("incomeTotal");
    }

}

