package com.argus.rag.excel.pipeline;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.common.util.NumericTexts;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 表格 Schema 自动识别服务。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>从 celldata 数据中自动推断列名、数据类型、主键/外键关系</li>
 *   <li>将 Excel 的行列式数据转换为结构化 Schema 描述</li>
 *   <li>支持文本到 SQL 的 NL 查询转换的 Schema 上下文构建</li>
 * </ul>
 * <p>
 * 设计思路：80% 的企业数据存储在 Excel/CSV 中，这些数据缺乏 Schema 描述。
 * 此服务自动补全这部分信息，使 AI 能够理解表格数据的语义。
 *
 * @see com.argus.rag.excel.service.ExcelParserService
 */
@Service
public class TableSchemaService {

    private static final Logger log = LoggerFactory.getLogger(TableSchemaService.class);

    /** 第一行默认为列名（header） */
    private static final int DEFAULT_HEADER_ROW = 0;

    /**
     * 从 Chunk 数据中提取 Schema 描述。
     *
     * @param chunks  一个 Sheet 的所有数据分块
     * @param sheet   Sheet 元信息
     * @return Schema 描述 JSON，包含列名、类型、统计信息、示例值
     */
    public Map<String, Object> extractSchema(List<ExcelSheetChunk> chunks, ExcelSheet sheet) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("sheetName", sheet.getSheetName());
        schema.put("totalRows", sheet.getTotalRows());
        schema.put("totalCols", sheet.getTotalCols());

        // 合并所有 chunk 的 celldata
        List<Map<String, Object>> allCells = mergeAllCells(chunks);
        if (allCells.isEmpty()) {
            schema.put("columns", new ArrayList<>());
            return schema;
        }

        // 确定最大行列
        int maxRow = sheet.getTotalRows();
        int maxCol = sheet.getTotalCols();

        // 构建二维矩阵以便列分析
        Map<Integer, Map<Integer, Object>> cellMatrix = buildCellMatrix(allCells);

        // 提取列 Schema
        List<Object> columns = new ArrayList<>();
        for (int col = 0; col < maxCol; col++) {
            Map<String, Object> colSchema = buildColumnSchema(cellMatrix, col, maxRow);
            columns.add(colSchema);
        }

