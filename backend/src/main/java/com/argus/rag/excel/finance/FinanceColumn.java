package com.argus.rag.excel.finance;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 财务标准字段。与 docs/财务AI日报-数据字典.md 中的标准列保持一致。
 */
public enum FinanceColumn {

    TRANSACTION_DATE("日期", "汇率日期", "交易日期", "记账日期", "交易时间",
            "date", "transaction date", "entry date", "transaction time"),
    SUMMARY("摘要", "摘要说明", "事项", "说明", "交易说明", "用途",
            "summary", "description", "details", "memo"),
    COUNTERPARTY("单位", "往来单位", "对方单位", "单位名称", "客户", "供应商",
            "counterparty", "company", "supplier", "counter party"),
    DEPARTMENT("部门", "责任部门", "department", "cost center"),
    CATEGORY("费用性质", "类别", "费用分类", "category", "expense category"),
    EXPENSE_NAME("费用", "费用项目", "费用名称", "科目", "款项名称", "明细科目",
            "expense name", "expense item", "fee item"),
    MEETING_NOTE("会议费备注"),
    DEBIT("借方", "借方金额", "借方发生额", "debit", "debit amount"),
    CREDIT("贷方", "贷方金额", "贷方发生额", "credit", "credit amount"),
    BALANCE("余额", "结余", "账面余额", "balance", "closing balance", "running balance"),
    FIXED_PURCHASE("定期购买"),
    FIXED_REDEMPTION("定期赎回"),
    FIXED_BALANCE("定期余额"),
    PREVIOUS_BALANCE("上日余额"),
    INCOME("收入", "收入金额", "收入发生额", "income", "income amount"),
    EXPENSE("支出", "支出金额", "支出发生额", "expense amount", "payment"),
    NOTE1("备注1"),
    NOTE2("备注2"),
    NOTE("备注"),
    ROW_NO("序号"),
    CURRENCY("币种"),
    RATE_DATE("汇率日期"),
    RATE_BEGIN("月初汇率"),
    RATE_MONTH("年月");

    private final Set<String> aliases;

    FinanceColumn(String... aliases) {
        this.aliases = Arrays.stream(aliases)
                .map(value -> value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /** 校验某个表头文本是否命中这个标准字段的别名 */
    public boolean matches(String headerText) {
        if (headerText == null || headerText.isBlank()) {
            return false;
        }
        String normalized = headerText.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return aliases.contains(normalized);
    }

    /** 从头文本找到对应的标准字段；找不到返回 null */
    public static FinanceColumn fromHeader(String headerText) {
        for (FinanceColumn column : values()) {
            if (column.matches(headerText)) {
                return column;
            }
        }
        return null;
    }
}
