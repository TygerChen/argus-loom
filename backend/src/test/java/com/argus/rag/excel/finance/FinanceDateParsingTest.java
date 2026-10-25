package com.argus.rag.excel.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FinanceSheetResolver#parseDateText} 与 {@code FinanceWorkbookExtractor#parseDateText}
 * 日期文本解析行为锁。
 * <p>
 * 两处各有一份几乎相同的 private 解析规则（8 位 yyyyMMdd → LocalDate.of，再依次尝试
 * 若干 `DateTimeFormatter` 模式），均为无 IO 的纯函数。此处放宽为同包可见后直接锁定其
 * 行为，便于日后收敛重复逻辑时不静默改变解读。注意 Resolver 的模式集比 Extractor 多了
 * `yyyy-MM`，但 `LocalDate.parse` 按月解析缺「日」必将抛异常 → 该多出的模式实际失效，
 * 两实现行为一致。
 * </p>
 */
class FinanceDateParsingTest {

    private static final String SPACE = " ";

    private FinanceSheetResolver newResolver() {
        return new FinanceSheetResolver();
    }

    private FinanceWorkbookExtractor newExtractor() {
        return new FinanceWorkbookExtractor(new FinanceSheetResolver(), new ObjectMapper());
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    // ---------- 公共子集：两种实现应一致 ----------

    @Test
    void blankReturnsNullForBoth() {
        assertNull(newResolver().parseDateText(null));
        assertNull(newResolver().parseDateText(""));
        assertNull(newResolver().parseDateText("   "));
        assertNull(newExtractor().parseDateText(null));
        assertNull(newExtractor().parseDateText(""));
        assertNull(newExtractor().parseDateText("   "));
    }

    @Test
    void compactEightDigitParsedForBoth() {
        assertEquals(d(2024, 5, 6), newResolver().parseDateText("20240506"));
        assertEquals(d(2024, 5, 6), newExtractor().parseDateText("20240506"));
        assertEquals(d(2023, 12, 31), newResolver().parseDateText("20231231"));
        assertEquals(d(2023, 12, 31), newExtractor().parseDateText("20231231"));
    }

    @Test
    void commonPatternsParsedForBoth() {
        // yyyy-MM-dd
        assertEquals(d(2024, 5, 6), newResolver().parseDateText("2024-05-06"));
        assertEquals(d(2024, 5, 6), newExtractor().parseDateText("2024-05-06"));
        // yyyy/M/d
        assertEquals(d(2024, 5, 6), newResolver().parseDateText("2024/5/6"));
        assertEquals(d(2024, 5, 6), newExtractor().parseDateText("2024/5/6"));
        // yyyy年M月d日
        assertEquals(d(2024, 5, 6), newResolver().parseDateText("2024年5月6日"));
        assertEquals(d(2024, 5, 6), newExtractor().parseDateText("2024年5月6日"));
    }

    @Test
    void invalidResolutionsReturnNullForBoth() {
        // 非法月 13
        assertNull(newResolver().parseDateText("20241301"));
        assertNull(newExtractor().parseDateText("20241301"));
        // 无法识别
        assertNull(newResolver().parseDateText("garbage"));
        assertNull(newExtractor().parseDateText("garbage"));
        // 8 位但非法日 32
        assertNull(newResolver().parseDateText("20240432"));
        assertNull(newExtractor().parseDateText("20240432"));
    }

    @Test
    void yearMonthWithoutDayReturnsNullInBoth() {
        // Resolver 的 DATE_PATTERNS 含 "yyyy-MM"，但 LocalDate.parse 按月解析缺「日」
        // 会抛 DateTimeParseException → 两者对 "2024-05" 均回退 null（该模式对 LocalDate 失效）
        assertNull(newResolver().parseDateText("2024-05"));
        assertNull(newExtractor().parseDateText("2024-05"));
    }

    @Test
    void leadingTrailingAsciiWhitespaceTrimmedForBoth() {
        String padded = SPACE + "2024-05-06" + SPACE;
        assertEquals(d(2024, 5, 6), newResolver().parseDateText(padded));
        assertEquals(d(2024, 5, 6), newExtractor().parseDateText(padded));
    }

    // ---------- isDateSerialLabel：日期序列号启发式（整型数值在 [30000,100000) 视为 Excel 日期序列） ----------

    @Test
    void dateSerialLabelDetectsSerialRanges() {
        assertTrue(newExtractor().isDateSerialLabel("40000"));
        assertTrue(newExtractor().isDateSerialLabel("45678.9"));
        assertTrue(newExtractor().isDateSerialLabel("99999.9"));
    }

    @Test
    void dateSerialLabelRejectsBoundaries() {
        assertTrue(!newExtractor().isDateSerialLabel("30000"));
        assertTrue(!newExtractor().isDateSerialLabel("100000"));
        assertTrue(!newExtractor().isDateSerialLabel("12345.6"));
    }

    @Test
    void dateSerialLabelRejectsNonNumericOrSized() {
        assertTrue(!newExtractor().isDateSerialLabel(null));
        assertTrue(!newExtractor().isDateSerialLabel("abc"));
        assertTrue(!newExtractor().isDateSerialLabel(" 40000 "));
        // 8 位紧凑日期(20240506)超出上限、非序列
        assertTrue(!newExtractor().isDateSerialLabel("20240506"));
    }
}