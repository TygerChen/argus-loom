package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.mapper.FinanceLedgerRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 财务分析与异常提醒（任务 11）。
 *
 * <p>设计原则：每个指标都返回可下钻的定位字段（批次/文档/Sheet/行），
 * 异常结果带规则码、阈值与命中原因，不直接作为会计结论；
 * AI 问答上下文只放聚合数字与定位信息，不传原始明细行。</p>
 *
 * <p>第一版基于内存聚合（finance_ledger_record 全量加载当前批次范围），
 * 500 人规模月度流水（数万行）完全够用；数据量增长后可平滑切换到
 * GROUP BY SQL（接口不变）。</p>
 */
@Service
public class FinanceAnalysisService {

    private final FinanceLedgerRecordMapper ledgerMapper;
    private final AnalysisThresholds thresholds;

    public FinanceAnalysisService(FinanceLedgerRecordMapper ledgerMapper, AnalysisThresholds thresholds) {
        this.ledgerMapper = ledgerMapper;
        this.thresholds = thresholds;
    }

    /** 异常检测阈值（可配置，页面展示规则时引用）。 */
    public record AnalysisThresholds(
            /** 单笔支出金额超过该值记 LARGE_AMOUNT。 */
            BigDecimal largeAmountLimit,
            /** 同一往来单位在 {@value #REPEAT_WINDOW_DAYS} 天内付款笔数达到该值记 REPEAT_PAYMENT。 */
            int repeatPaymentCount) {

        /** 重复付款检测的滑动窗口长度（天）。 */
        public static final int REPEAT_WINDOW_DAYS = 7;

        public AnalysisThresholds(double largeAmountLimit, int repeatPaymentWindowDays) {
            this(BigDecimal.valueOf(largeAmountLimit), repeatPaymentWindowDays);
        }
    }

