package com.argus.rag.engine;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.pipeline.TableIngestionService;
import com.argus.rag.excel.pipeline.TableSchemaService;
import com.argus.rag.excel.service.ExcelSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StructuredDataRetrievalService} 结构化数据检索契约（mock 表单/编译/入库服务，
 * 真实 JSON 序列化，纯 JVM/零 DB/零 LLM）：
 * - buildStructuredContext：无表→空、无切片表被跳过、schema 无列被跳过；非空则渲染
 *   Markdown 表格描述（行数、列名/类型/示例，无示例→N/A），末端附「提示」；
 * - retrieveRelevantData：无表→空、无切片表被跳过、成功回填 sourceSheetId、单表查询异常被吞
 *   不影响其余表，queryIntent 与 maxRows 透传给入库服务；
 * - fuzzySearch：以 contains 意图 + value + 每表最多 20 行走向 retrieveRelevantData。
 */
@DisplayName("结构化数据检索（StructuredDataRetrievalService）")
class StructuredDataRetrievalServiceTest {

    private ExcelSheetService sheetService;
    private TableSchemaService schemaService;
    private TableIngestionService ingestionService;
    private StructuredDataRetrievalService service;

    @BeforeEach
    void setUp() {
        sheetService = mock(ExcelSheetService.class);
        schemaService = mock(TableSchemaService.class);
        ingestionService = mock(TableIngestionService.class);
        service = new StructuredDataRetrievalService(sheetService, schemaService, ingestionService);
    }

    // ---------- helpers ----------

    private static ExcelSheet sheet(Long id, String name, int rows) {
        ExcelSheet s = new ExcelSheet();
        s.setId(id);
        s.setSheetName(name);
        s.setTotalRows(rows);
        return s;
    }

    private static ExcelSheetChunk chunk(Long sheetId) {
        ExcelSheetChunk c = new ExcelSheetChunk();
        c.setSheetId(sheetId);
        return c;
    }

    private static Map<String, Object> schemaWithColumn(String name, String type, List<String> examples) {
        List<Object> columns = new ArrayList<>();
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("name", name);
        col.put("inferredType", type);
        List<Object> ex = new ArrayList<>();
        if (examples != null) {
            examples.forEach(ex::add);
        }
        col.put("examples", ex);
        columns.add(col);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("columns", columns);
        return schema;
    }

    private void stubSheets(ExcelSheet... sheets) {
        when(sheetService.listSheetsByDocumentId(7L)).thenReturn(List.of(sheets));
    }

    // ---------- buildStructuredContext ----------

    @Test
    void structuredContextIsEmptyWhenNoSheets() {
        when(sheetService.listSheetsByDocumentId(7L)).thenReturn(List.of());
        assertEquals("", service.buildStructuredContext(7L));
    }

    @Test
    void structuredContextSkipsSheetWithoutChunks() {
        stubSheets(sheet(1L, "S1", 5));
        when(sheetService.listChunksBySheetId(1L)).thenReturn(List.of());
        // 表存在但无切片 → 不渲染该 Sheet，但仍输出头部与提示（不短路为空）
        String ctx = service.buildStructuredContext(7L);
        assertTrue(ctx.contains("## 可用的结构化数据表格"));
        assertTrue(ctx.contains("**提示**"));
        assertTrue(!ctx.contains("### S1") || !ctx.contains("- 列结构:"));
    }

    @Test
    void structuredContextSkipsSheetWithoutColumnsInSchema() {
        stubSheets(sheet(1L, "S1", 5));
        when(sheetService.listChunksBySheetId(1L)).thenReturn(List.of(chunk(1L)));
        when(schemaService.extractSchema(anyList(), any(ExcelSheet.class)))
                .thenReturn(new LinkedHashMap<>()); // 无 columns
        String ctx = service.buildStructuredContext(7L);
        assertTrue(ctx.contains("## 可用的结构化数据表格"));
        assertTrue(ctx.contains("**提示**"));
        assertTrue(!ctx.contains("### S1"));
    }

    @Test
    void structuredContextRendersHeadingRowsAndColumnsUsingExample() {
        ExcelSheet s1 = sheet(1L, "销售表", 120);
        stubSheets(s1);
        when(sheetService.listChunksBySheetId(1L)).thenReturn(List.of(chunk(1L)));
        when(schemaService.extractSchema(anyList(), same(s1))).thenReturn(schemaWithColumn(
                "金额", "number", List.of("100")));

        String ctx = service.buildStructuredContext(7L);
        assertTrue(ctx.contains("## 可用的结构化数据表格"));
        assertTrue(ctx.contains("### 销售表"));
        assertTrue(ctx.contains("- 行数: 120"));
        assertTrue(ctx.contains("  - `金额` (number), 示例: 100"));
        assertTrue(ctx.contains("---\n"));
        assertTrue(ctx.contains("**提示**: 如果用户询问数据分析"));
    }

