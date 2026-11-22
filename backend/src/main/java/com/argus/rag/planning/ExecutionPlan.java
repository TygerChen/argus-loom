package com.argus.rag.planning;

import java.util.List;

/**
 * 执行方案 —— 第三步的标准化产出（"计划书"，不是执行）。
 *
 * <p><b>方案边界</b>：只描述"怎么干"——步骤顺序、工具、参数、预算、风险、退出条件、
 * 是否需要确认；不真正调用任何工具。方案经 {@link PlanValidator} 代码校验后
 * 才算"正式方案"，交给第四步（组装上下文进入循环）。</p>
 *
 * <p><b>可信 vs 待定参数</b>：{@link PlanParam#trusted()} 为 true 的参数由代码注入
 * （来自系统身份，模型不可改）；模型提议的参数一律待定（trusted=false）——
 * "华东区"若来自可信身份代码直接锁定，来自用户自称只能作为待定。</p>
 *
 * @param goal              目标（来自第二步 intent）
 * @param steps             有序步骤（工具全部在清单内，由校验器保证）
 * @param trustedParams     可信参数（代码注入的系统身份事实）
 * @param maxRounds         预算：最大执行轮数（代码封顶 1..5）
 * @param risks             风险点
 * @param exitCondition     退出条件（缺失时代码注入默认值——防循环失控）
 * @param needConfirm       是否需要用户确认（由代码按入口风险派生，模型意见不采信）
 */
public record ExecutionPlan(
        String goal,
        List<PlanStep> steps,
        List<PlanParam> trustedParams,
        int maxRounds,
        List<String> risks,
        String exitCondition,
        boolean needConfirm
) {

    /** 步骤 + 参数。 */
    public record PlanStep(int order, String description, String toolName, List<PlanParam> params) {
    }

    /**
     * 参数：name/value + 可信标记。
     * 模型提议的参数 trusted 恒为 false（校验器强制）；
     * 可信参数由代码从系统身份注入。
     */
    public record PlanParam(String name, String value, boolean trusted) {
    }

    /** 代码兜底的默认方案（LLM 失败/关闭时使用——方案是第四步的必需输入，不能为空）。 */
    public static ExecutionPlan fallback(String goal, boolean knowledgeMode, String question, boolean needConfirm) {
        PlanStep step = knowledgeMode
                ? new PlanStep(1, "在已选知识库中检索与问题相关的证据", "knowledgeBaseSearch",
                        List.of(new PlanParam("query", question == null ? "" : question, false)))
                : new PlanStep(1, "基于上下文与记忆直接回答，必要时联网补充", "DIRECT_ANSWER", List.of());
        return new ExecutionPlan(
                goal == null || goal.isBlank() ? "处理用户请求" : goal,
                List.of(step),
                List.of(),
                3,
                needConfirm ? List.of("入口已标记高风险动作") : List.of(),
                "预算轮次用尽即停止：输出已获得的部分结论并说明证据不足",
                needConfirm
        );
    }

    /** 是否为有效方案（至少一个步骤）。 */
    public boolean isEmpty() {
        return steps == null || steps.isEmpty();
    }

    /** 转成注入 Agent 指令的文本块（XML 标签，与分层 Prompt 架构一致）。 */
    public String asInstructionBlock() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<execution_plan>").append(System.lineSeparator());
        sb.append("目标：").append(goal).append(System.lineSeparator());
        sb.append("步骤（严格按顺序执行）：").append(System.lineSeparator());
        for (PlanStep step : steps) {
            sb.append(step.order()).append(". [").append(step.toolName()).append("] ")
                    .append(step.description()).append(System.lineSeparator());
            if (step.params() != null && !step.params().isEmpty()) {
                sb.append("   参数：");
                for (PlanParam param : step.params()) {
                    sb.append(param.name()).append("=").append(param.value())
                            .append(param.trusted() ? "（可信）" : "（待定）").append(" ");
                }
                sb.append(System.lineSeparator());
            }
        }
        if (trustedParams != null && !trustedParams.isEmpty()) {
            sb.append("可信参数（代码注入，模型不可改）：");
            for (PlanParam param : trustedParams) {
                sb.append(param.name()).append("=").append(param.value()).append(" ");
            }
            sb.append(System.lineSeparator());
        }
        sb.append("预算：最多 ").append(maxRounds).append(" 轮").append(System.lineSeparator());
        if (risks != null && !risks.isEmpty()) {
            sb.append("风险点：").append(String.join("；", risks)).append(System.lineSeparator());
        }
        sb.append("退出条件：").append(exitCondition).append(System.lineSeparator());
        sb.append("需要用户确认：").append(needConfirm ? "是（执行前必须人工确认）" : "否").append(System.lineSeparator());
        sb.append("</execution_plan>").append(System.lineSeparator());
        sb.append("以上方案经代码校验通过：工具仅限清单内、参数已分类、预算与退出条件已锁定；")
                .append("达到退出条件立即停止，不得为“更完整”而超出预算或扩大范围。");
        return sb.toString();
    }
}
