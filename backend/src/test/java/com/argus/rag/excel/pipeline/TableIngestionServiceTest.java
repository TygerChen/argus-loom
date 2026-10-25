package com.argus.rag.excel.pipeline;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TableIngestionService#queryStructuredData} 行为契约测试。
 *
 * <p>结构化查询链路是重构（策略收敛）前的高危点——用例把 4 算子、找不到列兜底、
 * 默认 {@code eq} 等对外行为锁住，重构必须保持等价。</p>
 */
@DisplayName("结构化数据查询（TableIngestionService）")
class TableIngestionServiceTest {

    private ExcelSheet sheet;
    private List<ExcelSheetChunk> chunks;

    @BeforeEach
    void setUp() {
        sheet = new ExcelSheet();
        sheet.setSheetName("工资");
        sheet.setTotalRows(4);   // 0..3
        sheet.setTotalCols(2);

        ExcelSheetChunk chunk = new ExcelSheetChunk();
        chunk.setId(1L);
        chunk.setCelldataJson(render(
                cell(0, 0, "姓名"), cell(0, 1, "部门"),
                cell(1, 0, "张三"), cell(1, 1, "研发部"),
                cell(2, 0, "李四"), cell(2, 1, "财务部"),
                cell(3, 0, "王五"), cell(3, 1, "研发部")));
        chunks = List.of(chunk);
    }

    /** 收入：queryStructuredData 只依赖 schemaService；sheetService 参数在本方法无副作用。 */
    private TableIngestionService service() {
        return new TableIngestionService(null, new TableSchemaService());
    }

    @Test
    void eqFiltersMatchingRowsByColumnName() {
        Map<String, Object> r = service().queryStructuredData(sheet, chunks, filter("姓名", "eq", "张三"), 10);
        assertEquals(1, ((Number) r.get("matchCount")).intValue());
        @SuppressWarnings("unchecked")
        List<Object> rows = JsonCompat.getJSONArray(r, "rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRow = (Map<String, Object>) rows.get(0);
        assertEquals("张三", JsonCompat.getString(firstRow, "姓名"));
    }

    @Test
    void nullOperatorFallsBackToEq() {
        // 无 operator → 默认 eq，命中「部门=研发部」的 2 行
        Map<String, Object> r = service().queryStructuredData(sheet, chunks, filter("部门", null, "研发部"), 10);
        assertEquals(2, ((Number) r.get("matchCount")).intValue());
    }

    @Test
    void containsMatchesSubstringIgnoringCase() {
        Map<String, Object> r = service().queryStructuredData(sheet, chunks, filter("部门", "contains", "研发"), 10);
        assertEquals(2, ((Number) r.get("matchCount")).intValue());
    }

    @Test
    void gtAndLtCompareNumericallyIgnoringComma() {
        sheet = new ExcelSheet(); sheet.setSheetName("工资"); sheet.setTotalRows(4); sheet.setTotalCols(3);
        ExcelSheetChunk c = new ExcelSheetChunk(); c.setId(1L);
        c.setCelldataJson(render(
                cell(0, 0, "姓名"), cell(0, 1, "部门"), cell(0, 2, "薪资"),
                cell(1, 0, "张三"), cell(1, 1, "研发部"), cell(1, 2, "12,000"),
                cell(2, 0, "李四"), cell(2, 1, "财务部"), cell(2, 2, "8,000"),
                cell(3, 0, "王五"), cell(3, 1, "研发部"), cell(3, 2, "20,000")));
        chunks = List.of(c);

        Map<String, Object> rGt = service().queryStructuredData(sheet, chunks, filter("薪资", "gt", "10000"), 10);
        assertEquals(2, ((Number) rGt.get("matchCount")).intValue()); // 张三、王五
        Map<String, Object> rLt = service().queryStructuredData(sheet, chunks, filter("薪资", "lt", "10,000"), 10);
        assertEquals(1, ((Number) rLt.get("matchCount")).intValue()); // 李四
    }

    @Test
    void unknownColumnReturnsErrorKey() {
        Map<String, Object> r = service().queryStructuredData(sheet, chunks, filter("不存在", "eq", "x"), 10);
        assertTrue(r.containsKey("error"));
        assertTrue(JsonCompat.getString(r, "error").contains("找不到列"));
    }

    private Map<String, Object> filter(String column, String operator, String value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("column", column);
        if (operator != null) f.put("operator", operator);
        f.put("value", value);
        return f;
    }

    private String render(Map<String, Object>... cells) {
        List<Object> arr = new ArrayList<>();
        for (Map<String, Object> c : cells) arr.add(c);
        return JsonCompat.toJSONString(arr);
    }

    private Map<String, Object> cell(int r, int c, String v) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("r", r); o.put("c", c); o.put("v", v);
        return o;
    }
}
