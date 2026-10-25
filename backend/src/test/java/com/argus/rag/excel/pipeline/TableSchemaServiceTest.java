package com.argus.rag.excel.pipeline;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TableSchemaService} 表格 Schema 识别契约（纯 JSON 逻辑，零 mapper/DB/网络）：
 * - extractSchema：空 chunk → 空 columns + 基本信息；列名取 header(行0)、缺省 column_<c>；
 *   类型推断 number/date/boolean/text/unknown（多数派，含 header 值）；nonNullCount/distinctCount；
 *   examples 跳过 header 取前3；distinctCount 按 toString 去重；
 * - buildSchemaContext：Markdown 表头渲染 | 列索引 | 列名 | 推断类型 |；
 * - buildFederatedSchema：documentName + tables 数组；单表无关系；同名列且非通用名 → 关系。
 */
@DisplayName("表格 Schema 自动识别（TableSchemaService）")
class TableSchemaServiceTest {

    private final TableSchemaService svc = new TableSchemaService();

    private static ExcelSheetChunk chunk(Long id, String celldataJson) {
        ExcelSheetChunk c = new ExcelSheetChunk();
        c.setId(id);
        c.setCelldataJson(celldataJson);
        return c;
    }

    private static ExcelSheet sheet(String name, int rows, int cols) {
        ExcelSheet s = new ExcelSheet();
        s.setSheetName(name);
        s.setTotalRows(rows);
        s.setTotalCols(cols);
        return s;
    }

    private static ExcelDocument doc(String name) {
        ExcelDocument d = new ExcelDocument();
        d.setName(name);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> colOf(Map<String, Object> schema, int i) {
        return (Map<String, Object>) JsonCompat.getJSONArray(schema, "columns").get(i);
    }

    // ---------- extractSchema ----------

    @Test
    void emptyChunksYieldEmptyColumnsButInfo() {
        Map<String, Object> schema = svc.extractSchema(List.of(), sheet("S1", 5, 3));
        assertEquals("S1", JsonCompat.getString(schema, "sheetName"));
        assertEquals(5, ((Number) schema.get("totalRows")).intValue());
        assertEquals(3, ((Number) schema.get("totalCols")).intValue());
        assertEquals(0, JsonCompat.getJSONArray(schema, "columns").size());
    }

    @Test
    void numericColumnInfersNameTypeAndExamples() {
        // header「金额」(行0) 计入值；数据 100/200 → number
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"金额\",\"m\":\"金额\"}}," +
                 "{\"r\":1,\"c\":0,\"v\":{\"v\":100,\"m\":\"100\"}}," +
                 "{\"r\":2,\"c\":0,\"v\":{\"v\":200,\"m\":\"200\"}}]"));
        Map<String, Object> schema = svc.extractSchema(chunks, sheet("金额表", 3, 1));

