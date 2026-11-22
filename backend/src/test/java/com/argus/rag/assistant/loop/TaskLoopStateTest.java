package com.argus.rag.assistant.loop;

import com.argus.rag.planning.ExecutionPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务循环状态契约：轮次推进/剩余预算、工具结果摘要截断、
 * 预算用尽→强制停止指令、退出条件缺失→默认值。
 */
@DisplayName("任务循环状态（TaskLoopState）")
class TaskLoopStateTest {

    @Test
    void roundsAdvanceAndBudgetBlockAppears() {
        TaskLoopState state = TaskLoopState.from(new ExecutionPlan("查", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
                List.of(), 3, List.of(), "找到唯一有效证据即停止", false));
        state.nextRound();
        String block1 = state.asContextBlock();
        assertTrue(block1.contains("当前轮次：1 / 预算 3"), "状态块必须显示轮次与预算");
        assertTrue(block1.contains("退出条件"), "退出条件必须每轮可见");
        state.nextRound();
        state.nextRound();
        assertTrue(state.asContextBlock().contains("预算已用尽"), "预算用尽必须出现强制停止指令");
        assertEquals(0, state.remainingRounds());
    }

    @Test
    void toolDigestsTruncatedAndLatestShown() {
        TaskLoopState state = TaskLoopState.defaults();
        state.recordToolResult("knowledgeBaseSearch", "很长".repeat(200));
        String block = state.asContextBlock();
        assertTrue(block.contains("[knowledgeBaseSearch]"));
        assertTrue(block.length() < 600, "摘要必须截断，不携带工具原文");
    }

    @Test
    void blankExitConditionFallsBackToDefault() {
        TaskLoopState state = TaskLoopState.from(null);
        assertTrue(state.asContextBlock().contains("预算轮次用尽即停止"));
    }

    @Test
    void runnerVerdictControlsLoopAndGraphLimit() {
        // 第七步：继续/退出由运行器裁决；图递归上限由预算派生
        TaskLoopState state = TaskLoopState.from(new ExecutionPlan("查", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
                List.of(), 3, List.of(), "找到即停", false));
        assertTrue(state.verdict().continueLoop(), "预算未耗尽 → 继续");
        assertTrue(state.asContextBlock().contains("循环裁决"));
        assertEquals(8, state.graphRecursionLimit(), "3 轮预算 → 3*2+2=8 图步上限");

        for (int i = 0; i < 3; i++) {
            state.nextRound();
        }
        org.junit.jupiter.api.Assertions.assertFalse(state.verdict().continueLoop());
        assertTrue(state.verdict().reason().contains("预算耗尽"));
    }
}
