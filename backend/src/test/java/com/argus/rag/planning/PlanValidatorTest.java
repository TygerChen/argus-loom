package com.argus.rag.planning;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案校验器契约（模型提方案，代码做校验）：
 * 工具白名单剥除、模型参数强制待定、可信参数代码注入、预算封顶、退出条件兜底、确认门代码派生。
 */
@DisplayName("方案校验器（PlanValidator）")
class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();
    private final AuthenticatedUser user = new AuthenticatedUser(86L, "U10086", "张三", SystemRole.USER, false);

    @Test
    void unknownToolsAreStrippedAndStepsRenumbered() {
        ExecutionPlan candidate = new ExecutionPlan("查年假", List.of(
                new ExecutionPlan.PlanStep(1, "检索制度", "knowledgeBaseSearch", List.of()),
                new ExecutionPlan.PlanStep(2, "直接改数据库", "executeSql", List.of()),
                new ExecutionPlan.PlanStep(3, "生成结论", "DIRECT_ANSWER", List.of())),
                List.of(), 3, List.of(), "找到唯一有效证据即停止", false);
        ExecutionPlan out = validator.validate(candidate, false, user, List.of(1L));
        assertEquals(2, out.steps().size(), "清单外工具步骤被剥除");
        assertEquals("knowledgeBaseSearch", out.steps().get(0).toolName());
        assertEquals(1, out.steps().get(0).order(), "步骤重新编号");
        assertEquals("DIRECT_ANSWER", out.steps().get(1).toolName());
    }

    @Test
    void modelParamsForcedUntrustedAndTrustedInjectedByCode() {
        ExecutionPlan candidate = new ExecutionPlan("查年假", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch",
                        List.of(new ExecutionPlan.PlanParam("query", "华东 年假", true),
                                new ExecutionPlan.PlanParam("role", "ADMIN", false)))),
                List.of(), 3, List.of(), "找到即停止", false);
        ExecutionPlan out = validator.validate(candidate, false, user, List.of(3L, 5L));
        assertTrue(out.steps().get(0).params().stream().allMatch(p -> !p.trusted()),
                "模型提议的参数一律待定——模型不能给自己授信");
        assertTrue(out.trustedParams().stream().anyMatch(p -> p.name().equals("userId") && p.trusted()));
        assertTrue(out.trustedParams().stream().anyMatch(p -> p.name().equals("knowledgeGroups")
                && p.value().equals("3,5")));
    }

    @Test
    void budgetClampedAndExitConditionDefaulted() {
        ExecutionPlan candidate = new ExecutionPlan("查", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
                List.of(), 99, List.of(), "", false);
        ExecutionPlan out = validator.validate(candidate, false, user, List.of());
        assertEquals(5, out.maxRounds(), "预算封顶 5");
        assertTrue(out.exitCondition().contains("预算轮次用尽"), "退出条件缺失时代码注入默认值");
    }

    @Test
    void needConfirmDerivedFromCodeNotModel() {
        ExecutionPlan high = new ExecutionPlan("导出", List.of(
                new ExecutionPlan.PlanStep(1, "检索", "knowledgeBaseSearch", List.of())),
                List.of(), 3, List.of(), "完成即停", false);
        assertTrue(validator.validate(high, true, user, List.of()).needConfirm(),
                "入口 HIGH → 确认门=true（模型建议 false 也不采信）");
        assertFalse(validator.validate(high, false, user, List.of()).needConfirm(),
                "入口 LOW → 只读方案无需确认");
    }
}
