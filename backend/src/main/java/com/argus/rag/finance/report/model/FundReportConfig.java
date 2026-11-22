package com.argus.rag.finance.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金日报业务规则配置（口径与 fund-daily-report 技能 defaults.json 对齐）。
 * 全部规则由配置驱动，不写死任何一家公司；差异项走用户级 pipeline_json 覆盖。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundReportConfig {

    private String reportTitle = "母子公司资金日报";

    /** 借方记收入（false = 贷方记收入） */
    private boolean debitAsIncome = true;

    /** 费用性质列命中即视为内部转账（从当日收支中剔除，单列展示） */
    private List<String> transferKeywords = new ArrayList<>(List.of("转款"));

    /** 费用性质列命中即视为理财流水（单列展示） */
    private List<String> financeKeywords = new ArrayList<>(List.of("理财"));

    /** 文件名解析公司名时去除的尾缀 */
    private List<String> companyNameSuffixes = new ArrayList<>(
            List.of("资金报表", "资金日报", "报表", "日报", "模板", "信息化"));

    /** 文件名去前缀/后缀年份（2025年北京公司.xls → 北京公司） */
    private boolean companyNameStripYear = true;

    /** 假设流水按日期升序（允许早停；乱序表置 false） */
    private boolean assumeDateAscending = true;

    /** 永久跳过的 Sheet 名（小写匹配） */
    private List<String> skipSheetNames = new ArrayList<>(
            List.of("总经办", "现金（个税返还）", "汇总表"));

    /** 排除的文件名（精确名或 * 通配，抑制陈旧副本重复计入） */
    private List<String> excludeFiles = new ArrayList<>();

    /** 币种识别关键字（小写包含匹配） */
    private Map<String, List<String>> currencyKeywords = defaultCurrencyKeywords();

    /** 币种覆盖："文件名::Sheet名" → 币种码 */
    private Map<String, String> currencyOverrides = new LinkedHashMap<>();

    /** 外币→人民币折算率（阶段一为固定率；月度汇率表后续接入） */
    private Map<String, Double> fxRates = new LinkedHashMap<>();

    private String displayUnit = "万元";

    private static Map<String, List<String>> defaultCurrencyKeywords() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("USD", List.of("美元", "美金", "usd"));
        map.put("HKD", List.of("港币", "hkd"));
        map.put("SGD", List.of("新币", "sgd", "新加坡币"));
        map.put("EUR", List.of("欧元", "eur"));
        return map;
    }
}
