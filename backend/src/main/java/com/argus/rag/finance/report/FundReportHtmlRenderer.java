package com.argus.rag.finance.report;

import com.argus.rag.finance.report.model.FundAccountResult;
import com.argus.rag.finance.report.model.FundDailyResult;
import com.argus.rag.finance.report.model.FundGroupResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.StringJoiner;

/**
 * 资金日报 HTML 渲染器：产出自包含可归档的单文件看板。
 * 数据即 {@link FundDailyResult}，与钉钉推送内容同源。
 */
@Component
public class FundReportHtmlRenderer {

    /** 万元展示换算（余额类金额以元为单位存储）。 */
    private static final BigDecimal WAN = new BigDecimal("10000");

    public String render(FundDailyResult result) {
        StringBuilder html = new StringBuilder(16 * 1024);
        html.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">")
                .append("<title>").append(esc(result.getReportTitle())).append("</title><style>")
                .append("body{font-family:'Microsoft YaHei',sans-serif;margin:24px;color:#1f2937}")
                .append("h1{font-size:22px}.cards{display:flex;gap:16px;margin:16px 0}")
                .append(".card{flex:1;border:1px solid #e5e7eb;border-radius:8px;padding:16px;text-align:center}")
                .append(".card .label{color:#6b7280;font-size:13px}.card .value{font-size:24px;font-weight:600;margin-top:8px}")
                .append(".neg{color:#dc2626}.pos{color:#16a34a}")
                .append("table{width:100%;border-collapse:collapse;margin:12px 0;font-size:14px}")
                .append("th,td{border:1px solid #e5e7eb;padding:8px;text-align:right}")
                .append("th{background:#f9fafb}td.name{text-align:left}")
                .append(".subtotal td{background:#f3f4f6;font-weight:600}")
                .append(".warn{color:#b45309;font-size:13px;margin:8px 0}")
                .append(".bad{color:#dc2626;font-weight:600}")
                .append("</style></head><body>");
        html.append("<h1>").append(esc(result.getReportTitle())).append(" · ")
                .append(result.getReportDate()).append("</h1>");
        html.append("<div class=\"cards\">");
        card(html, "上日余额", result.getTotalPrev());
        card(html, "本日收入", result.getTotalIncome());
        card(html, "本日支出", result.getTotalPayout());
        card(html, "本日结余", result.getTotalBalance());
        html.append("</div>");
        if (!result.isBalanceCheckOk()) {
            html.append("<div class=\"bad\">⚠ 合计勾稽未通过：差额 ")
                    .append(result.getBalanceCheckDiff()).append(" 元，请检查数据源</div>");
        }
        for (String warning : result.getWarnings()) {
            html.append("<div class=\"warn\">").append(esc(warning)).append("</div>");
        }
        html.append("<table><tr><th>公司 / 账户</th><th>币种</th><th>上日余额</th><th>本日收入</th>")
                .append("<th>本日支出</th><th>转账剔除</th><th>理财剔除</th><th>本日结余</th></tr>");
        for (FundGroupResult group : result.getGroups()) {
            for (FundAccountResult account : group.getAccounts()) {
                html.append("<tr><td class=\"name\">").append(esc(account.getCompany()))
                        .append(" / ").append(esc(account.getName())).append("</td><td>")
                        .append(esc(account.getCurrency())).append("</td>");
                rowCells(html, account);
                html.append("</tr>");
            }
            html.append("<tr class=\"subtotal\"><td class=\"name\">").append(esc(group.getCompany()))
                    .append(" 资金合计</td><td>RMB</td>");
            html.append("<td>").append(wan(group.getPrev())).append("</td><td>").append(wan(group.getIncome()))
                    .append("</td><td>").append(wan(group.getPayout())).append("</td><td>")
                    .append(wan(group.getTransferIncome().add(group.getTransferPayout())))
                    .append("</td><td>").append(wan(group.getFinanceIncome().add(group.getFinancePayout())))
                    .append("</td><td>").append(wan(group.getBalance())).append("</td></tr>");
        }
        html.append("<tr class=\"subtotal\"><td class=\"name\">资金合计</td><td>RMB</td>");
        html.append("<td>").append(wan(result.getTotalPrev())).append("</td><td>").append(wan(result.getTotalIncome()))
                .append("</td><td>").append(wan(result.getTotalPayout())).append("</td><td>—</td><td>—</td><td>")
                .append(wan(result.getTotalBalance())).append("</td></tr>");
        html.append("</table></body></html>");
        return html.toString();
    }

    private void card(StringBuilder html, String label, BigDecimal value) {
        html.append("<div class=\"card\"><div class=\"label\">").append(label)
                .append("</div><div class=\"value").append(valueClass(label, value))
                .append("\">").append(wan(value)).append(" 万</div></div>");
    }

    private String valueClass(String label, BigDecimal value) {
        boolean flow = label.contains("收入") || label.contains("支出");
        if (!flow) {
            return "";
        }
        return value.signum() > 0 ? " pos" : " neg";
    }

    private void rowCells(StringBuilder html, FundAccountResult account) {
        html.append("<td>").append(wan(account.getPrev())).append("</td><td>")
                .append(wan(account.getIncome())).append("</td><td>")
                .append(wan(account.getPayout())).append("</td><td>")
                .append(wan(account.getTransferIncome().add(account.getTransferPayout()))).append("</td><td>")
                .append(wan(account.getFinanceIncome().add(account.getFinancePayout()))).append("</td><td>")
                .append(wan(account.getBalance())).append("</td>");
    }

    private String wan(BigDecimal value) {
        return value == null ? "—" : value.divide(WAN, 2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String esc(String text) {
        if (text == null) {
            return "";
        }
        StringJoiner sj = new StringJoiner("");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> sj.add("&lt;");
                case '>' -> sj.add("&gt;");
                case '&' -> sj.add("&amp;");
                case '"' -> sj.add("&quot;");
                default -> sj.add(String.valueOf(c));
            }
        }
        return sj.toString();
    }
}
