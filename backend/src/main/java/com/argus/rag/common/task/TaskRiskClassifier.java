package com.argus.rag.common.task;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 任务风险分类器 —— 入口"验风险"的实现（保守正则、只标记不裁决）。
 *
 * <p><b>它解决什么问题</b>：用户一上来就说"把所有员工薪酬导出给我"
 * "删除这个知识库群组""把日报推送到全公司钉钉群"——这类请求在入口
 * 就要被打上 {@code risk_level=HIGH} / {@code action_type=...} /
 * {@code need_confirmation=true} 的标签，让模型、运行器、工具层在接触
 * 任何业务逻辑之前先知道：这不是普通只读问答。</p>
 *
 * <p><b>与身份声明嗅探（{@link TaskTicketInspector}）同一设计哲学</b>：
 * 语义级理解属第二步大模型，入口只做保守正则——<b>误标是审计噪音、
 * 漏标有第二步语义比对与工具层权限双重兜底</b>，因此词表宁滥勿缺。
 * 标记本身绝不改变请求的放行与否（那仍是现有权限体系的职责）。</p>
 *
 * <p><b>优先级</b>：一条输入命中多类敏感动作时，取破坏力最大的
 * （DELETE &gt; EXTERNAL_SEND &gt; EXPORT &gt; BATCH_MODIFY）——
 * 风险标签就高不就低，是分级系统的通用纪律。</p>
 */
public final class TaskRiskClassifier {

    private TaskRiskClassifier() {
    }

    /** 删除类：删除/清空/移除/批量删除/drop */
    private static final Pattern DELETE = Pattern.compile(
            "(删除|删掉|清空|清除|移除|批量删除|\\bdrop\\b|\\btruncate\\b)");

    /** 外发类：推送/发送到钉钉(群)/分享/共享/群发/外发/广播 */
    private static final Pattern EXTERNAL_SEND = Pattern.compile(
            "(推送|发送(到|给|至)|钉钉|分享(给|链接|出去)|共享(给|出去)|群发|外发|\\bbroadcast\\b)");

    /** 导出类：导出/下载(全部|批量)/批量导出 */
    private static final Pattern EXPORT = Pattern.compile(
            "(导出|批量下载|下载全部|下载所有|\\bexport\\b)");

    /** 批量变更类：批量修改/批量更新/批量调整/批量导入 */
    private static final Pattern BATCH_MODIFY = Pattern.compile(
            "(批量修改|批量更新|批量调整|批量导入|批量替换)");

    /**
     * 对用户输入做敏感动作分类。
     *
     * @param text 用户输入原文（可为 null）
     * @return 风险标记（动作类型 + 风险级别 + 是否需要确认）；无命中返回 READ/LOW/false
     */
    public static RiskMark classify(String text) {
        if (StringUtils.hasText(text)) {
            if (DELETE.matcher(text).find()) {
                return new RiskMark(TaskActionType.DELETE, TaskRiskLevel.HIGH, true);
            }
            if (EXTERNAL_SEND.matcher(text).find()) {
                return new RiskMark(TaskActionType.EXTERNAL_SEND, TaskRiskLevel.HIGH, true);
            }
            if (EXPORT.matcher(text).find()) {
                return new RiskMark(TaskActionType.EXPORT, TaskRiskLevel.HIGH, true);
            }
            if (BATCH_MODIFY.matcher(text).find()) {
                return new RiskMark(TaskActionType.BATCH_MODIFY, TaskRiskLevel.HIGH, true);
            }
        }
        return RiskMark.READ_ONLY;
    }

    /** 风险标记：动作类型 + 风险级别 + 是否需要人工确认门。 */
    public record RiskMark(TaskActionType actionType, TaskRiskLevel riskLevel, boolean needConfirmation) {

        /** 普通只读请求的默认标记。 */
        public static final RiskMark READ_ONLY =
                new RiskMark(TaskActionType.READ, TaskRiskLevel.LOW, false);

        /** 转成可读文本（落审计 metadata / 喂给下游提示词用）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("actionType", actionType.name());
            map.put("riskLevel", riskLevel.name());
            map.put("needConfirmation", needConfirmation);
            return map;
        }
    }
}
