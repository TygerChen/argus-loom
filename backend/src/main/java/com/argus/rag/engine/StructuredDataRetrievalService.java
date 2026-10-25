package com.argus.rag.engine;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.pipeline.TableIngestionService;
import com.argus.rag.excel.pipeline.TableSchemaService;
import com.argus.rag.excel.service.ExcelSheetService;
import com.argus.rag.qa.rag.HybridChunkRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL-aware 结构化数据检索服务。
 * <p>
 * 核心创新：在现有文本块检索（HybridChunkRetrievalService）基础上，
 * 增加对结构化表格数据的感知能力。
 * <p>
 * 工作流程：
 * <ol>
 *   <li>用户提问 → LLM 判断是否涉及结构化数据</li>
 *   <li>如果是表格查询 → 获取各 Sheet 的 Schema 描述</li>
 *   <li>将 Schema 注入检索上下文 → 告知 LLM 有哪些列、什么类型</li>
 *   <li>LLM 生成过滤条件 → 执行结构化查询 → 返回精确数据行</li>
 * </ol>
 * <p>
 * 与纯文本检索的本质区别：文本检索返回"语义相似的段落"，
 * 结构化检索返回"精确匹配的数据行"——这正是企业数据分析场景的核心需求。
 *
 * @see HybridChunkRetrievalService
 * @see TableSchemaService
 */
@Service
public class StructuredDataRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(StructuredDataRetrievalService.class);

    private final ExcelSheetService sheetService;
    private final TableSchemaService schemaService;
    private final TableIngestionService ingestionService;

    public StructuredDataRetrievalService(ExcelSheetService sheetService,
                                           TableSchemaService schemaService,
                                           TableIngestionService ingestionService) {
        this.sheetService = sheetService;
        this.schemaService = schemaService;
        this.ingestionService = ingestionService;
    }

    /**
     * 为 RAG 检索构建结构化数据上下文。
     * <p>
     * 这个方法的输出直接注入到 QA 检索上下文（RAG context prompt）中，
     * 使 LLM 能够理解表格数据的结构并生成精确的数据查询。
     *
     * @param documentId 用户查询时选择的文档 ID
     * @return 结构化上下文描述（Markdown 格式）
     */
    public String buildStructuredContext(Long documentId) {
        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(documentId);
        if (sheets.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder();
        ctx.append("\n## 可用的结构化数据表格\n\n");

        for (ExcelSheet sheet : sheets) {
            List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheet.getId());
            if (chunks.isEmpty()) continue;

            Map<String, Object> schema = schemaService.extractSchema(chunks, sheet);
            List<Object> columns = JsonCompat.getJSONArray(schema, "columns");
            if (columns == null || columns.isEmpty()) continue;

            ctx.append("### ").append(sheet.getSheetName()).append("\n");
            ctx.append("- 行数: ").append(sheet.getTotalRows()).append("\n");
            ctx.append("- 列结构:\n");

            for (int i = 0; i < columns.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> col = (Map<String, Object>) columns.get(i);
                List<Object> examples = JsonCompat.getJSONArray(col, "examples");
                ctx.append("  - `")
                   .append(JsonCompat.getString(col, "name"))
                   .append("` (")
                   .append(JsonCompat.getString(col, "inferredType"))
                   .append("), 示例: ")
                   .append(examples != null && !examples.isEmpty()
                           ? examples.get(0) : "N/A")
                   .append("\n");
            }
            ctx.append("\n");
        }

        ctx.append("---\n");
        ctx.append("**提示**: 如果用户询问数据分析、统计、汇总等问题，请基于以上 Schema 生成对应的过滤/聚合条件，");
        ctx.append("使用 `query_structured_data` 工具获取精确数据。\n");

        return ctx.toString();
    }

    /**
     * 检索相关表格数据。
     * <p>
     * 根据查询意图在多个 Sheet 中搜索匹配的数据行。
     *
     * @param documentId 文档 ID
     * @param queryIntent 查询意图（LLM 解析出的 {column, operator, value}）
     * @param maxResults  每表最大返回行数
     * @return 检索结果
     */
    public List<Map<String, Object>> retrieveRelevantData(Long documentId,
                                                   Map<String, Object> queryIntent,
                                                   int maxResults) {
        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(documentId);
        List<Map<String, Object>> results = new ArrayList<>();

        for (ExcelSheet sheet : sheets) {
            try {
                List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheet.getId());
                if (chunks.isEmpty()) continue;

                Map<String, Object> result = ingestionService.queryStructuredData(
                        sheet, chunks, queryIntent, maxResults);
                result.put("sourceSheetId", sheet.getId());
                results.add(result);
            } catch (Exception e) {
                log.warn("检索 Sheet[{}] 失败: {}", sheet.getId(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * 搜索所有表格中包含特定关键词的行。
     * 用于模糊搜索场景（用户不知道该查哪个列）。
     *
     * @param documentId 文档 ID
     * @param keyword    搜索关键词
     * @return 搜索结果
     */
    public List<Map<String, Object>> fuzzySearch(Long documentId, String keyword) {
        Map<String, Object> queryIntent = new LinkedHashMap<>();
        queryIntent.put("operator", "contains");
        queryIntent.put("value", keyword);
        return retrieveRelevantData(documentId, queryIntent, 20);
    }
}
