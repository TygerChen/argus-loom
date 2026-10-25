package com.argus.rag.assistant.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ToolRiskLevel} 工具风险分级契约（纯枚举，Lock Function Calling 2.7 分级）。
 * 完整四档 LOW/MEDIUM/HIGH/CRITICAL，且定义顺序稳定（对应自动放行→人工审批的升级梯度）。
 */
@DisplayName("工具风险分级（ToolRiskLevel）")
class ToolRiskLevelTest {

    @Test
    void exposesExactlyFourRiskLevels() {
        assertEquals(4, ToolRiskLevel.values().length);
    }

    @Test
    void consistsOfTheFourCanonicalLevels() {
        assertEquals(EnumSet.of(ToolRiskLevel.LOW, ToolRiskLevel.MEDIUM,
                        ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL),
                EnumSet.allOf(ToolRiskLevel.class));
    }
}