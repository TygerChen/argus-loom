package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelSheetChunkDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SheetChunkMerger} 增量合并纯函数契约（纯 JVM，零 DB/零事务/真实 Jackson）。
 */
@DisplayName("Sheet 增量合并（SheetChunkMerger）")
class SheetChunkMergerTest {

    private static ExcelSheetChunkDelta delta(int row, int col, String value) {
        ExcelSheetChunkDelta d = new ExcelSheetChunkDelta();
        d.setCellRow(row);
        d.setCellCol(col);
        d.setCellValue(value);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(List<Object> out, int index) {
        return (Map<String, Object>) out.get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vObj(Map<String, Object> cell) {
        return (Map<String, Object>) cell.get("v");
    }

    private static int intVal(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    @Test
    void nullOrEmptyBaseWithNoDeltasYieldsEmpty() {
        assertTrue(SheetChunkMerger.merge(null, List.of()).isEmpty());
        assertTrue(SheetChunkMerger.merge("", List.of()).isEmpty());
    }

    @Test
    void singleDeltaWritesRCV() {
        List<Object> out = SheetChunkMerger.merge(null, List.of(delta(0, 5, "{\"v\":\"甲\"}")));
        assertEquals(1, out.size());
        Map<String, Object> c = cell(out, 0);
        assertEquals(0, intVal(c.get("r")));
        assertEquals(5, intVal(c.get("c")));
        assertEquals("甲", vObj(c).get("v"));
    }

    @Test
    void laterDeltaOverridesSameKey() {
        List<Object> out = SheetChunkMerger.merge(null, List.of(
                delta(1, 2, "{\"v\":\"first\"}"),
                delta(1, 2, "{\"v\":\"second\"}")));
        assertEquals(1, out.size());
        assertEquals("second", vObj(cell(out, 0)).get("v"));
    }

    @Test
    void distinctKeysBothKeptInInsertOrder() {
        List<Object> out = SheetChunkMerger.merge(null, List.of(
                delta(0, 0, "{\"v\":\"a\"}"),
                delta(3, 9, "{\"v\":\"b\"}")));
        assertEquals(2, out.size());
        assertEquals(3, intVal(cell(out, 1).get("r")));
        assertEquals(9, intVal(cell(out, 1).get("c")));
    }

    @Test
    void baseCellsMergedAndOverriddenByDelta() {
        String base = "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"old\"}},{\"r\":0,\"c\":1,\"v\":{\"v\":\"keep\"}}]";
        List<Object> out = SheetChunkMerger.merge(base, List.of(delta(0, 0, "{\"v\":\"new\"}")));
        assertEquals(2, out.size());
        assertEquals("new", vObj(cell(out, 0)).get("v"));
    }

    @Test
    void emptyObjectValueDeletesCell() {
        String base = "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"gone\"}},{\"r\":0,\"c\":1,\"v\":{\"v\":\"stay\"}}]";
        List<Object> out = SheetChunkMerger.merge(base, List.of(delta(0, 0, "{}")));
        assertEquals(1, out.size());
        assertEquals(1, intVal(cell(out, 0).get("c")));
    }

    @Test
    void nullBlankAndLiteralNullAllDelete() {
        String base = "[{\"r\":0,\"c\":0,\"v\":{}},{\"r\":0,\"c\":1,\"v\":{}},{\"r\":0,\"c\":2,\"v\":{}}]";
        List<Object> out = SheetChunkMerger.merge(base, List.of(
                delta(0, 0, null),
                delta(0, 1, "   "),
                delta(0, 2, "null")));
        assertTrue(out.isEmpty());
    }

    @Test
    void objectWithOnlyWhitespaceMiddleIsTreatedAsEmpty() {
        String base = "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"gone\"}}]";
        List<Object> out = SheetChunkMerger.merge(base, List.of(delta(0, 0, "{\n}")));
        assertTrue(out.isEmpty());
    }

    @Test
    void addThenDeleteSameKeyYieldsEmpty() {
        List<Object> out = SheetChunkMerger.merge(null, List.of(
                delta(4, 4, "{\"v\":\"temp\"}"),
                delta(4, 4, "{}")));
        assertTrue(out.isEmpty());
    }

    @Test
    void deleteThenReaddRestoresCell() {
        List<Object> out = SheetChunkMerger.merge(null, List.of(
                delta(2, 2, "{\"v\":\"a\"}"),
                delta(2, 2, "{}"),
                delta(2, 2, "{\"v\":\"back\"}")));
        assertEquals(1, out.size());
        assertEquals("back", vObj(cell(out, 0)).get("v"));
    }

    @Test
    void payloadCarriesExtraKeysPreserved() {
        List<Object> out = SheetChunkMerger.merge(null, List.of(delta(1, 1, "{\"v\":\"x\",\"s\":\"General\"}")));
        Map<String, Object> c = cell(out, 0);
        assertEquals("x", vObj(c).get("v"));
        assertEquals("General", vObj(c).get("s"));
    }
}
