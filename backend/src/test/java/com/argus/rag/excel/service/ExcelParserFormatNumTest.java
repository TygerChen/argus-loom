package com.argus.rag.excel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExcelParserService#formatNum} 纯格式化行为锁。
 * <p>
 * 整数值去掉尾随 ".0"（3.0→"3"），小数值原样经 {@link String#valueOf(double)}。
 * 该方法改为静态同包可见以脱离实例构造直接测试。
 * </p>
 */
class ExcelParserFormatNumTest {

    @Test
    void wholeNumbersDropDecimalZeros() {
        assertEquals("3", ExcelParserService.formatNum(3.0D));
        assertEquals("0", ExcelParserService.formatNum(0.0D));
        assertEquals("-2", ExcelParserService.formatNum(-2.0D));
        assertEquals("1000000", ExcelParserService.formatNum(1000000.0D));
    }

    @Test
    void fractionalNumbersKeepValueOf() {
        assertEquals("3.5", ExcelParserService.formatNum(3.5D));
        assertEquals("123.456", ExcelParserService.formatNum(123.456D));
        assertEquals("0.1", ExcelParserService.formatNum(0.1D));
    }

    @Test
    void largeValuesFallBackToScientific() {
        // (long)1e20 溢出钳位、与 1e20 不相等 → 走 String.valueOf 科学计数
        assertEquals(String.valueOf(1e20), ExcelParserService.formatNum(1e20));
    }
}