package com.argus.rag.assistant.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.argus.rag.assistant.agent.tool.ContextBudget.budget;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ContextBudget} 上下文预算分配契约（纯 JVM，无状态）。
 * 比例项总量=1.0，预算=(int)(window×ratio) 且向下取整（截断）。
 */
@DisplayName("上下文预算分配（ContextBudget）")
class ContextBudgetTest {

    private static final int W = ContextBudget.DEFAULT_WINDOW; // 8192

    @Test
    void defaultWindowIs8192() {
        assertEquals(8192, W);
    }

    @Test
    void ratiosSumToOne() {
        double total = ContextBudget.SYSTEM_INSTRUCTION_RATIO
                + ContextBudget.TOOL_DEFINITIONS_RATIO
                + ContextBudget.LONG_TERM_MEMORY_RATIO
                + ContextBudget.KNOWLEDGE_RATIO
                + ContextBudget.MESSAGE_HISTORY_RATIO
                + ContextBudget.CURRENT_INPUT_RATIO
                + ContextBudget.OUTPUT_RESERVE_RATIO;
        assertEquals(1.0, total, 1e-9);
    }

    @Test
    void budgetComputesIntegerFractionTowardsZero() {
        assertEquals((int) (8192 * 0.20), budget(8192, 0.20));
        assertEquals(1638, budget(8192, 0.20));
        assertEquals(2048, budget(8192, 0.25));
        assertEquals(409, budget(8192, 0.05));
    }

    @Test
    void zeroWindowYieldsZero() {
        assertEquals(0, budget(0, 0.25));
    }

    @Test
    void smallWindowTruncatesFractions() {
        // 10 × 0.10 = 1.0 → 1；10 × 0.25 = 2.5 → 2（向下取整）
        assertEquals(1, budget(10, ContextBudget.CURRENT_INPUT_RATIO));
        assertEquals(2, budget(10, ContextBudget.KNOWLEDGE_RATIO));
    }

    @Test
    void fullRatioUsesWholeWindow() {
        assertEquals(8192, budget(8192, 1.0));
    }
}