package com.argus.rag.excel.pipeline.celldata;

import com.argus.rag.excel.entity.ExcelSheetChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CellDataTable} 单元格矩阵还原契约。
 */
@DisplayName("单元格数据矩阵（CellDataTable）")
class CellDataTableTest {

    private ExcelSheetChunk chunk(String cellsJson) {
        ExcelSheetChunk c = new ExcelSheetChunk();
        c.setId(1L);
        c.setCelldataJson(cellsJson);
        return c;
    }

    @Test
    void reconstructsRowColumnMatrixFromPlainCells() {
        CellDataTable t = CellDataTable.fromChunks(List.of(
                chunk("[{\"r\":0,\"c\":0,\"v\":\"姓名\"},{\"r\":0,\"c\":1,\"v\":\"部门\"}," +
                      "{\"r\":1,\"c\":0,\"v\":\"张三\"},{\"r\":1,\"c\":1,\"v\":\"研发部\"}]")));
        assertEquals(2, t.rows());
        assertEquals("姓名", CellDataTable.valueAsText(t.valueAt(0, 0)));
        assertEquals("研发部", CellDataTable.valueAsText(t.valueAt(1, 1)));
        assertTrue(t.hasRow(1));
        assertNull(t.valueAt(9, 9));       // 行不存在 → null
        assertNull(t.valueAt(1, 99));      // 列不存在 → null
    }

    @Test
    void valueAsTextPreferVThenMForObjectWrappedCells() {
        com.alibaba.fastjson.JSONObject obj = new com.alibaba.fastjson.JSONObject();
        obj.put("v", "12000"); obj.put("m", "12,000");
        assertEquals("12000", CellDataTable.valueAsText(obj));

        com.alibaba.fastjson.JSONObject nullV = new com.alibaba.fastjson.JSONObject();
        nullV.put("m", "m-only");
        assertEquals("m-only", CellDataTable.valueAsText(nullV));
        assertNull(CellDataTable.valueAsText(null));
    }

    @Test
    void malformedChunkIsSkippedWithoutThrowing() {
        // 一个坏 chunk（非法 JSON）+ 一个正常 chunk：整体不抛，正常数据仍可查询
        ExcelSheetChunk bad = new ExcelSheetChunk(); bad.setId(1L); bad.setCelldataJson("not, json{,break");
        ExcelSheetChunk good = new ExcelSheetChunk(); good.setId(2L);
        good.setCelldataJson("[{\"r\":0,\"c\":0,\"v\":\"x\"}]");

        CellDataTable t = CellDataTable.fromChunks(List.of(bad, good));
        assertEquals("x", CellDataTable.valueAsText(t.valueAt(0, 0)));
    }
}