package com.argus.rag.excel.service;

import com.argus.rag.common.util.JsonCompat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("流式解析输出契约（StreamingParserService）")
class StreamingParserServiceTest {

    private final StreamingParserService service = new StreamingParserService();

    private static Map<Integer, Map<Integer, String>> rows(String[][] data) {
        Map<Integer, Map<Integer, String>> rows = new LinkedHashMap<>();
        for (var fe : data) {
            int r = Integer.parseInt(fe[0]);
            rows.computeIfAbsent(r, k -> new LinkedHashMap<>()).put(Integer.parseInt(fe[1]), fe[2]);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(List<Object> list, int i) {
        return (Map<String, Object>) list.get(i);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static int intVal(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ---------- toCelldata ----------

    @Test
    void toCelldataEmitsCompatibleCellShape() {
        List<Object> arr = StreamingParserService.toCelldata(rows(new String[][]{{"0", "0", "abc"}}));
        assertEquals(1, arr.size());
        Map<String, Object> c = cell(arr, 0);
        assertEquals(0, intVal(c.get("r")));
        assertEquals(0, intVal(c.get("c")));
        Map<String, Object> v = subMap(c, "v");
        assertEquals("abc", v.get("v"));
        assertEquals("abc", v.get("m"));
        Map<String, Object> ct = subMap(v, "ct");
        assertEquals("General", ct.get("fa"));
        assertEquals("s", ct.get("t"));
    }

    @Test
    void toCelldataPreservesAllCellsAcrossRows() {
        List<Object> arr = StreamingParserService.toCelldata(rows(new String[][]{
                {"0", "0", "a"}, {"1", "2", "b"}, {"0", "1", "c"}
        }));
        assertEquals(3, arr.size());
        boolean has01 = false, has12 = false;
        for (Object obj : arr) {
            Map<String, Object> c = (Map<String, Object>) obj;
            int r = intVal(c.get("r")), cc = intVal(c.get("c"));
            assertTrue(r >= 0 && cc >= 0);
            if (r == 0 && cc == 1) has01 = true;
            if (r == 1 && cc == 2) has12 = true;
        }
        assertTrue(has01, "row0/col1 应存在");
        assertTrue(has12, "row1/col2 应存在");
    }

    @Test
    void toCelldataEmptyInputYieldsEmptyArray() {
        List<Object> arr = StreamingParserService.toCelldata(new LinkedHashMap<>());
        assertNotNull(arr);
        assertEquals(0, arr.size());
        assertEquals("[]", JsonCompat.toJSONString(arr));
    }

    @Test
    void toCelldataCarriesBlankValueAsStringType() {
        List<Object> arr = StreamingParserService.toCelldata(rows(new String[][]{{"3", "5", ""}}));
        assertEquals(1, arr.size());
        Map<String, Object> v = subMap(cell(arr, 0), "v");
        assertEquals("", v.get("v"));
        Map<String, Object> ct = subMap(v, "ct");
        assertEquals("s", ct.get("t"));
    }

    // ---------- toMergeConfig ----------

    @Test
    void toMergeConfigPassesKeysAndReferencesThrough() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("r", 0);
        entry.put("rs", 2);
        Map<String, Map<String, Object>> merges = new LinkedHashMap<>();
        merges.put("0_4", entry);
        Map<String, Object> cfg = StreamingParserService.toMergeConfig(merges);
        assertEquals(1, cfg.size());
        assertSame(entry, cfg.get("0_4"));
        assertEquals(0, intVal(subMap(cfg, "0_4").get("r")));
        assertEquals(2, intVal(subMap(cfg, "0_4").get("rs")));
    }

    @Test
    void toMergeConfigEmptyInputYieldsEmptyObject() {
        Map<String, Object> cfg = StreamingParserService.toMergeConfig(new LinkedHashMap<>());
        assertEquals(0, cfg.size());
    }

    // ---------- toColumnWidths ----------

    @Test
    void toColumnWidthsStringifiedKeyAndValueThrough() {
        Map<Integer, Integer> w = new LinkedHashMap<>();
        w.put(0, 14);
        w.put(3, 7);
        Map<String, Object> cfg = StreamingParserService.toColumnWidths(w);
        assertEquals(2, cfg.size());
        assertEquals(14, intVal(cfg.get("0")));
        assertEquals(7, intVal(cfg.get("3")));
    }

    // ---------- parseStreaming 错误路径 ----------

    @Test
    void parseStreamingRejectsGarbageInput() {
        assertThrows(Exception.class, () -> service.parseStreaming(new ByteArrayInputStream(new byte[]{0, 1, 2, 3})));
    }
}
