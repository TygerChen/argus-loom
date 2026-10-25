package com.argus.rag.excel.pipeline;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.pipeline.celldata.CellDataTable;
import com.argus.rag.excel.pipeline.filter.ColumnFilterStrategy;
import com.argus.rag.excel.service.ExcelSheetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 结构化数据摄入与检索服务。
 * <p>
 * 将 Excel 表格数据转换为 RAG 系统可消费的结构化知识。
 * 核心流程：
 * <ol>
 *   <li>扫描 Sheet → 提取 Schema</li>
 *   <li>构建自然语言描述（用于向量检索）</li>
 *   <li>支持自然语言查询转换为单个或多个 Sheet 的数据过滤</li>
 *   <li>生成 Agent 可用的结构化查询结果</li>
 * </ol>
 * <p>
 * 与传统的非结构化 RAG（PDF 文本检索）不同，此服务处理的是
 * <b>结构化表格数据</b>——这是现有 RAG 平台的最大盲区。
 *
 * @see TableSchemaService
 * @see ExcelSheetService
 */
@Service
public class TableIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TableIngestionService.class);

    private final ExcelSheetService sheetService;
    private final TableSchemaService schemaService;

    public TableIngestionService(ExcelSheetService sheetService,
                                  TableSchemaService schemaService) {
        this.sheetService = sheetService;
        this.schemaService = schemaService;
    }

    /**
     * 为指定文档构建结构化知识摘要。
     * 返回的是一个 Markdown 格式的自然语言描述，可以直接作为 RAG 的文本块嵌入。
     *
     * @param documentId 文档 ID
     * @return RAG-ready 结构化知识文本
     */
    public String buildStructuredKnowledgeSummary(Long documentId) {
        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(documentId);
        if (sheets.isEmpty()) return "";

        // P2-07：一次批量取回全文档 chunk（原实现每个 Sheet 查一次，N+1）
        Map<Long, List<ExcelSheetChunk>> chunksBySheet =
                sheetService.listChunksByDocumentGrouped(documentId);

        StringBuilder sb = new StringBuilder();
        sb.append("# 文档结构化数据摘要\n\n");

        for (ExcelSheet sheet : sheets) {
            List<ExcelSheetChunk> chunks = chunksBySheet.getOrDefault(sheet.getId(), List.of());
            String schemaCtx = schemaService.buildSchemaContext(chunks, sheet);

            // 额外添加前5行的示例数据
            sb.append(schemaCtx).append("\n");
            sb.append("### 数据样例（首5行）\n");
            sb.append(buildSampleData(CellDataTable.fromChunks(chunks), 5)).append("\n\n");
            sb.append("---\n\n");
        }

        return sb.toString();
    }

    /**
     * 按自然语言查询条件过滤表格数据。
     * <p>
     * 支持简单条件：
     * <ul>
     *   <li>列名 = 值</li>
     *   <li>列名 包含 值</li>
     *   <li>列名 > / < 数值</li>
     * </ul>
     *
     * @param sheet       Sheet 元信息
     * @param chunks      数据分块
     * @param filterJson  过滤条件 JSON
     *                    {"column": "列名或列索引", "operator": "eq/contains/gt/lt", "value": "目标值"}
     * @param limit       返回行数上限
     * @return 匹配的数据行
     */
    public Map<String, Object> queryStructuredData(ExcelSheet sheet, List<ExcelSheetChunk> chunks,
                                           Map<String, Object> filterJson, int limit) {
        String column = JsonCompat.getString(filterJson, "column");
        String operator = JsonCompat.getString(filterJson, "operator");
        String targetValue = JsonCompat.getString(filterJson, "value");
        int maxRows = Math.min(limit > 0 ? limit : 100, 500);

        // 获取 Schema 以解析列索引
        Map<String, Object> schema = schemaService.extractSchema(chunks, sheet);
        Integer colIndex = resolveColumnIndex(schema, column);
        if (colIndex == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "找不到列: " + column);
            return error;
        }

        // 扁平化 celldata 为矩阵
        CellDataTable table = CellDataTable.fromChunks(chunks);
        String colName = getColumnName(schema, colIndex);

        // 执行过滤
        List<Object> matchingRows = new ArrayList<>();
        for (int r = 1; r < sheet.getTotalRows() && matchingRows.size() < maxRows; r++) {
            if (!table.hasRow(r)) continue;

            Object cellVal = table.valueAt(r, colIndex);
            if (cellVal == null) continue;

            String cellStr = CellDataTable.valueAsText(cellVal);
            if (ColumnFilterStrategy.from(operator).matches(cellStr, targetValue)) {
                Map<String, Object> rowResult = buildRowResult(table, schema, r);
                matchingRows.add(rowResult);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheetName", sheet.getSheetName());
        result.put("column", colName);
        result.put("filter", filterJson);
        result.put("matchCount", matchingRows.size());
        result.put("rows", matchingRows);
        result.put("schema", schema);
        return result;
    }

    // ── 私有辅助方法 ──

    private Integer resolveColumnIndex(Map<String, Object> schema, String columnRef) {
        // 先尝试按索引解析
        try { return Integer.parseInt(columnRef); } catch (NumberFormatException e) { /* not a number */ }

        // 按列名匹配
        List<Object> columns = JsonCompat.getJSONArray(schema, "columns");
        if (!columns.isEmpty()) {
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = (Map<String, Object>) columns.get(i);
                if (columnRef.equals(JsonCompat.getString(col, "name"))) {
                    Object idx = col.get("columnIndex");
                    return idx instanceof Number ? ((Number) idx).intValue() : null;
                }
            }
        }
        return null;
    }

    private String getColumnName(Map<String, Object> schema, Integer colIndex) {
        List<Object> columns = JsonCompat.getJSONArray(schema, "columns");
        if (!columns.isEmpty()) {
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = (Map<String, Object>) columns.get(i);
                Object idx = col.get("columnIndex");
                if (idx instanceof Number && colIndex.equals(((Number) idx).intValue())) {
                    return JsonCompat.getString(col, "name");
                }
            }
        }
        return "column_" + colIndex;
    }

    private Map<String, Object> buildRowResult(CellDataTable table, Map<String, Object> schema, int rowIndex) {
        Map<String, Object> rowResult = new LinkedHashMap<>();
        rowResult.put("rowIndex", rowIndex);

        List<Object> columns = JsonCompat.getJSONArray(schema, "columns");
        if (!columns.isEmpty()) {
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = (Map<String, Object>) columns.get(i);
                Object colIdx = col.get("columnIndex");
                rowResult.put(JsonCompat.getString(col, "name"),
                        CellDataTable.valueAsText(table.valueAt(rowIndex, colIdx instanceof Number ? ((Number) colIdx).intValue() : 0)));
            }
        }
        return rowResult;
    }

    private String buildSampleData(CellDataTable table, int maxRows) {
        StringBuilder sb = new StringBuilder("```\n");
        int displayCount = 0;
        for (int r = 0; r <= table.maxRow() && displayCount < maxRows + 1; r++) {
            if (!table.hasRow(r)) continue;
            displayCount++;
            if (displayCount == 1) continue; // skip header in sample

            Map<Integer, Object> row = table.rowAt(r);
            int maxCol = row.keySet().stream().max(Integer::compareTo).orElse(0);
            for (int c = 0; c <= maxCol; c++) {
                sb.append(CellDataTable.valueAsText(row.get(c))).append(c < maxCol ? " | " : "");
            }
            sb.append("\n");
        }
        sb.append("```");
        return sb.toString();
    }
}