        Map<String, Object> col = colOf(schema, 0);
        assertEquals("金额", JsonCompat.getString(col, "name"));
        assertEquals("number", JsonCompat.getString(col, "inferredType"));
        assertEquals(3, ((Number) col.get("nonNullCount")).intValue());
        assertEquals(3, ((Number) col.get("distinctCount")).intValue());
        // examples 跳过 header（行0），取行1~2
        List<Object> ex = JsonCompat.getJSONArray(col, "examples");
        assertEquals(2, ex.size());
        assertEquals("100", ex.get(0).toString());
        assertEquals("200", ex.get(1).toString());
    }

    @Test
    void emptyColumnFallsBackGenericNameAndUnknownType() {
        // 数据只有 col0，无 col1 → col1 无值 → unknown + column_1
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"名称\"}}," +
                 "{\"r\":1,\"c\":0,\"v\":{\"v\":\"a\"}}," +
                 "{\"r\":2,\"c\":0,\"v\":{\"v\":\"b\"}}]"));
        Map<String, Object> schema = svc.extractSchema(chunks, sheet("S", 3, 2));
        Map<String, Object> col1 = colOf(schema, 1);
        assertEquals("column_1", JsonCompat.getString(col1, "name"));
        assertEquals("unknown", JsonCompat.getString(col1, "inferredType"));
        assertEquals(0, ((Number) col1.get("nonNullCount")).intValue());
        assertEquals(0, JsonCompat.getJSONArray(col1, "examples").size());
    }

    @Test
    void infersTextTypeFromStrings() {
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"部门\"}}," +
                 "{\"r\":1,\"c\":0,\"v\":{\"v\":\"一部\"}}," +
                 "{\"r\":2,\"c\":0,\"v\":{\"v\":\"二部\"}}]"));
        Map<String, Object> schema = svc.extractSchema(chunks, sheet("T", 3, 1));
        assertEquals("text", JsonCompat.getString(colOf(schema, 0), "inferredType"));
    }

    @Test
    void infersDateType() {
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"日期\"}}," +
                 "{\"r\":1,\"c\":0,\"v\":{\"v\":\"2024-01-15\"}}," +
                 "{\"r\":2,\"c\":0,\"v\":{\"v\":\"2024/12/31\"}}]"));
        Map<String, Object> schema = svc.extractSchema(chunks, sheet("D", 3, 1));
        assertEquals("date", JsonCompat.getString(colOf(schema, 0), "inferredType"));
    }

    @Test
    void infersBooleanType() {
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"完成\"}}," +
                 "{\"r\":1,\"c\":0,\"v\":{\"v\":\"是\"}}," +
                 "{\"r\":2,\"c\":0,\"v\":{\"v\":\"否\"}}]"));
        Map<String, Object> schema = svc.extractSchema(chunks, sheet("B", 3, 1));
        assertEquals("boolean", JsonCompat.getString(colOf(schema, 0), "inferredType"));
    }

    @Test
    void distinctCountDedupesEqualValues() {
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":1,\"v\":{\"v\":\"状态\"}},{\"r\":1,\"c\":1,\"v\":{\"v\":\"新\"}},{\"r\":2,\"c\":1,\"v\":{\"v\":\"新\"}}]"));
        Map<String, Object> schema = svc.extractSchema(chunks, sheet("D", 3, 2));
        Map<String, Object> col = colOf(schema, 1);
        assertEquals(3, ((Number) col.get("nonNullCount")).intValue());   // 状态、新、新
        assertEquals(2, ((Number) col.get("distinctCount")).intValue());  // 状态、新（新 去重）
    }

    // ---------- buildSchemaContext ----------

    @Test
    void buildSchemaContextRendersTableHeader() {
        List<ExcelSheetChunk> chunks = List.of(chunk(1L,
                "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"金额\"}},{\"r\":1,\"c\":0,\"v\":{\"v\":100}}]"));
        String ctx = svc.buildSchemaContext(chunks, sheet("测试表", 2, 1));
        assertTrue(ctx.contains("# 表格 Schema: 测试表"));
        assertTrue(ctx.contains("行数: 2"));
        assertTrue(ctx.contains("列数: 1"));
        assertTrue(ctx.contains("| 0 | 金额 | number |"));
    }

    // ---------- buildFederatedSchema ----------

    @Test
    void federatedSchemaSingleTableNoRelationships() {
        Map<ExcelSheet, List<ExcelSheetChunk>> map = Map.of(
                sheet("A", 1, 1), List.of(chunk(1L, "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"x\"}}]")));
        Map<String, Object> fed = svc.buildFederatedSchema(map, doc("Doc"));
        assertEquals("Doc", JsonCompat.getString(fed, "documentName"));
        assertEquals(1, JsonCompat.getJSONArray(fed, "tables").size());
        assertEquals(0, JsonCompat.getJSONArray(fed, "relationships").size());
    }

    @Test
    void federatedSchemaDetectsSharedNonGenericColumn() {
        // 两表共享「订单号」→ 一条关系；「id」属通用列名不参与
        Map<ExcelSheet, List<ExcelSheetChunk>> map = Map.of(
                sheet("订单表", 2, 2), List.of(chunk(1L,
                        "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"订单号\"}},{\"r\":0,\"c\":1,\"v\":{\"v\":\"id\"}},{\"r\":1,\"c\":0,\"v\":{\"v\":\"A1\"}}]")),
                sheet("明细表", 2, 1), List.of(chunk(2L,
                        "[{\"r\":0,\"c\":0,\"v\":{\"v\":\"订单号\"}},{\"r\":1,\"c\":0,\"v\":{\"v\":\"A1\"}}]")));

        Map<String, Object> fed = svc.buildFederatedSchema(map, doc("K"));
        List<Object> rel = JsonCompat.getJSONArray(fed, "relationships");
        assertEquals(1, rel.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) rel.get(0);
        assertEquals("订单号", JsonCompat.getString(r, "onColumn"));
        // Map 迭代顺序不保证 left/right 次序
        String l = JsonCompat.getString(r, "leftTable");
        String rt = JsonCompat.getString(r, "rightTable");
        assertTrue((l.equals("订单表") && rt.equals("明细表")) || (l.equals("明细表") && rt.equals("订单表")));
    }
}
