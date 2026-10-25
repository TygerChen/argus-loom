package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.KnowledgeBaseSearchToolResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.util.MetadataReaders;
import com.argus.rag.qa.service.QaRetrievalService;
import com.argus.rag.qa.service.QaRetrievalService.QaRetrievalResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * 知识库检索工具。
 * <p>作为 Spring AI Tool 暴露给 Agent，用于在 KB_SEARCH 模式下执行知识库检索。</p>
 * <p>工具名称：{@value #TOOL_NAME}，功能：根据用户查询检索知识库组中的相关文档片段。</p>
 *
 * <p><b>Agent 与 Tool 的关系（assistant 和 qa 的本质区别）</b>：
 * qa 流程是"人写死的流程"——先检索后回答，代码里定死的；
 * Agent 模式是把"决定权"交给模型：模型看到用户问题，自己判断要不要调
 * knowledgeBaseSearch 这个工具（@Tool 注解就是它的"工具说明书"）。
 * 这就是 Function Calling / Tool Use——模型输出"我要调哪个工具、参数是什么"，
 * 框架执行后把结果喂回模型继续推理。</p>
 *
 * <p><b>安全细节</b>：groupId / userId 从 ToolContext（服务端会话状态）注入，
 * 模型只能决定"查什么"，不能决定"以谁的身份查"——权限锚点永远在服务端。</p>
 */
@Component
public class AssistantKnowledgeBaseTool {

    public static final String TOOL_NAME = "knowledgeBaseSearch";
    public static final String GROUP_ID_CONTEXT_KEY = "groupId";
    public static final String GROUP_IDS_CONTEXT_KEY = "groupIds";
    public static final String RESULT_HOLDER_CONTEXT_KEY = "knowledgeBaseToolResultHolder";
    public static final String USER_ID_CONTEXT_KEY = "currentUserId";

    private static final String INSUFFICIENT_CODE = ErrorCodes.RETRIEVAL_NOT_GROUNDED;
    private static final String INSUFFICIENT_MESSAGE = "检索到的有效证据不足，暂不回答。";

    private final QaRetrievalService qaRetrievalService;
    private final ToolEventEmitter toolEventEmitter;

    public AssistantKnowledgeBaseTool(QaRetrievalService qaRetrievalService,
                                       ToolEventEmitter toolEventEmitter) {
        this.qaRetrievalService = qaRetrievalService;
        this.toolEventEmitter = toolEventEmitter;
    }

    /**
     * 执行知识库检索。
     * <p>作为 Spring AI Tool 暴露给 ReactAgent，Agent 会在需要检索外部知识时自动调用。
     * 每次会话仅允许一次有效检索，重复调用会返回提示信息要求 Agent 基于已有证据作答。</p>
     * <p>检索结果通过 {@link AssistantKnowledgeBaseToolResultHolder} 记录引用列表，
     * 供 {@link AssistantAgentFacade} 在构建最终响应时使用。</p>
     *
     * @param query       用于检索知识库的查询文本，通常为用户的当前问题
     * @param toolContext Spring AI 工具上下文，包含 groupId 和结果持有器
     * @return 知识库检索结果，包含成功标识、证据列表和引用列表
     */
    @Tool(
            name = TOOL_NAME,
            description = "在当前已选择的一个或多个知识库中检索相关证据片段。只返回证据，不生成最终答案。"
    )
    public KnowledgeBaseSearchToolResponse search(
            @ToolParam(description = "用于检索知识库的查询文本，通常使用用户当前问题。") String query,
            ToolContext toolContext
    ) {
        List<Long> groupIds = readGroupIds(toolContext);
        AssistantKnowledgeBaseToolResultHolder resultHolder = readResultHolder(toolContext);
        if (resultHolder.hasCompletedSearch()) {
            return new KnowledgeBaseSearchToolResponse(
                    false,
                    "DUPLICATE_TOOL_CALL",
                    "本轮已经完成过一次知识库检索，请基于上一条工具返回的 evidences 直接给出最终回答，不要再次调用工具。",
                    null,
                    "本轮知识库检索结果已经返回，必须停止继续调用工具并生成最终回答。",
                    List.of(),
                    resultHolder.currentCitations()
            );
        }
        String safeQuery = requireQuery(query);
        toolEventEmitter.emit(ToolEventEmitter.ToolEvent.start(TOOL_NAME));
        QaRetrievalResult result = qaRetrievalService.retrieveEvidence(groupIds, safeQuery);
        List<Document> documents = result.bundle().documents();
        if (documents == null || documents.isEmpty()) {
            resultHolder.recordCitations(List.of());
            return new KnowledgeBaseSearchToolResponse(
                    false,
                    INSUFFICIENT_CODE,
                    INSUFFICIENT_MESSAGE,
                    result.bundle().evidenceLevel() == null ? null : result.bundle().evidenceLevel().name(),
                    result.bundle().evidenceGuidance(),
                    List.of(),
                    List.of()
            );
        }
        resultHolder.recordCitations(result.citations());
        return new KnowledgeBaseSearchToolResponse(
                true,
                null,
                null,
                result.bundle().evidenceLevel() == null ? null : result.bundle().evidenceLevel().name(),
                result.bundle().evidenceGuidance(),
                documents.stream().map(AssistantKnowledgeBaseTool::toEvidence).toList(),
                result.citations()
        );
    }

    private static String requireQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException("知识库检索 query 不能为空");
        }
        return query.trim();
    }

    private List<Long> readGroupIds(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new BusinessException("知识库检索缺少工具上下文");
        }
        Object groupIdsValue = toolContext.getContext().get(GROUP_IDS_CONTEXT_KEY);
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
        if (groupIdsValue instanceof String stringValue && StringUtils.hasText(stringValue)) {
            List<Long> groupIds = parseCommaSeparated(stringValue);
            if (!groupIds.isEmpty()) {
                return List.copyOf(groupIds);
            }
        }
        // 兼容旧版单组上下文
        Object value = toolContext.getContext().get(GROUP_ID_CONTEXT_KEY);
        Long singleGroupId = parsePositiveLong(value);
        if (singleGroupId != null) {
            return List.of(singleGroupId);
        }
        throw new BusinessException("知识库检索缺少 groupIds");
    }

    static List<Long> parseCommaSeparated(String value) {
        List<Long> groupIds = new ArrayList<>();
        for (String token : value.split(",")) {
            Long parsed = parsePositiveLong(token.trim());
            if (parsed != null) {
                groupIds.add(parsed);
            }
        }
        return groupIds;
    }

    static Long parsePositiveLong(Object value) {
        return AssistantIds.parsePositiveLong(value);
    }

    private AssistantKnowledgeBaseToolResultHolder readResultHolder(ToolContext toolContext) {
        Object value = toolContext.getContext().get(RESULT_HOLDER_CONTEXT_KEY);
        if (value instanceof AssistantKnowledgeBaseToolResultHolder holder) {
            return holder;
        }
        throw new BusinessException("知识库检索缺少结果上下文");
    }

    private static KnowledgeBaseSearchToolResponse.Evidence toEvidence(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new KnowledgeBaseSearchToolResponse.Evidence(
                readLong(metadata, "documentId"),
                readLong(metadata, "chunkId"),
                readInteger(metadata, "chunkIndex"),
                readText(metadata, "fileName"),
                readScore(metadata),
                document.getText()
        );
    }

    private static Long readLong(Map<String, Object> metadata, String key) {
        return MetadataReaders.longValue(metadata, key);
    }

    private static Integer readInteger(Map<String, Object> metadata, String key) {
        return MetadataReaders.intValue(metadata, key);
    }

    private static double readScore(Map<String, Object> metadata) {
        return MetadataReaders.score(metadata);
    }

    private static String readText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String text ? text : null;
    }
}