    @Test
    void structuredContextRendersNafWhenNoExample() {
        ExcelSheet s1 = sheet(1L, "S1", 5);
        stubSheets(s1);
        when(sheetService.listChunksBySheetId(1L)).thenReturn(List.of(chunk(1L)));
        when(schemaService.extractSchema(anyList(), same(s1)))
                .thenReturn(schemaWithColumn("日期", "string", List.of()));
        String ctx = service.buildStructuredContext(7L);
        // 无示例 → 兜底 N/A
        assertTrue(ctx.contains("  - `日期` (string), 示例: N/A"));
    }

    // ---------- retrieveRelevantData + fuzzySearch ----------

    @Test
    void retrieveRelevantDataReturnsEmptyWhenNoSheets() {
        when(sheetService.listSheetsByDocumentId(7L)).thenReturn(List.of());
        assertTrue(service.retrieveRelevantData(7L, new LinkedHashMap<>(), 5).isEmpty());
    }

    @Test
    void retrieveRelevantDataSkipsSheetWithoutChunks() {
        stubSheets(sheet(1L, "S", 5));
        when(sheetService.listChunksBySheetId(1L)).thenReturn(List.of());
        assertTrue(service.retrieveRelevantData(7L, new LinkedHashMap<>(), 5).isEmpty());
    }

    @Test
    void retrieveRelevantDataStampsSourceSheetId() {
        ExcelSheet s = sheet(9L, "S", 5);
        stubSheets(s);
        when(sheetService.listChunksBySheetId(9L)).thenReturn(List.of(chunk(9L)));
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("rows", new ArrayList<>());
        when(ingestionService.queryStructuredData(same(s), anyList(), any(Map.class), eq(3)))
                .thenReturn(rows);

        List<Map<String, Object>> resultsWithStamper = service.retrieveRelevantData(7L, new LinkedHashMap<>(), 3);

        assertEquals(1, resultsWithStamper.size());
        assertEquals(9L, ((Number) resultsWithStamper.get(0).get("sourceSheetId")).longValue());
        verify(ingestionService).queryStructuredData(same(s), anyList(), any(Map.class), eq(3));
    }

    @Test
    void retrieveRelevantDataSwallowsPerSheetQueryError() {
        ExcelSheet bad = sheet(1L, "Bad", 5);
        ExcelSheet good = sheet(2L, "Good", 8);
        stubSheets(bad, good);
        when(sheetService.listChunksBySheetId(1L)).thenReturn(List.of(chunk(1L)));
        when(sheetService.listChunksBySheetId(2L)).thenReturn(List.of(chunk(2L)));
        when(ingestionService.queryStructuredData(same(bad), anyList(), any(Map.class), anyInt()))
                .thenThrow(new IllegalArgumentException("schema 解析失败"));
        when(ingestionService.queryStructuredData(same(good), anyList(), any(Map.class), anyInt()))
                .thenReturn(new LinkedHashMap<>());

        List<Map<String, Object>> resultsWithStamper = service.retrieveRelevantData(7L, new LinkedHashMap<>(), 3);

        assertEquals(1, resultsWithStamper.size());
        assertEquals(2L, ((Number) resultsWithStamper.get(0).get("sourceSheetId")).longValue());
    }

    @Test
    void fuzzySearchBuildsContainsIntentAndClampsToTwenty() {
        ExcelSheet s = sheet(5L, "S", 5);
        stubSheets(s);
        when(sheetService.listChunksBySheetId(5L)).thenReturn(List.of(chunk(5L)));
        when(ingestionService.queryStructuredData(any(), any(), any(Map.class), anyInt()))
                .thenReturn(new LinkedHashMap<>());

        List<Map<String, Object>> resultsWithStamper = service.fuzzySearch(7L, "华为");

        assertEquals(1, resultsWithStamper.size());
        verify(ingestionService).queryStructuredData(any(), anyList(), argThat(query ->
                        "contains".equals(JsonCompat.getString((Map<String, Object>) query, "operator"))
                                && "华为".equals(JsonCompat.getString((Map<String, Object>) query, "value"))),
                eq(20));
    }
}