    /**
     * 期间汇总：本期收入/支出/净额 + 上期环比 + 可下钻批次列表。
     */
    public Map<String, Object> periodSummary(LocalDate from, LocalDate to) {
        List<FinanceLedgerRecord> current = loadRecords(from, to);
        List<FinanceLedgerRecord> previous = loadRecords(
                from.minusDays(daysBetween(from, to) + 1), from.minusDays(1));

        BigDecimal income = sum(current, true);
        BigDecimal expense = sum(current, false);
        BigDecimal prevIncome = sum(previous, true);
        BigDecimal prevExpense = sum(previous, false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodFrom", from);
        result.put("periodTo", to);
        result.put("incomeTotal", income);
        result.put("expenseTotal", expense);
        result.put("netTotal", income.subtract(expense));
        result.put("incomeChangeRatio", changeRatio(income, prevIncome));
        result.put("expenseChangeRatio", changeRatio(expense, prevExpense));
        result.put("batchIds", distinctBatchIds(current));
        return result;
    }

    /**
     * 维度汇总：按 counterparty/department/category 聚合金额，
     * 每条都带可回溯的批次与样例记录定位。
     *
     * @param dimension 维度字段名（counterparty / department / category）
     * @param topN      只返回金额前 N 的分组
     */
    public List<Map<String, Object>> dimensionSummary(String dimension, int topN) {
        String column = switch (dimension == null ? "" : dimension) {
            case "department" -> "department";
            case "category" -> "category";
            default -> "counterparty";
        };
        List<FinanceLedgerRecord> records = loadRecords(null, null);
        Map<String, List<FinanceLedgerRecord>> grouped = new LinkedHashMap<>();
        for (FinanceLedgerRecord r : records) {
            String key = switch (column) {
                case "department" -> r.getDepartment();
                case "category" -> r.getCategory();
                default -> r.getCounterparty();
            };
            if (key == null || key.isBlank()) {
                key = "(未填写)";
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        return grouped.entrySet().stream()
                .map(e -> {
                    BigDecimal income = sum(e.getValue(), true);
                    BigDecimal expense = sum(e.getValue(), false);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("dimension", column);
                    item.put("dimensionValue", e.getKey());
                    item.put("recordCount", e.getValue().size());
                    item.put("incomeTotal", income);
                    item.put("expenseTotal", expense);
                    item.put("netTotal", income.subtract(expense));
                    item.put("batchIds", distinctBatchIds(e.getValue()));
                    item.put("sampleRecordIds", e.getValue().stream()
                            .map(FinanceLedgerRecord::getId)
                            .filter(java.util.Objects::nonNull)
                            .limit(10)
                            .toList());
                    return item;
                })
                .sorted((a, b) -> ((BigDecimal) b.get("expenseTotal"))
                        .add((BigDecimal) b.get("incomeTotal"))
                        .compareTo(((BigDecimal) a.get("expenseTotal")).add((BigDecimal) a.get("incomeTotal"))))
                .limit(Math.max(1, topN))
                .toList();
    }

    /**
     * 异常提醒：大额支出 + 同供应商短期重复付款。
     * 每条异常都带规则码、阈值、命中原因与原始记录定位，不作为会计结论。
     */
    public List<Map<String, Object>> detectAnomalies() {
        List<FinanceLedgerRecord> records = loadRecords(null, null);
        List<Map<String, Object>> anomalies = new ArrayList<>();

        // 规则 1：单笔大额支出
        List<FinanceLedgerRecord> large = records.stream()
                .filter(r -> r.getExpense() != null
                        && r.getExpense().compareTo(thresholds.largeAmountLimit()) > 0)
                .toList();
        if (!large.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleCode", "LARGE_AMOUNT");
            item.put("ruleName", "单笔大额支出");
            item.put("threshold", thresholds.largeAmountLimit());
            item.put("count", large.size());
            item.put("totalAmount", sum(large, false));
            item.put("recordBatchIds", distinctBatchIds(large));
            item.put("recordIds", large.stream().map(FinanceLedgerRecord::getId).toList());
            item.put("reason", "单笔支出超过阈值 " + thresholds.largeAmountLimit().toPlainString()
                    + "，最大一笔 " + large.stream().map(FinanceLedgerRecord::getExpense).max(BigDecimal::compareTo).orElse(null));
            anomalies.add(item);
        }

        // 规则 2：同一往来单位短期重复付款
        Map<String, List<FinanceLedgerRecord>> bySupplier = new LinkedHashMap<>();
        for (FinanceLedgerRecord r : records) {
            if (r.getExpense() != null && r.getCounterparty() != null && !r.getCounterparty().isBlank()) {
                bySupplier.computeIfAbsent(r.getCounterparty(), k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<String, List<FinanceLedgerRecord>> e : bySupplier.entrySet()) {
            List<FinanceLedgerRecord> paid = e.getValue().stream()
                    .filter(r -> r.getBusinessDate() != null)
                    .sorted((a, b) -> a.getBusinessDate().compareTo(b.getBusinessDate()))
                    .toList();
            List<FinanceLedgerRecord> hits = new ArrayList<>();
            for (int i = thresholds.repeatPaymentCount() - 1; i < paid.size(); i++) {
                LocalDate windowEnd = paid.get(i).getBusinessDate();
                LocalDate windowStart = windowEnd.minusDays(AnalysisThresholds.REPEAT_WINDOW_DAYS - 1);
                long inWindow = paid.stream()
                        .filter(r -> !r.getBusinessDate().isBefore(windowStart)
                                && !r.getBusinessDate().isAfter(windowEnd))
                        .count();
                if (inWindow >= thresholds.repeatPaymentCount()) {
                    hits.add(paid.get(i));
                }
            }
            if (!hits.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleCode", "REPEAT_PAYMENT");
                item.put("ruleName", "同供应商短期重复付款");
                item.put("threshold", thresholds.repeatPaymentCount());
                item.put("count", hits.size());
                item.put("totalAmount", sum(hits, false));
                item.put("recordBatchIds", distinctBatchIds(hits));
                item.put("recordIds", hits.stream().map(FinanceLedgerRecord::getId).toList());
                item.put("reason", "供应商「" + e.getKey() + "」在 "
                        + AnalysisThresholds.REPEAT_WINDOW_DAYS + " 天内付款 " + hits.size() + " 笔");
                anomalies.add(item);
            }
        }
        return anomalies;
    }

    /**
     * AI 财务问答上下文：只包含聚合数字、期间、定位信息与问题原文；
     * 原始明细行不出服务层，AI 只在授权汇总上工作（任务 11 约束 3）。
     */
    public Map<String, Object> aiQuestionContext(LocalDate from, LocalDate to, String question) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("periodFrom", from);
        context.put("periodTo", to);
        context.put("question", question);
        context.put("summary", periodSummary(from, to));
        context.put("topCounterparties", dimensionSummary("counterparty", 5));
        context.put("anomalyCount", detectAnomalies().size());
        return context;
    }

    /** 按期间加载当前版本批次的流水（batchId 排序保证聚合稳定）。 */
    private List<FinanceLedgerRecord> loadRecords(LocalDate from, LocalDate to) {
        QueryWrapper<FinanceLedgerRecord> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("batch_id", "sheet_index", "excel_row");
        // 第一版在内存过滤日期（月度流水数万行内可接受）；数据量增长后把
        // ge/le 条件下推 SQL 即可，聚合接口不变。
        return ledgerMapper.selectList(wrapper).stream()
                .filter(r -> r.getBusinessDate() != null)
                .filter(r -> from == null || !r.getBusinessDate().isBefore(from))
                .filter(r -> to == null || !r.getBusinessDate().isAfter(to))
                .toList();
    }

    private static BigDecimal sum(List<FinanceLedgerRecord> records, boolean income) {
        return records.stream()
                .map(r -> income ? r.getIncome() : r.getExpense())
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Set<Long> distinctBatchIds(List<FinanceLedgerRecord> records) {
        return records.stream()
                .map(FinanceLedgerRecord::getBatchId)
                .filter(java.util.Objects::nonNull)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    /** 环比变化率：(本期-上期)/上期，上期为 0 时返回 null（避免除零误导）。 */
    private static BigDecimal changeRatio(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(previous)
                .divide(previous.abs(), 2, RoundingMode.HALF_UP);
    }

    private static long daysBetween(LocalDate start, LocalDate end) {
        return java.time.temporal.ChronoUnit.DAYS.between(start, end);
    }
}
