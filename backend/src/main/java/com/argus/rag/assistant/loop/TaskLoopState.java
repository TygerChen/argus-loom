package com.argus.rag.assistant.loop;

import com.argus.rag.planning.ExecutionPlan;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务循环状态 —— 第四步"组装上下文"新增的核心组件（方案八组件之"任务状态与预算"）。
 *
 * <p><b>它解决什么问题</b>：此前助手循环里模型看不到自己的预算与进度——
 * 递归上限只是图引擎的硬闸（10 步），模型不知道"我还剩几轮""已经查到了什么"
 * "什么情况下该停"。本类把方案里的预算变成循环中<b>代码持续维护、每轮注入</b>的
 * 显式状态，配合退出条件让"可终止"从架构描述变成模型每轮可见的纪律。</p>
 *
 * <p><b>归属与生命周期</b>：每次 chat 请求创建一个实例（由 Agent 门面按方案
 * maxRounds 初始化），经 RunnableConfig metadata 传入 BEFORE_MODEL Hook——
 * Hook 即"第四步组装器"：每轮模型调用前登记轮次、摘要工具结果、注入状态块。
 * 请求结束即丢弃（单请求作用域；跨会话持久化属运行器持久化演进项）。</p>
 *
 * <p><b>压缩纪律（方案第七节）</b>：工具结果只留摘要（截断），历史由既有的
 * compact summary 机制压缩；状态块内容恒定小额——预算检查不靠丢弃，
 * 靠"每轮只装一行进度 + 摘要化结果"。</p>
 */
public class TaskLoopState {

    /** 单条工具结果摘要的最大字符数。 */
    private static final int DIGEST_MAX_CHARS = 120;
    /** 默认预算（方案缺失时的兜底，与 PlanValidator 默认一致）。 */
    private static final int DEFAULT_MAX_ROUNDS = 3;

    private final int maxRounds;
    private final String exitCondition;
    /** 方案工具白名单（第三步产出；空集=未限定，按内置注册表校验）。 */
    private final Set<String> planToolNames;
    private final AtomicInteger round = new AtomicInteger(0);
    /** 已执行工具结果摘要（只增不改；压缩由 Hook 层控制展示条数）。 */
    private final List<String> toolDigests = new ArrayList<>();
    private final AtomicInteger recordedResponses = new AtomicInteger(0);
    /** 第五步决策审计记录（cap 12，超出即丢弃最旧的不做——审计价值随轮次衰减）。 */
    private final List<DecisionRecord> decisions = new ArrayList<>();
    private final AtomicInteger outOfPlanDecisions = new AtomicInteger(0);

    private TaskLoopState(int maxRounds, String exitCondition, Set<String> planToolNames) {
        this.maxRounds = Math.max(1, maxRounds);
        this.exitCondition = exitCondition == null || exitCondition.isBlank()
                ? "预算轮次用尽即停止：输出已获得的部分结论并说明证据不足"
                : exitCondition;
        this.planToolNames = planToolNames == null ? Set.of() : Set.copyOf(planToolNames);
    }

    /** 决策审计记录（第五步：模型提议，运行器把关）。 */
    public record DecisionRecord(int round, String action, String toolName, String argsDigest, boolean inWhitelist) {
    }

    /** 从第三步方案初始化（方案缺失时按默认预算、无白名单限定）。 */
    public static TaskLoopState from(ExecutionPlan plan) {
        if (plan == null) {
            return new TaskLoopState(DEFAULT_MAX_ROUNDS, null, Set.of());
        }
        Set<String> tools = new java.util.LinkedHashSet<>();
        if (plan.steps() != null) {
            for (ExecutionPlan.PlanStep step : plan.steps()) {
                if (step != null && step.toolName() != null && !"DIRECT_ANSWER".equalsIgnoreCase(step.toolName())) {
                    tools.add(step.toolName());
                }
            }
        }
        return new TaskLoopState(plan.maxRounds(), plan.exitCondition(), tools);
    }

    /** 默认预算实例（方案缺失路径）。 */
    public static TaskLoopState defaults() {
        return new TaskLoopState(DEFAULT_MAX_ROUNDS, null, Set.of());
    }

    /** 登记一次模型调用（轮次 +1），返回当前轮次号。 */
    public int nextRound() {
        return round.incrementAndGet();
    }

    /**
     * 摘要登记一条工具执行结果（重复调用同一结果由 Hook 侧计数去重）。
     *
     * @param toolName 工具名（不可得时传 "tool"）
     * @param result   工具返回内容（截断为摘要，不存原文——上下文压缩纪律）
     */
    public void recordToolResult(String toolName, String result) {
        if (result == null || result.isBlank()) {
            return;
        }
        String normalized = result.replaceAll("\\s+", " ").trim();
        String digest = "[" + (toolName == null || toolName.isBlank() ? "tool" : toolName) + "] "
                + (normalized.length() <= DIGEST_MAX_CHARS ? normalized : normalized.substring(0, DIGEST_MAX_CHARS) + "…");
        toolDigests.add(digest);
    }

    /** 已登记的工具响应条数（Hook 用于增量摘要，避免重复记录）。 */
    public int recordedResponseCount() {
        return recordedResponses.get();
    }

