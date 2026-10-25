package com.argus.rag.excel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExcelSheetService} 纯计算族行为锁（static 同包直调，零 mapper/IO）
 */
@DisplayName("Sheet 前端数据→celldata 转换（ExcelSheetService 纯 static 族）")
class ExcelSheetCelldataMappingTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(List<Object> list, int i) {
        return (Map<String, Object>) list.get(i);
    }

    private static int intVal(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ---------- toJsonObject / toJsonArray ----------

    @Test
    void toJsonObjectNullYieldsEmpty() {
        assertEquals(0, ExcelSheetService.toJsonObject(null).size());
    }

    @Test
    void toJsonObjectPassesThroughMap() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("k", "v");
        assertSame(obj, ExcelSheetService.toJsonObject(obj));
    }

    @Test
    void toJsonObjectConvertsMap() {
        Map<String, Object> out = ExcelSheetService.toJsonObject(Map.of("a", 1));
        assertEquals(1, intVal(out.get("a")));
    }

    @Test
    void toJsonArrayNullYieldsEmpty() {
        assertEquals(0, ExcelSheetService.toJsonArray(null).size());
    }

    @Test
    void toJsonArrayConvertsList() {
        List<Object> out = ExcelSheetService.toJsonArray(List.of(1, 2, 3));
        assertEquals(3, out.size());
        assertEquals(2, intVal(out.get(1)));
    }

    // ---------- isEmptyCellValue ----------

    @Test
    void emptyValueDetection() {
        assertTrue(ExcelSheetService.isEmptyCellValue(null));
        assertTrue(ExcelSheetService.isEmptyCellValue(new LinkedHashMap<>()));
        assertTrue(ExcelSheetService.isEmptyCellValue(Map.of()));
        assertFalse(ExcelSheetService.isEmptyCellValue(Map.of("a", 1)));
        Map<String, Object> nonEmpty = new LinkedHashMap<>();
        nonEmpty.put("v", 5);
        assertFalse(ExcelSheetService.isEmptyCellValue(nonEmpty));
    }

    // ---------- intValue / stringValue ----------

    @Test
    void intValueFallsBackOnBadInput() {
        assertEquals(7, ExcelSheetService.intValue(null, 7));
        assertEquals(5, ExcelSheetService.intValue("5", 7));
        assertEquals(7, ExcelSheetService.intValue("abc", 7));
    }

    @Test
    void stringValueFallsBackOnNullOrBlank() {
        assertEquals("def", ExcelSheetService.stringValue(null, "def"));
        assertEquals("def", ExcelSheetService.stringValue("   ", "def"));
        assertEquals("ok", ExcelSheetService.stringValue("ok", "def"));
    }

    // ---------- calcTotalRows / calcTotalCols ----------

    @Test
    void totalRowsMaxOfMatrixAndCelldata() {
        assertEquals(6, ExcelSheetService.calcTotalRows(
                List.of(List.of(1, 2), List.of(3, 4)),
                cellDataR(5)));
    }

    @Test
    void totalColsMaxOfMatrixAndCelldata() {
        assertEquals(3, ExcelSheetService.calcTotalCols(
                List.of(List.of(1, 2), List.of(3, 4, 5)),
                cellDataC(1)));
    }

    private static List<Object> cellDataR(int r) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("r", r);
        List<Object> arr = new ArrayList<>();
        arr.add(c);
        return arr;
    }

    private static List<Object> cellDataC(int c) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("c", c);
        List<Object> arr = new ArrayList<>();
        arr.add(cell);
        return arr;
    }

    // ---------- dataMatrixToCelldata / normalizeCelldata ----------

    @SuppressWarnings("unchecked")
    @Test
    void dataMatrixExpandsToCellsSkippingNull() {
        List<List<Object>> matrix = List.of(
                Arrays.asList(Map.of("v", "a"), Map.of("v", "b")),
                Arrays.asList(Map.of("v", "c"), null));
        List<Object> cells = ExcelSheetService.dataMatrixToCelldata(matrix);

        assertEquals(3, cells.size());
        assertEquals(0, intVal(cell(cells, 0).get("r")));
        assertEquals(0, intVal(cell(cells, 0).get("c")));
        assertEquals("a", ((Map<?, ?>) cell(cells, 0).get("v")).get("v"));
        assertEquals(1, intVal(cell(cells, 2).get("r")));
        assertEquals(0, intVal(cell(cells, 2).get("c")));
        assertEquals("c", ((Map<?, ?>) cell(cells, 2).get("v")).get("v"));
    }

    @Test
    void dataMatrixWithEmptyMapSkipsCell() {
        List<List<Object>> matrix = List.of(List.of(Map.of(), Map.of("v", "x")));
        List<Object> cells = ExcelSheetService.dataMatrixToCelldata(matrix);
        assertEquals(1, cells.size());
        assertEquals(1, intVal(cell(cells, 0).get("c")));
    }

    @Test
    void normalizeCelldataDropsInvalidAndEmptyButKeepsValid() {
        List<Object> raw = new ArrayList<>();

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("r", 0);
        ok.put("c", 0);
        Map<String, Object> okVal = new LinkedHashMap<>();
        okVal.put("value", 1);
        ok.put("v", okVal);

        Map<String, Object> noR = new LinkedHashMap<>();
        noR.put("c", 1);
        noR.put("v", new LinkedHashMap<>());

        Map<String, Object> emptyV = new LinkedHashMap<>();
        emptyV.put("r", 2);
        emptyV.put("c", 1);
        emptyV.put("v", new LinkedHashMap<>());

        raw.add(ok);
        raw.add(noR);
        raw.add(emptyV);

        List<Object> norm = ExcelSheetService.normalizeCelldata(raw);
        assertEquals(1, norm.size());
        assertEquals(0, intVal(cell(norm, 0).get("r")));
    }

    @Test
    void normalizeCelldataKeepsNonEmptyValues() {
        List<Object> raw = new ArrayList<>();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("r", 3);
        c.put("c", 4);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("value", true);
        c.put("v", v);
        raw.add(c);

        List<Object> norm = ExcelSheetService.normalizeCelldata(raw);
        assertEquals(1, norm.size());
        assertEquals(4, intVal(cell(norm, 0).get("c")));
    }
}
