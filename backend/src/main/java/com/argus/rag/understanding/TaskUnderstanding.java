package com.argus.rag.understanding;

import java.util.List;
import java.util.Set;

/**
 * 任务理解结果 —— 第二步的标准化产出（交给第三步"制定方案"消费）。
 *
 * <p><b>方案边界（三段式分工的产物）</b>：大模型产出语义理解（类型/意图/实体/约束/歧义），
 * 代码后置校验保证不越界——权限/数据范围的任何判断已被 {@code UnderstandingPostValidator}
 * 剥离并以可信身份改写为 assumptions；高风险歧义只允许出现在 clarifications（追问），
 * 不允许拍板。</p>
 *
 * <p><b>完成的判定标准（方案第九节）</b>：四个问题都有答案——
 * 用户要的是哪类任务（taskTypes）/一句话意图（intent）/关键实体与约束
 * （entities+constraints）/哪些必须追问哪些可默认（clarifications+assumptions）。</p>
 *
 * @param taskTypes      任务类型集合（可复合）
 * @param intent         一句话意图
 * @param entities       关键实体（对象/范围/动作/时间等名词短语）
 * @param constraints    关键约束（限定条件）
 * @param ambiguities    发现的歧义（用户没说清但影响结果的信息）
 * @param clarifications 必须追问的问题（高风险歧义，模型只能提不能拍板）
 * @param assumptions    已按默认值继续的假设（低风险歧义的默认口径，代码记录）
 */
public record TaskUnderstanding(
        Set<TaskUnderstandingType> taskTypes,
        String intent,
        List<String> entities,
        List<String> constraints,
        List<String> ambiguities,
        List<String> clarifications,
        List<String> assumptions
) {

    /** 空结果：理解失败/关闭时的 fail-soft 值（调用方按"无理解"继续原流程）。 */
    public static TaskUnderstanding empty() {
        return new TaskUnderstanding(Set.of(), "", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 是否为有效理解结果（intent 非空即视为有效）。 */
    public boolean isEmpty() {
        return intent == null || intent.isBlank();
    }

    /**
     * 转成注入 Agent 系统指令的文本块（XML 标签包裹，与助手分层 Prompt 架构一致）。
     * <p>这里是"内存上下文"，允许携带语义内容；审计落库仍不记原文（第一步红线）。</p>
     */
    public String asInstructionBlock() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<task_understanding>").append(System.lineSeparator());
        sb.append("任务类型：").append(
                taskTypes == null || taskTypes.isEmpty() ? "-" :
                        String.join("、", taskTypes.stream().map(TaskUnderstandingType::displayName).toList())
        ).append(System.lineSeparator());
        sb.append("意图：").append(intent).append(System.lineSeparator());
        appendList(sb, "关键实体", entities);
        appendList(sb, "关键约束", constraints);
        appendList(sb, "未决歧义", ambiguities);
        if (clarifications != null && !clarifications.isEmpty()) {
            appendList(sb, "必须先澄清的问题（证据不足时先向用户提问，不要臆测）", clarifications);
        }
        if (assumptions != null && !assumptions.isEmpty()) {
            appendList(sb, "已确认的默认口径（可直接采用，无需再问）", assumptions);
        }
        sb.append("</task_understanding>").append(System.lineSeparator());
        sb.append("以上是系统对本轮任务的理解结果：权限与数据范围永远以系统可信身份为准；")
                .append("若必须澄清的问题未解决，先向用户追问，不要猜测。");
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(title).append("：").append(String.join("；", items)).append(System.lineSeparator());
    }
}