    public void markResponsesRecorded(int count) {
        recordedResponses.set(count);
    }

    public int currentRound() {
        return round.get();
    }

    public int maxRounds() {
        return maxRounds;
    }

    /**
     * 循环裁决（第七步：继续还是退出，由运行器判断，模型无权决定）。
     *
     * <p>裁决规则（方案第六节）：预算耗尽 → 退出；否则继续（最后一轮
     * 标注"必须给出最终回答"）。退出条件由模型在回答里兑现（answer 后流程自然结束），
     * 预算是运行器的硬边界。</p>
     */
    public Verdict verdict() {
        if (budgetExhausted()) {
            return new Verdict(false, "预算耗尽（" + round.get() + "/" + maxRounds + "）");
        }
        if (remainingRounds() == 1) {
            return new Verdict(true, "最后一轮：必须给出最终回答");
        }
        return new Verdict(true, "剩余 " + remainingRounds() + " 轮");
    }

    /** 裁决结果：继续与否 + 原因（审计与状态块共用）。 */
    public record Verdict(boolean continueLoop, String reason) {
    }

    /**
     * 图递归上限（运行器控制循环的硬边界）：由方案预算派生，不再用固定常量。
     * <p>每轮模型决策约耗 2 个图步（模型节点+工具节点），另留最终回答 2 步；
     * 封顶 {@value #GRAPH_LIMIT_CEILING}（兼防御异常预算）。</p>
     */
    public static final int GRAPH_LIMIT_CEILING = 10;
    private static final int GRAPH_LIMIT_FLOOR = 4;

    public int graphRecursionLimit() {
        return Math.max(GRAPH_LIMIT_FLOOR,
                Math.min(GRAPH_LIMIT_CEILING, maxRounds * 2 + 2));
    }

    public Set<String> planToolNames() {
        return planToolNames;
    }

    /**
     * 登记一条模型决策（第五步审计；只记录不拦截——硬拦截属框架级工具执行层演进项）。
     *
     * @param action     CALL_TOOL / ANSWER
     * @param toolName   工具名（ANSWER 时为 null）
     * @param argsDigest 参数摘要（截断）
     * @param inWhitelist 是否在方案工具清单内
     */
    public void recordDecision(String action, String toolName, String argsDigest, boolean inWhitelist) {
        if (decisions.size() < 12) {
            decisions.add(new DecisionRecord(round.get(), action, toolName,
                    argsDigest == null ? "" : argsDigest, inWhitelist));
        }
        if (!inWhitelist) {
            outOfPlanDecisions.incrementAndGet();
        }
    }

    public List<DecisionRecord> decisions() {
        return List.copyOf(decisions);
    }

    public int outOfPlanDecisionCount() {
        return outOfPlanDecisions.get();
    }

    /** 剩余轮次（当前轮之后还有几次模型调用机会）。 */
    public int remainingRounds() {
        return Math.max(0, maxRounds - round.get());
    }

    /** 预算是否已用尽（本轮就是最后一轮或已超）。 */
    public boolean budgetExhausted() {
        return round.get() >= maxRounds;
    }

    /**
     * 生成注入上下文的任务状态块（XML 标签，与分层 Prompt 架构一致）。
     * <p>压缩时绝不丢失的三样东西都在这里：目标预算、退出条件、已获结果摘要。</p>
     */
    public String asContextBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("<task_loop_state>").append(System.lineSeparator());
        sb.append("当前轮次：").append(round.get()).append(" / 预算 ").append(maxRounds)
                .append("，剩余 ").append(remainingRounds()).append(" 轮").append(System.lineSeparator());
        if (!toolDigests.isEmpty()) {
            sb.append("已执行工具与结果摘要：").append(System.lineSeparator());
            int shown = Math.min(toolDigests.size(), 6);
            for (int i = toolDigests.size() - shown; i < toolDigests.size(); i++) {
                sb.append("- ").append(toolDigests.get(i)).append(System.lineSeparator());
            }
        } else {
            sb.append("已执行工具：无（本轮尚未调用任何工具）").append(System.lineSeparator());
        }
        sb.append("退出条件（达到立即停止）：").append(exitCondition).append(System.lineSeparator());
        Verdict verdict = verdict();
        sb.append("【循环裁决（运行器）】").append(verdict.continueLoop() ? "继续" : "退出")
                .append("——").append(verdict.reason()).append(System.lineSeparator());
        if (outOfPlanDecisions.get() > 0) {
            sb.append("【越界记录】已有 ").append(outOfPlanDecisions.get())
                    .append(" 次工具调用超出方案清单（已审计）——如无必要不要再使用清单外工具。")
                    .append(System.lineSeparator());
        }
        if (budgetExhausted()) {
            sb.append("【预算纪律】预算已用尽：本轮起禁止再调用任何工具，")
                    .append("必须基于已有信息直接输出最终回答（证据不足则如实说明）。")
                    .append(System.lineSeparator());
        } else if (remainingRounds() == 1) {
            sb.append("【预算纪律】这是最后一轮：若信息已足够请直接给出最终回答，不要再规划新的工具调用。")
                    .append(System.lineSeparator());
        }
        sb.append("</task_loop_state>");
        return sb.toString();
    }
}
