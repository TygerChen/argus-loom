package com.argus.rag.assistant.model;

import com.argus.rag.planning.ExecutionPlan;
import com.argus.rag.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 返回形态派生契约（第九步：代码仲裁，模型无权决定）：
 * 确认门>参考>冲突>确定 的优先级；友好提示只给冲突/需确认形态（参考的核验提示由第八步生成）。
 */
@DisplayName("返回形态（ResultShape）")
class ResultShapeTest {

    private static final ExecutionPlan CONFIRM_PLAN = new ExecutionPlan("导出", List.of(
            new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
            List.of(), 3, List.of(), "找到即停", true);
    private static final ExecutionPlan PLAIN_PLAN = new ExecutionPlan("查", List.of(
            new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
            List.of(), 3, List.of(), "找到即停", false);

    @Test
    void confirmationGateWinsOverEverything() {
        VerificationResult unsupported = new VerificationResult(false, 30, List.of("x"), "无证据");
        assertEquals(ResultShape.NEEDS_CONFIRMATION,
                ResultShape.resolve(CONFIRM_PLAN, unsupported, List.of("版本冲突")),
                "入口高风险（确认门）优先级最高——即使内容未获支撑/有冲突");
        assertEquals(ResultShape.NEEDS_CONFIRMATION.friendlyNotice(),
                ResultShape.resolve(CONFIRM_PLAN, null, null).friendlyNotice());
    }

    @Test
    void unsupportedVerificationMeansReference() {
        VerificationResult unsupported = new VerificationResult(false, 30, List.of("x"), "无证据");
        assertEquals(ResultShape.REFERENCE, ResultShape.resolve(PLAIN_PLAN, unsupported, List.of()));
        // 参考形态不追加提示——核验提示由第八步 annotate 生成，避免双标注
        assertEquals("", ResultShape.REFERENCE.friendlyNotice());
    }

    @Test
    void conflictSignalsYieldConflictShape() {
        VerificationResult supported = new VerificationResult(true, 90, List.of(), "结论均有支撑");
        assertEquals(ResultShape.CONFLICT,
                ResultShape.resolve(PLAIN_PLAN, supported, List.of("总部与华东制度存在版本冲突")));
        assertTrue(ResultShape.CONFLICT.friendlyNotice().contains("不替") || 
                ResultShape.CONFLICT.friendlyNotice().contains("口径"),
                "冲突提示必须引导用户自行判断/确认");
    }

    @Test
    void cleanResultIsDetermined() {
        VerificationResult supported = new VerificationResult(true, 90, List.of(), "结论均有支撑");
        assertEquals(ResultShape.DETERMINED, ResultShape.resolve(PLAIN_PLAN, supported, List.of("时间范围待确认")));
        assertEquals("", ResultShape.DETERMINED.friendlyNotice(), "确定答案不加噪音提示");
        assertFalse(ResultShape.DETERMINED.displayName().isBlank());
    }
}
