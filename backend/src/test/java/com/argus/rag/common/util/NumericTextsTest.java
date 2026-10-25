package com.argus.rag.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link NumericTexts.parseOrNull} 锁：去千分位 + double 解析，失败/null 返回 null。
 * <p>收拢的两处：{@code ColumnFilterStrategy.numericOrNull}（直接委托）、
 * {@code TableSchemaService.isNumeric}（{@code parseOrNull!=null}，唯一调用点已先剔 null/空）。</p>
 */
class NumericTextsTest {

    @Test
    void stripsThousandsAndParses() {
        assertEquals(1234.5, NumericTexts.parseOrNull("1,234.5"));
        assertEquals(1000.0, NumericTexts.parseOrNull("1,000"));
        assertEquals(3.14, NumericTexts.parseOrNull("3.14"));
        assertEquals(-7.0, NumericTexts.parseOrNull("-7"));
    }

    @Test
    void nullBlankOrNonNumericReturnsNull() {
        assertNull(NumericTexts.parseOrNull(null));
        assertNull(NumericTexts.parseOrNull("  "));
        assertNull(NumericTexts.parseOrNull("abc"));
        assertNull(NumericTexts.parseOrNull("12abc"));
    }

    @Test
    void multipleCommasConcatenateToDigits() {
        assertEquals(123.0, NumericTexts.parseOrNull("1,2,3"));
    }
}