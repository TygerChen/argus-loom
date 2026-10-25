package com.argus.rag.excel.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FinanceWorkbookExtractor#parseNumberText} 与 {@link FinanceWorkbookExtractor#normalizeCurrency}
 * 金额/币种解析行为锁。
 * <p>
 * 两方法纯 String→ 数值/代码、无 IO：金额解析负责去千分位/空格后 {@link BigDecimal}；
 * 币种规范化按「代码子串 → 中文名」规则把文本映射为 ISO 代码。均为易错细节规则且此前无测试。
 * </p>
 */
class FinanceValueParsingTest {

    private FinanceWorkbookExtractor newExtractor() {
        return new FinanceWorkbookExtractor(new FinanceSheetResolver(), new ObjectMapper());
    }

    // ---------- parseNumberText ----------

    @Test
    void numberBlankReturnsNull() {
        assertNull(newExtractor().parseNumberText(null));
        assertNull(newExtractor().parseNumberText(""));
        assertNull(newExtractor().parseNumberText("   "));
    }

    @Test
    void plainDecimalParsed() {
        assertEquals(new BigDecimal("3.25"), newExtractor().parseNumberText("3.25"));
        assertEquals(new BigDecimal("-5.5"), newExtractor().parseNumberText("-5.5"));
    }

    @Test
    void thousandsSeparatorAndSpacesStripped() {
        assertEquals(new BigDecimal("1234.56"), newExtractor().parseNumberText("1,234.56"));
        assertEquals(new BigDecimal("1234567"), newExtractor().parseNumberText("1,234,567"));
        assertEquals(new BigDecimal("1234"), newExtractor().parseNumberText("1 234"));
    }

    @Test
    void nonNumericReturnsNull() {
        assertNull(newExtractor().parseNumberText("debit"));
        assertNull(newExtractor().parseNumberText("abc"));
        // 非数字字符(币种符号)同样无法解析
        assertNull(newExtractor().parseNumberText("€1,000"));
    }

    // ---------- normalizeCurrency ----------

    @Test
    void currencyBlankReturnsNull() {
        assertNull(newExtractor().normalizeCurrency(null));
        assertNull(newExtractor().normalizeCurrency(""));
        assertNull(newExtractor().normalizeCurrency("   "));
    }

    @Test
    void currencyIsoCodesDetectedCaseInsensitively() {
        assertEquals("CNY", newExtractor().normalizeCurrency("CNY"));
        assertEquals("USD", newExtractor().normalizeCurrency("usd"));
        assertEquals("HKD", newExtractor().normalizeCurrency("HKD"));
        assertEquals("SGD", newExtractor().normalizeCurrency("SGD"));
        assertEquals("EUR", newExtractor().normalizeCurrency("EUR"));
        assertEquals("JPY", newExtractor().normalizeCurrency("JPY"));
        assertEquals("GBP", newExtractor().normalizeCurrency("GBP"));
    }

    @Test
    void currencyChineseNamesMapped() {
        assertEquals("CNY", newExtractor().normalizeCurrency("人民币"));
        assertEquals("CNY", newExtractor().normalizeCurrency("人民币元"));
        assertEquals("USD", newExtractor().normalizeCurrency("美元"));
    }

    @Test
    void currencySubstringInMixedTextDetected() {
        // contains 匹配：带代码/中文名的混合文本也能识别
        assertEquals("USD", newExtractor().normalizeCurrency("美元 USD"));
        assertEquals("CNY", newExtractor().normalizeCurrency("人民币 1,234"));
    }

    @Test
    void unsupportedCurrencyReturnsNull() {
        // 不在代码表、也不含中文名的币种无法识别
        assertNull(newExtractor().normalizeCurrency("CHF"));
        assertNull(newExtractor().normalizeCurrency("RMB"));
        assertNull(newExtractor().normalizeCurrency("瑞士法郎"));
    }
}