        schema.put("columns", columns);
        log.debug("Schema 提取完成: sheet={}, columns={}", sheet.getSheetName(), columns.size());
        return schema;
    }

    /**
     * 为 LLM 构建可读的 Schema 上下文描述文本。
     * 用于注入到 RAG 检索上下文和 Agent Prompt 中。
     */
    public String buildSchemaContext(List<ExcelSheetChunk> chunks, ExcelSheet sheet) {
        Map<String, Object> schema = extractSchema(chunks, sheet);
        StringBuilder sb = new StringBuilder();
        sb.append("# 表格 Schema: ").append(sheet.getSheetName()).append("\n");
        sb.append("行数: ").append(sheet.getTotalRows()).append("\n");
        sb.append("列数: ").append(sheet.getTotalCols()).append("\n\n");

        List<Object> columns = JsonCompat.getJSONArray(schema, "columns");
        if (columns != null) {
            sb.append("| 列索引 | 列名 | 推断类型 |\n");
            sb.append("|--------|------|----------|\n");
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = (Map<String, Object>) columns.get(i);
                sb.append("| ").append(i)
                  .append(" | ").append(JsonCompat.getString(col, "name"))
                  .append(" | ").append(JsonCompat.getString(col, "inferredType"))
                  .append(" |\n");
            }
        }

        return sb.toString();
    }

    /**
     * 为多个 Sheet 构建联邦 Schema（检测表间关联关系）。
     * 用于跨表查询场景，发现不同 Sheet 之间的潜在 JOIN 关系。
     */
    public Map<String, Object> buildFederatedSchema(Map<ExcelSheet, List<ExcelSheetChunk>> sheetChunksMap,
                                           ExcelDocument document) {
        Map<String, Object> federated = new LinkedHashMap<>();
        federated.put("documentName", document.getName());

        List<Object> tables = new ArrayList<>();
        for (Map.Entry<ExcelSheet, List<ExcelSheetChunk>> entry : sheetChunksMap.entrySet()) {
            Map<String, Object> tableSchema = extractSchema(entry.getValue(), entry.getKey());
            tables.add(tableSchema);
        }
        federated.put("tables", tables);

        // 检测表间键关系（如果不同 Sheet 有同名列，标记为潜在关联）
        List<Object> relationships = detectTableRelationships(tables);
        federated.put("relationships", relationships);

        return federated;
    }

    // ── 私有辅助方法 ──

    private List<Map<String, Object>> mergeAllCells(List<ExcelSheetChunk> chunks) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (ExcelSheetChunk chunk : chunks) {
            try {
                List<Object> cells = JsonCompat.parseArray(chunk.getCelldataJson());
                if (cells != null) {
                    for (int i = 0; i < cells.size(); i++) {
                        Object item = cells.get(i);
                        if (item instanceof Map) {
                            all.add((Map<String, Object>) item);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Chunk[{}] 解析失败: {}", chunk.getId(), e.getMessage());
            }
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Map<Integer, Object>> buildCellMatrix(List<Map<String, Object>> cells) {
        Map<Integer, Map<Integer, Object>> matrix = new HashMap<>();
        for (Map<String, Object> cell : cells) {
            int r = ((Number) cell.get("r")).intValue();
            int c = ((Number) cell.get("c")).intValue();
            matrix.computeIfAbsent(r, k -> new HashMap<>()).put(c, cell.get("v"));
        }
        return matrix;
    }

    private Map<String, Object> buildColumnSchema(Map<Integer, Map<Integer, Object>> matrix,
                                          int col, int maxRow) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("columnIndex", col);

        // 收集该列的所有非空值
        List<Object> values = new ArrayList<>();
        for (int r = 0; r < Math.min(maxRow, 500); r++) {  // 采样前500行
            Map<Integer, Object> row = matrix.get(r);
            if (row != null) {
                Object raw = row.get(col);
                if (raw != null) {
                    values.add(raw);
                }
            }
        }

        // 推断列名：取第一行作为列名
        String colName = "column_" + col;
        Map<Integer, Object> headerRow = matrix.get(DEFAULT_HEADER_ROW);
        if (headerRow != null && headerRow.get(col) != null) {
            Object headerVal = headerRow.get(col);
            colName = extractValueAsString(headerVal);
        }
        schema.put("name", colName);

        // 推断数据类型
        String inferredType = inferColumnType(values, colName);
        schema.put("inferredType", inferredType);

        // 统计信息
        schema.put("nonNullCount", values.size());
        schema.put("distinctCount", values.stream().map(Object::toString).distinct().count());

        // 前3个示例值
        List<Object> examples = new ArrayList<>();
        for (int i = 1; i < Math.min(values.size(), 4); i++) {  // 跳过 header 行
            examples.add(extractValueAsString(values.get(i)));
        }
        schema.put("examples", examples);

        return schema;
    }

    /** 从一列样本值推断该列的字段类型：对前 50 个值做类型计数，按多数派判定为 number/date/boolean/text，空列回退 unknown。 */
    private String inferColumnType(List<Object> values, String colName) {
        if (values.isEmpty()) return "unknown";

        // 统计各类型的命中次数（采样前50个值）
        int numericCount = 0, dateCount = 0, booleanCount = 0, stringCount = 0;
        int sampleSize = Math.min(values.size(), 50);

        for (int i = 0; i < sampleSize; i++) {
            String str = extractValueAsString(values.get(i));
            if (str == null || str.isEmpty()) continue;

            if (isNumeric(str)) numericCount++;
            else if (isDate(str)) dateCount++;
            else if (isBoolean(str)) booleanCount++;
            else stringCount++;
        }

        // 按多数派判断
        int max = Math.max(Math.max(numericCount, dateCount),
                  Math.max(booleanCount, stringCount));
        if (max == numericCount) return "number";
        if (max == dateCount) return "date";
        if (max == booleanCount) return "boolean";
        return "text";
    }

    private boolean isNumeric(String str) {
        // 唯一调用点已跳空（inferColumnType line 209 先判 null/空），故 parseOrNull!=null 与旧实现等价
        return NumericTexts.parseOrNull(str) != null;
    }

    private boolean isDate(String str) {
        return str.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}") ||
               str.matches("\\d{1,2}[-/]\\d{1,2}[-/]\\d{4}");
    }

    private boolean isBoolean(String str) {
        return "true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str) ||
               "是".equals(str) || "否".equals(str) || "yes".equalsIgnoreCase(str) || "no".equalsIgnoreCase(str);
    }

    private String extractValueAsString(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map) {
            Map<?, ?> v = (Map<?, ?>) raw;
            Object val = v.get("v");
            return val != null ? val.toString() : JsonCompat.getString(v, "m");
        }
        return raw.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Object> detectTableRelationships(List<Object> tables) {
        List<Object> relations = new ArrayList<>();
        if (tables.size() < 2) return relations;

        // 简单地：如果两个表有同名列（且不是 header 行中的通用列名），标记为潜在关联
        Set<String> genericNames = Set.of("id", "序号", "备注", "note", "notes", "description", "说明");
        for (int i = 0; i < tables.size(); i++) {
            for (int j = i + 1; j < tables.size(); j++) {
                Map<String, Object> t1 = (Map<String, Object>) tables.get(i);
                Map<String, Object> t2 = (Map<String, Object>) tables.get(j);
                List<Object> cols1 = JsonCompat.getJSONArray(t1, "columns");
                List<Object> cols2 = JsonCompat.getJSONArray(t2, "columns");
                if (cols1.isEmpty() || cols2.isEmpty()) continue;

                for (int ci = 0; ci < cols1.size(); ci++) {
                    String name1 = JsonCompat.getString((Map<String, Object>) cols1.get(ci), "name");
                    if (genericNames.contains(name1)) continue;
                    for (int cj = 0; cj < cols2.size(); cj++) {
                        String name2 = JsonCompat.getString((Map<String, Object>) cols2.get(cj), "name");
                        if (name1.equals(name2)) {
                            Map<String, Object> rel = new LinkedHashMap<>();
                            rel.put("leftTable", JsonCompat.getString(t1, "sheetName"));
                            rel.put("rightTable", JsonCompat.getString(t2, "sheetName"));
                            rel.put("onColumn", name1);
                            relations.add(rel);
                        }
                    }
                }
            }
        }
        return relations;
    }
}
