package com.argus.rag.assistant.agent;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.engine.StructuredDataRetrievalService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.group.service.GroupMembershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assistant Agent 的结构化数据工具。
 * <p>
 * 使 AI Agent 能够直接查询 Excel/表格数据，将自然语言转换为结构化查询并返回精确数据行。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>Agent 不需要理解 SQL——它只需调用此工具的 {@link #queryStructuredData} 方法</li>
 *   <li>工具内部处理 Schema 匹配、过滤执行、结果格式化</li>
 *   <li>返回结果包含完整的数据行 + Schema 描述，Agent 可直接据此回答用户问题</li>
 * </ul>
 * <p>
 * 典型场景：
 * <ul>
 *   <li>"深圳地区的销售额是多少？" → Agent 调用此工具查询表格</li>
 *   <li>"列出单价超过 500 的所有商品" → Agent 构造过滤条件后调用</li>
 *   <li>"张三的最近 5 条记录" → Agent 查询后归纳</li>
 * </ul>
 *
 * @see StructuredDataRetrievalService
 * @see AssistantKnowledgeBaseTool
 */
@Component
public class StructuredDataQueryTool {

    private static final Logger log = LoggerFactory.getLogger(StructuredDataQueryTool.class);

    /**
     * 金融 Excel 越权拦截的「不透明」提示。
     * <p>文档不存在/{@code roleGate} 门槛不达 两条路径共用同一文案，刻意与「确有数据但无权」
     * 不可区分，防止通过报错差异探测受限文档的存在性。两处必须恒同——收敛为单常量杜绝单边漂移。</p>
     */
    private static final String OPAQUE_NO_DATA_MESSAGE = "未找到该表格的结构化数据。";

    private final StructuredDataRetrievalService retrievalService;
    private final DocumentMapper ragDocumentMapper;
    private final GroupMembershipService groupMembershipService;
    private final DocumentRoleGate roleGate;
    /** Excel 工作簿权限判定唯一入口（owner/editor/viewer + 财务隔离） */
    private final ExcelPermissionService excelPermissionService;

    public StructuredDataQueryTool(StructuredDataRetrievalService retrievalService,
                                    DocumentMapper ragDocumentMapper,
                                    GroupMembershipService groupMembershipService,
                                    DocumentRoleGate roleGate,
                                    ExcelPermissionService excelPermissionService) {
        this.retrievalService = retrievalService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.groupMembershipService = groupMembershipService;
        this.roleGate = roleGate;
        this.excelPermissionService = excelPermissionService;
    }

    /**
     * 查询 Excel 表格中的结构化数据。
     * <p>
     * 使用此工具可以精确查找表格中的数据行。
     * 支持按列名过滤、数值比较、文本模糊匹配。
     *
     * @param column     要查询的列名（如"销售额""地区""产品名"）
     * @param operator   比较运算符：eq(等于)、contains(包含)、gt(大于)、lt(小于)
     * @param value      要匹配的值
     * @param documentId 文档 ID（可选，不传则查询所有文档的表格）
     * @return JSON 格式的查询结果，包含匹配的数据行和 Schema 信息
     */
    @Tool(name = "query_structured_data",
          description = "查询 Excel 表格中的结构化数据。用于回答需要对表格数据进行精确查找、过滤、统计的问题。" +
                        "例如：查找特定地区的销售额、列出符合条件的产品、统计数据汇总等。")
    public String queryStructuredData(
            @ToolParam(description = "要查询的列名，如'销售额'、'地区'、'产品名'。Agent 需先通过 Schema 了解有哪些可用列。",
                       required = true) String column,
            @ToolParam(description = "比较运算符：eq(等于)、contains(包含)、gt(大于)、lt(小于)",
                       required = false) String operator,
            @ToolParam(description = "要匹配的值",
                       required = true) String value,
            @ToolParam(description = "文档 ID，指定要查询哪个 Excel 文件。不确定时可不传，系统会列出当前账号可检索的文档。",
                       required = false) Long documentId,
            ToolContext toolContext) {
        Map<String, Object> queryIntent = new LinkedHashMap<>();
        queryIntent.put("column", column);
        queryIntent.put("operator", operator != null ? operator : "eq");
        queryIntent.put("value", value);

        log.info("Agent 结构化查询请求: column={}, op={}, value={}, docId={}",
                  column, operator, value, documentId);

        StringBuilder result = new StringBuilder();

        try {
            if (documentId != null) {
                // 先校验当前用户对该文档（含角色门槛）可读，未达标等同"无数据"，防财务 Excel 越权
                requireStructuredDataReadable(documentId);
                // 查询指定文档
                String schemaCtx = retrievalService.buildStructuredContext(documentId);
                result.append("## 查询结果\n\n");

                List<Map<String, Object>> dataResults = retrievalService.retrieveRelevantData(
                        documentId, queryIntent, 50);
                appendResults(result, dataResults, schemaCtx);
            } else {
                // 未指定 documentId：列出当前账号可检索的文档（群组范围 + 角色门槛双重过滤）。
                // 绝不在此路径返回任何受限文档的信息——与"无此数据"不可区分，防财务 Excel 越权试探。
                result.append("## 全局表格查询结果\n\n");
                result.append(listReadableStructuredDocuments(toolContext));
            }
        } catch (Exception e) {
            log.error("结构化查询失败", e);
            result.append("查询失败: ").append(e.getMessage());
        }

        return result.toString();
    }

    /**
     * 未指定 documentId 时列出的候选文档文本。
     * <p>候选集 = {@link DocumentRoleGate#allowedDocumentIds(Long)} 在本次会话各群组上的并集，
     * 已经过"群组成员资格 + required_role 门槛 + READY + 未删除"过滤，
     * 且始终由当前请求用户（{@code UserContext}）推导，绝不包含无权查看的文档。</p>
     */
    private String listReadableStructuredDocuments(ToolContext toolContext) {
        List<Long> groupIds = readGroupIds(toolContext);
        if (groupIds.isEmpty()) {
            return "【提示】未能在本次对话范围内确定可检索的群组，请指定 documentId 后查询。";
        }
        Set<Long> allowedIds = new LinkedHashSet<>();
        for (Long groupId : groupIds) {
            allowedIds.addAll(roleGate.allowedDocumentIds(groupId));
        }
        if (allowedIds.isEmpty()) {
            return "当前没有可检索的 Excel 文档。";
        }
        StringBuilder sb = new StringBuilder("当前可检索的文档（请指定其中的 documentId 继续查询）：\n\n");
        for (DocumentEntity doc : ragDocumentMapper.selectBatchIds(allowedIds)) {
            if (doc != null && doc.getId() != null) {
                sb.append("- 文档 #").append(doc.getId());
                if (doc.getFileName() != null) {
                    sb.append("：").append(doc.getFileName());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 从 {@link ToolContext} 读取允许检索的群组 ID 列表（缺失/非法时返回空集，不抛错）。
     */
    private List<Long> readGroupIds(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return List.of();
        }
        Object groupIdsValue = toolContext.getContext().get(AssistantKnowledgeBaseTool.GROUP_IDS_CONTEXT_KEY);
        if (groupIdsValue instanceof List<?> groupIdList) {
            List<Long> groupIds = new ArrayList<>();
            for (Object item : groupIdList) {
                Long parsed = parsePositiveLong(item);
                if (parsed != null) {
                    groupIds.add(parsed);
                }
            }
            if (!groupIds.isEmpty()) {
                return List.copyOf(groupIds);
            }
        }
        // 兼容旧版单组上下文
        Long singleGroupId = parsePositiveLong(toolContext.getContext().get(AssistantKnowledgeBaseTool.GROUP_ID_CONTEXT_KEY));
        if (singleGroupId != null) {
            return List.of(singleGroupId);
        }
        return List.of();
    }

    static Long parsePositiveLong(Object value) {
        return AssistantIds.parsePositiveLong(value);
    }

    /**
     * 获取指定文档的表格 Schema 信息。
     * <p>
     * Agent 应先调用此工具了解有哪些列可用，然后再调用 query_structured_data 进行具体查询。
     *
     * @param documentId 文档 ID
     * @return Schema 描述文本
     */
    @Tool(name = "get_table_schema",
          description = "获取 Excel 文档的表格结构信息（有哪些 Sheet、每个 Sheet 有哪些列、各列的数据类型和示例值）。" +
                        "在对表格数据进行查询之前，通常应先调用此工具了解表格结构。")
    public String getTableSchema(
            @ToolParam(description = "文档 ID", required = true) Long documentId) {
        log.info("Agent 请求表格 Schema: documentId={}", documentId);
        requireStructuredDataReadable(documentId);
        String schema = retrievalService.buildStructuredContext(documentId);
        if (schema.isEmpty()) {
            return "文档 " + documentId + " 不包含结构化表格数据。";
        }
        return schema;
    }

    /**
     * 模糊搜索所有表格中的数据。
     *
     * @param keyword    搜索关键词
     * @param documentId 文档 ID
     * @return 搜索结果
     */
    @Tool(name = "search_table_data",
          description = "在 Excel 表格中模糊搜索包含特定关键词的数据行。" +
                        "当你不知道具体应该查哪一列时使用此工具。")
    public String searchTableData(
            @ToolParam(description = "搜索关键词", required = true) String keyword,
            @ToolParam(description = "文档 ID", required = true) Long documentId) {
        log.info("Agent 表格数据搜索: keyword={}, documentId={}", keyword, documentId);
        requireStructuredDataReadable(documentId);
        List<Map<String, Object>> results = retrievalService.fuzzySearch(documentId, keyword);

        StringBuilder sb = new StringBuilder("## 模糊搜索: \"").append(keyword).append("\"\n\n");
        appendResults(sb, results, null);
        return sb.toString();
    }

    // ── 私有辅助方法 ──

    /**
     * 校验当前用户对指定文档可结构化读取（财务 Excel 数据隔离的 AI 工具侧强制）。
     * <p>与知识库检索/文档读侧共用 DocumentRoleGate 单一判定点：</p>
     * <ul>
     *   <li>文档不存在/已删除 → 视为"无数据"（防存在性泄露）；</li>
     *   <li>须为所属群组成员（{@link GroupMembershipService#requireGroupReadable}）；</li>
     *   <li>required_role 门槛须 ≤ 当前用户角色（{@code DocumentRoleGate.canRead}）。</li>
     * </ul>
     */
    private void requireStructuredDataReadable(Long documentId) {
        if (documentId == null) {
            return;
        }
        // Excel 工作簿鉴权只走 ExcelPermissionService（工作簿 owner/editor/viewer + 财务隔离）。
        // 绝不套用 RAG DocumentRoleGate/群组门槛——两套资源体系 ID 空间独立，
        // 数字相同时互串会造成「普通用户凭 RAG 公开文档读财务工作簿」类越权。
        if (!excelPermissionService.canView(documentId)) {
            throw new BusinessException(OPAQUE_NO_DATA_MESSAGE);
        }
    }

    private void appendResults(StringBuilder sb, List<Map<String, Object>> results, String schemaCtx) {
        if (results.isEmpty()) {
            sb.append("未找到匹配的数据行。");
            if (schemaCtx != null) {
                sb.append("\n\n可用的表格 Schema：\n").append(schemaCtx);
            }
            return;
        }

        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> result = results.get(i);
            String sheetName = JsonCompat.getString(result, "sheetName");
            Object matchCountObj = result.get("matchCount");
            int matchCount = matchCountObj instanceof Number ? ((Number) matchCountObj).intValue() : 0;
            Object rows = result.get("rows");

            sb.append("### ").append(sheetName).append("（匹配 ").append(matchCount).append(" 行）\n\n");

            if (rows instanceof List rowsArr) {
                for (int j = 0; j < Math.min(rowsArr.size(), 20); j++) {
                    Object rowItem = rowsArr.get(j);
                    if (rowItem instanceof Map) {
                        sb.append("- ").append(JsonCompat.toJSONString(rowItem));
                    } else {
                        sb.append("- ").append(JsonCompat.toJSONString(rowItem));
                    }
                    sb.append("\n");
                }
                if (rowsArr.size() > 20) {
                    sb.append("\n... 以及其他 ").append(rowsArr.size() - 20).append(" 行（已截断）\n");
                }
            } else {
                sb.append(JsonCompat.toJSONString(rows));
            }
            sb.append("\n");
        }
    }
}
