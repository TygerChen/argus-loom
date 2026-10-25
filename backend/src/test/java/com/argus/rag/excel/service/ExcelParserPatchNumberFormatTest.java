package com.argus.rag.excel.service;

import com.argus.rag.common.util.JsonCompat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExcelParserService#patchNumberFormat(Map, Map)} 行为锁
 * （纯 JVM、同包 package-private 直调、`new ExcelParserService(null, null)` 零 mapper、零 POI）。
 * <p>锁契约：仅当库值与解析值均数字、类型仍为 n(ct.t) 且公式键存在性/值一致时，才把解析后的
 * 格式(m/ct)写回 storedValue 并返回 true；格式与原一致或任一前置不满足 → false 不修改。</p>
 */
@DisplayName("数字格式修复决策（ExcelParserService.patchNumberFormat）")
class ExcelParserPatchNumberFormatTest {

    private static final ExcelParserService SVC = new ExcelParserService(null, null);

    private static Map<String, Object> valueCell(double v, String fa, String m, String formula) {
        Map<String, Object> cell = new LinkedHashMap<>();
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("t", "n");
        ct.put("fa", fa);
        cell.put("ct", ct);
        cell.put("v", v);
        cell.put("m", m);
        if (formula != null) {
            cell.put("f", formula);
        }
        return cell;
    }

    private static Map<String, Object> textCell(Object value) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("v", value);
        cell.put("m", "acb_text");
        return cell;
    }

    private static Map<String, Object> cellOf(Map<String, Object> inner) {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("v", inner);
        return outer;
    }

    // ---------- 打补丁 / 无需打补丁 ----------

    @Test
    void differsInFormatPatchesStoredCell() {
        Map<String, Object> stored = cellOf(valueCell(3.14, "0.00", "0.00", null));
        Map<String, Object> parsed = cellOf(valueCell(3.14, "###.##", "3.14", null));
        assertTrue(SVC.patchNumberFormat(stored, parsed));
        // 格式串 m 被覆盖为解析值
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) stored.get("v");
        assertEquals("3.14", JsonCompat.getString(inner, "m"));
        // ct 换成解析格式，且 t 仍为 n
        @SuppressWarnings("unchecked")
        Map<String, Object> ct = (Map<String, Object>) inner.get("ct");
        assertEquals("n", JsonCompat.getString(ct, "t"));
        assertEquals("###.##", JsonCompat.getString(ct, "fa"));
    }

    @Test
    void identicalFormatAndValueReturnsFalse() {
        Map<String, Object> stored = cellOf(valueCell(3.14, "0.00", "0.00", null));
        Map<String, Object> parsed = cellOf(valueCell(3.14, "0.00", "0.00", null));
        assertFalse(SVC.patchNumberFormat(stored, parsed));
    }

    // ---------- 前置逐条否决 ----------

    @Test
    void nonNumberValueReturnsFalse() {
        Map<String, Object> stored = cellOf(textCell("abc"));
        Map<String, Object> parsed = cellOf(valueCell(3.14, "0.00", "0.00", null));
        assertFalse(SVC.patchNumberFormat(stored, parsed));
    }

    @Test
    void missingOrNonNumericCtReturnsFalse() {
        // ct 缺失
        Map<String, Object> ctMissing = new LinkedHashMap<>();
        ctMissing.put("v", 5.0);
        ctMissing.put("m", "0.00");
        assertFalse(SVC.patchNumberFormat(cellOf(ctMissing), cellOf(valueCell(5.0, "0.00", "5", null))));

        // ct.t 非 n（日期格式）
        Map<String, Object> notNumericCt = new LinkedHashMap<>();
        Map<String, Object> ctDate = new LinkedHashMap<>();
        ctDate.put("t", "d");
        ctDate.put("fa", "yyyy年mm月dd日");
        notNumericCt.put("ct", ctDate);
        notNumericCt.put("v", 5.0);
        notNumericCt.put("m", "5");
        assertFalse(SVC.patchNumberFormat(cellOf(notNumericCt), cellOf(valueCell(5.0, "0.00", "5", null))));
    }

    @Test
    void formulaPresenceMismatchReturnsFalse() {
        Map<String, Object> stored = cellOf(valueCell(3.14, "0.00", "0.00", "A1*2"));
        Map<String, Object> parsed = cellOf(valueCell(3.14, "0.00", "0.00", null));
        assertFalse(SVC.patchNumberFormat(stored, parsed));
    }

    @Test
    void formulaValueMismatchReturnsFalse() {
        Map<String, Object> stored = cellOf(valueCell(3.14, "0.00", "0.00", "A1*2"));
        Map<String, Object> parsed = cellOf(valueCell(3.14, "0.00", "0.00", "B2+1"));
        assertFalse(SVC.patchNumberFormat(stored, parsed));
    }

    @Test
    void matchingFormulasStillPatchOnFormatDifference() {
        Map<String, Object> stored = cellOf(valueCell(3.14, "0.00", "0.00", "A1*2"));
        Map<String, Object> parsed = cellOf(valueCell(3.14, "#,##0.00", "3.14", "A1*2"));
        assertTrue(SVC.patchNumberFormat(stored, parsed));
    }
}
