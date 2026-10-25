package com.argus.rag.excel.pipeline.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColumnFilterStrategy} 行为契约测试。
 *
 * <p>锁住「运算符 → 过滤判定」映射，与 {@code TableIngestionService.queryStructuredData}
 * 重构前的高危 switch 语义一致：null 默认 eq；不识别回落永假。</p>
 */
@DisplayName("列过滤策略（ColumnFilterStrategy）")
class ColumnFilterStrategyTest {

    @Test
    void eqIsCaseInsensitive() {
        ColumnFilterStrategy s = ColumnFilterStrategy.from("eq");
        assertTrue(s.matches("Acme", "acme"));
        assertFalse(s.matches("Acme", "Acme Corp"));
    }

    @Test
    void eqNullTargetNeverMatches() {
        assertFalse(ColumnFilterStrategy.EQ.matches("张三", null));
        assertFalse(ColumnFilterStrategy.EQ.matches(null, "张三"));
    }

    @Test
    void containsIsCaseInsensitiveSubstring() {
        ColumnFilterStrategy s = ColumnFilterStrategy.from("contains");
        assertTrue(s.matches("研发部一区", "研发部"));
        assertTrue(s.matches("Acme Corp", "ACME"));
        assertFalse(s.matches("研发部", "财务部"));
    }

    @Test
    void gtIsNumericAndIgnoresThousandSeparators() {
        ColumnFilterStrategy s = ColumnFilterStrategy.from("gt");
        assertTrue(s.matches("12,000", "10000"));
        assertFalse(s.matches("8,000", "9000"));
        assertFalse(s.matches("8000", "abc")); // 目标解析失败 → false
        assertFalse(s.matches("abc", "9000")); // 单元格解析失败 → false
    }

    @Test
    void ltIsNumericAndIgnoresThousandSeparators() {
        ColumnFilterStrategy s = ColumnFilterStrategy.from("lt");
        assertFalse(s.matches("12,000", "10000"));
        assertTrue(s.matches("8,000", "9000"));
    }

    @Test
    void fromResolvesOperatorsCaseInsensitively() {
        assertEquals(ColumnFilterStrategy.EQ, ColumnFilterStrategy.from("eq"));
        assertEquals(ColumnFilterStrategy.CONTAINS, ColumnFilterStrategy.from("CONTAINS"));
        assertEquals(ColumnFilterStrategy.GT, ColumnFilterStrategy.from("Gt"));
        assertEquals(ColumnFilterStrategy.LT, ColumnFilterStrategy.from("lt"));
    }

    @Test
    void fromDefaultsToEqWhenNullAndNoOpForUnknown() {
        assertEquals(ColumnFilterStrategy.EQ, ColumnFilterStrategy.from(null));
        assertEquals(ColumnFilterStrategy.NO_OP, ColumnFilterStrategy.from("between"));
        assertFalse(ColumnFilterStrategy.from("between").matches("v", "t"));
    }
}