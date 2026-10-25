package com.argus.rag.qa.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.util.MetadataReaders;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.qa.mapper.QaRecordCitationMapper;
import com.argus.rag.qa.mapper.QaRecordMapper;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.entity.QaRecordCitationEntity;
import com.argus.rag.qa.model.entity.QaRecordEntity;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * QA 问答记录持久化服务 —— 每次问答的"档案管理员"。
 *
 * <p>保存两样东西：问答主记录（问题、回答、证据等级、模型名、耗时，
 * qa_records 表）和引用快照（回答引用了哪些文档的哪些块，
 * qa_record_citations 表）。<b>为什么要存引用快照？</b>知识库内容是会变的——
 * 文档可能被删、被改，但"当时 AI 是看着哪段话回答的"必须永久可查，
 * 这是"回答可审计"的最后一环（04 篇：开口有据，拒答有理）。</p>
 *
 * <p><b>可靠性取舍</b>：持久化失败只记日志、不抛异常——用户已经拿到了回答，
 * 档案没存好不该让用户重问一次。统计/档案类操作给主流程让路，
 * 和 LlmUsageCollectorImpl 是同一个原则。</p>
 */
@Service
public class QaRecordPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(QaRecordPersistenceService.class);
    private static final int SNIPPET_LIMIT = 1200;

    private final QaRecordMapper qaRecordMapper;
    private final QaRecordCitationMapper qaRecordCitationMapper;
    private final CurrentUserService currentUserService;

    public QaRecordPersistenceService(QaRecordMapper qaRecordMapper,
                                      QaRecordCitationMapper qaRecordCitationMapper,
                                      CurrentUserService currentUserService) {
        this.qaRecordMapper = qaRecordMapper;
        this.qaRecordCitationMapper = qaRecordCitationMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * 保存一次已结束的 QA 问答记录。
     * <p>持久化失败只记录日志并返回 null，避免影响用户已得到的问答结果。</p>
     */
    /** 用户给问答点踩/点赞。可见性口径与详情查询完全一致，防止按 ID 给别人记录刷分。 */
    public void updateRating(Long recordId, String rating) {
        // 评分属写操作：只能评自己可见（本人提问 / 所属群 OWNER / 系统管理员）的记录，
        // 复用与详情查询完全相同的可见性口径，防止按 ID 给他人记录刷分。
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        boolean systemAdmin = currentUser.systemRole() == SystemRole.ADMIN;
        QaRecordEntity entity = qaRecordMapper.selectVisibleRecord(
                recordId, currentUser.userId(), systemAdmin);
        if (entity == null) {
            throw new BusinessException("问答记录不存在");
        }
        entity.setUserRating(rating);
        qaRecordMapper.updateById(entity);
    }

    public Long saveCompleted(SaveCommand command) {
        if (command == null) {
            return null;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<QaRecordCitationEntity> citations = toCitationEntities(command.evidenceDocuments(), now);
            AskQuestionResponse response = command.response();
            QaChatService.UsageInfo usage = command.usage() == null
                    ? new QaChatService.UsageInfo(0, 0, 0, false, 0L)
                    : command.usage();

            QaRecordEntity record = new QaRecordEntity();
            record.setUserId(command.userId());
            record.setGroupId(command.groupId());
            record.setQuestion(command.question());
            record.setAnswer(response == null ? null : response.answer());
            record.setAnswered(response != null && response.answered());
            record.setReasonCode(response == null ? null : response.reasonCode());
            record.setReasonMessage(response == null ? null : response.reasonMessage());
            record.setEvidenceLevel(command.evidenceLevel() == null ? null : command.evidenceLevel().name());
            record.setCitationCount(citations.size());
            record.setPromptTokens(usage.promptTokens());
            record.setCompletionTokens(usage.completionTokens());
            record.setTotalTokens(usage.totalTokens());
            record.setIsEstimated(usage.estimated());
            record.setLatencyMs(usage.latencyMs());
            record.setModelName(command.modelName());
            record.setEndpoint(command.endpoint());
            record.setSuccess(command.success());
            record.setErrorMessage(command.errorMessage());
            record.setCreatedAt(now);

            qaRecordMapper.insert(record);
            if (record.getId() == null) {
                return null;
            }
            for (QaRecordCitationEntity citation : citations) {
                citation.setQaRecordId(record.getId());
                qaRecordCitationMapper.insert(citation);
            }
            return record.getId();
        } catch (Exception exception) {
            log.warn("QA record persistence failed. userId={}, groupId={}, endpoint={}",
                    command.userId(), command.groupId(), command.endpoint(), exception);
            return null;
        }
    }

    /** 将检索返回的原始 Document 列表转换为引用快照实体。 */
    private List<QaRecordCitationEntity> toCitationEntities(List<Document> documents, LocalDateTime now) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<QaRecordCitationEntity> citations = new ArrayList<>();
        for (Document document : documents) {
            QaRecordCitationEntity citation = toCitationEntity(document, now);
            if (citation != null) {
                citations.add(citation);
            }
        }
        return citations;
    }

    /** 从单条检索证据中提取文档、切片、得分和片段文本。 */
    private QaRecordCitationEntity toCitationEntity(Document document, LocalDateTime now) {
        if (document == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        String fileName = readText(metadata, "fileName");
        if (!StringUtils.hasText(fileName)) {
            fileName = readText(metadata, "documentName");
        }
        if (!StringUtils.hasText(fileName)) {
            return null;
        }

        QaRecordCitationEntity entity = new QaRecordCitationEntity();
        entity.setDocumentId(readLong(metadata, "documentId"));
        entity.setDocumentVersionId(readLong(metadata, "documentVersionId"));
        entity.setChunkId(readLong(metadata, "chunkId"));
        entity.setChunkIndex(readInteger(metadata, "chunkIndex"));
        entity.setStartChunkIndex(readInteger(metadata, "startChunkIndex"));
        entity.setEndChunkIndex(readInteger(metadata, "endChunkIndex"));
        entity.setFileName(fileName);
        entity.setScore(readDouble(metadata, "score"));
        entity.setRetrievalSource(readText(metadata, "retrievalSource"));
        entity.setVectorScore(readDouble(metadata, "vectorScore"));
        entity.setKeywordScore(readDouble(metadata, "keywordScore"));
        entity.setHybridScore(readDouble(metadata, "hybridScore"));
        entity.setSnippet(snippet(document.getText()));
        entity.setCreatedAt(now);
        return entity;
    }

    /** 从元数据中读取 Long 类型字段。 */
    private Long readLong(Map<String, Object> metadata, String key) {
        return MetadataReaders.longValue(metadata, key);
    }

    /** 从元数据中读取 Integer 类型字段。 */
    private Integer readInteger(Map<String, Object> metadata, String key) {
        return MetadataReaders.intValue(metadata, key);
    }

    /** 从元数据中读取 Double 类型字段。 */
    private Double readDouble(Map<String, Object> metadata, String key) {
        return MetadataReaders.doubleValue(metadata, key);
    }

    /** 从元数据中读取非空字符串字段。 */
    private String readText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    /** 归一化并截断证据文本，避免引用快照过长。 */
    private static String snippet(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_LIMIT ? normalized : normalized.substring(0, SNIPPET_LIMIT);
    }

    /** 保存 QA 记录所需的完整上下文。 */
    public record SaveCommand(
            Long userId,
            Long groupId,
            String question,
            String endpoint,
            String modelName,
            AskQuestionResponse response,
            EvidenceLevel evidenceLevel,
            QaChatService.UsageInfo usage,
            List<Document> evidenceDocuments,
            boolean success,
            String errorMessage
    ) {
    }
}
