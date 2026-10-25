package com.argus.rag.excel.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.excel.service.ExcelSheetAccessService;
import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.pipeline.TableIngestionService;
import com.argus.rag.excel.pipeline.TableSchemaService;
import com.argus.rag.excel.service.ExcelSheetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化数据查询 API。
 * <p>
 * 提供自然语言查询 Excel 表格数据的能力，
 * 将企业表格数据变为 AI 可查询的结构化知识。
 * <p>
 * 这是产品的核心差异化能力：在 RAG 平台中唯一同时覆盖非结构化文档 + 结构化表格的产品。
 */
@RestController
@RequestMapping("/api/excel/schema")
@Tag(name = "结构化数据查询", description = "自动识别表格 Schema + 自然语言查询表格数据")
public class StructuredDataController {

    private static final Logger log = LoggerFactory.getLogger(StructuredDataController.class);

    private final ExcelSheetService sheetService;
    private final TableSchemaService schemaService;
    private final TableIngestionService ingestionService;
    private final ExcelSheetAccessService sheetAccessService;
    private final ExcelPermissionService permissionService;

    public StructuredDataController(ExcelSheetService sheetService,
                                     TableSchemaService schemaService,
                                     TableIngestionService ingestionService,
                                     ExcelSheetAccessService sheetAccessService,
                                     ExcelPermissionService permissionService) {
        this.sheetService = sheetService;
        this.schemaService = schemaService;
        this.ingestionService = ingestionService;
        this.sheetAccessService = sheetAccessService;
        this.permissionService = permissionService;
    }

    /**
     * 获取指定 Sheet 的 Schema 描述。
     * <p>
     * 自动检测列名、数据类型、统计信息和示例值。
     * 此接口返回的数据可直接注入 LLM prompt 中作为上下文。
     *
     * @param sheetId Sheet ID
     * @return Schema JSON（列名、类型、统计、示例值）
     */
    @GetMapping("/{sheetId}")
    public ApiResponse<Map<String, Object>> getSchema(@PathVariable Long sheetId) {
        requireSheetAccess(sheetId);
        ExcelSheet sheet = findSheet(sheetId);
        List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheetId);
        if (chunks.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("sheetName", sheet.getSheetName());
            empty.put("totalRows", sheet.getTotalRows());
            empty.put("totalCols", sheet.getTotalCols());
            empty.put("columns", new ArrayList<>());
            return ApiResponse.success(empty);
        }

        Map<String, Object> schema = schemaService.extractSchema(chunks, sheet);
        List<Object> columns = JsonCompat.getJSONArray(schema, "columns");
        log.info("Schema 获取: sheet={}, columns={}", sheet.getSheetName(),
                  columns != null ? columns.size() : 0);
        return ApiResponse.success(schema);
    }

    /**
     * 获取 RAG 就绪的结构化知识摘要。
     * <p>
     * 返回 Markdown 格式的自然语言描述，可直接作为文本块嵌入到 RAG 系统中。
     * 包含 Schema 定义 + 数据样例。
     *
     * @param documentId 文档 ID
     * @return Markdown 格式知识摘要
     */
    @GetMapping("/document/{documentId}/summary")
    public ApiResponse<String> getStructuredSummary(@PathVariable Long documentId) {
        permissionService.requireView(documentId);
        String summary = ingestionService.buildStructuredKnowledgeSummary(documentId);
        if (summary.isEmpty()) {
            throw new BusinessException("文档无结构化数据");
        }
        log.info("结构化摘要已生成: documentId={}, length={}", documentId, summary.length());
        return ApiResponse.success(summary);
    }

    /**
     * 按条件查询结构化数据。
     * <p>
     * 支持简单过滤查询：列名匹配、数值比较、文本包含。
     * 用于验证 Schema 信息是否正确，以及快速检索特定行。
     *
     * @param sheetId Sheet ID
     * @param filter  过滤条件 JSON {"column":"列名","operator":"eq/contains/gt/lt","value":"值"}
     * @return 匹配的数据行
     */
    @PostMapping("/{sheetId}/query")
    public ApiResponse<Map<String, Object>> queryData(@PathVariable Long sheetId,
                                              @RequestBody Map<String, Object> filter) {
        requireSheetAccess(sheetId);
        ExcelSheet sheet = findSheet(sheetId);
        List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheetId);
        Map<String, Object> result = ingestionService.queryStructuredData(sheet, chunks, filter, 100);
        return ApiResponse.success(result);
    }

    private ExcelSheet findSheet(Long sheetId) {
        // 走 Service（过滤已删除 sheet），不直接穿透 Mapper。
        ExcelSheet sheet = sheetService.getSheetById(sheetId);
        if (sheet == null) {
            throw new BusinessException("Sheet 不存在: " + sheetId);
        }
        return sheet;
    }

    /** Sheet 数据含真实业务内容，读取前必须完成工作簿 + 归属双重校验。 */
    private void requireSheetAccess(Long sheetId) {
        ExcelSheet sheet = findSheet(sheetId);
        sheetAccessService.requireSheetView(sheet.getDocumentId(), sheetId,
                permissionService.currentUser());
    }
}
