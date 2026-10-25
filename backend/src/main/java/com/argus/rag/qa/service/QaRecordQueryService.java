package com.argus.rag.qa.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.qa.mapper.QaRecordCitationMapper;
import com.argus.rag.qa.mapper.QaRecordMapper;
import com.argus.rag.qa.model.entity.QaRecordCitationEntity;
import com.argus.rag.qa.model.entity.QaRecordEntity;
import com.argus.rag.qa.model.vo.QaRecordDetailVO;
import com.argus.rag.qa.model.vo.QaRecordListItemVO;
import com.argus.rag.qa.model.vo.QaRecordPageVO;
import com.argus.rag.qa.support.EvidenceOverviewAssembler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;

/**
 * QA 问答历史查询服务，统一处理权限过滤、分页和响应组装。
 *
 * <p><b>这个类最值得学的是"引用脱敏"（REDACTED_SNIPPET）</b>：
 * 用户 A 的问答记录里可能引用了后来 B 才被禁止查看的文档——历史照实保留，
 * 但展示时把 B 无权看的摘录替换成占位文案，<b>既解释了为什么看不见，
 * 又一个字都不泄露</b>。权限不是"能不能看到记录"的二值判断，
 * 而是"记录可见、无权部分脱敏"的精细口径——这是权限设计的高阶形态。</p>
 */
@Service
public class QaRecordQueryService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int PREVIEW_LIMIT = 120;
    /** 无权引用的脱敏占位文案：解释为什么摘录不可见，但不泄露任何原文内容。 */
    private static final String REDACTED_SNIPPET = "（该引用来源文档当前对你的角色受限，摘录内容已隐藏）";

    private final CurrentUserService currentUserService;
    private final QaRecordMapper qaRecordMapper;
    private final QaRecordCitationMapper qaRecordCitationMapper;
    private final DocumentMapper documentMapper;
    private final DocumentRoleGate documentRoleGate;
    private final EvidenceOverviewAssembler evidenceOverviewAssembler;

    public QaRecordQueryService(CurrentUserService currentUserService,
                                QaRecordMapper qaRecordMapper,
                                QaRecordCitationMapper qaRecordCitationMapper,
                                DocumentMapper documentMapper,
                                DocumentRoleGate documentRoleGate,
                                EvidenceOverviewAssembler evidenceOverviewAssembler) {
        this.currentUserService = currentUserService;
        this.qaRecordMapper = qaRecordMapper;
        this.qaRecordCitationMapper = qaRecordCitationMapper;
        this.documentMapper = documentMapper;
        this.documentRoleGate = documentRoleGate;
        this.evidenceOverviewAssembler = evidenceOverviewAssembler;
    }

    /** 分页查询当前用户可见的 QA 历史记录。 */
    public QaRecordPageVO list(Long groupId, Boolean answered, Integer page, Integer pageSize) {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        boolean systemAdmin = currentUser.systemRole() == SystemRole.ADMIN;
        int safePage = page == null || page < 1 ? DEFAULT_PAGE : page;
        int safePageSize = normalizePageSize(pageSize);
        long offset = (long) (safePage - 1) * safePageSize;

        List<QaRecordListItemVO> items = qaRecordMapper.selectVisibleRecords(
                currentUser.userId(),
                systemAdmin,
                groupId,
                answered,
                offset,
                safePageSize
        ).stream().map(this::toListItem).toList();
        Long total = qaRecordMapper.countVisibleRecords(
                currentUser.userId(),
                systemAdmin,
                groupId,
                answered);
        return new QaRecordPageVO(items, total == null ? 0L : total, safePage, safePageSize);
    }

    /** 查询单条 QA 记录详情，并附带回答时保存的引用快照。 */
    public QaRecordDetailVO getDetail(Long recordId) {
        if (recordId == null || recordId <= 0) {
            throw new BusinessException("QA record id is invalid");
        }
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        boolean systemAdmin = currentUser.systemRole() == SystemRole.ADMIN;
        QaRecordEntity record = qaRecordMapper.selectVisibleRecord(recordId, currentUser.userId(), systemAdmin);
        if (record == null) {
            throw new BusinessException("QA record does not exist or is not visible");
        }
        List<QaRecordDetailVO.Citation> citations = loadCitationsForViewer(record, systemAdmin);
        return toDetail(record, citations);
    }

    /**
     * 按查看者角色过滤引用快照。
     * <p>
     * 历史记录的可见性只校验「本人提问 / 群 OWNER / 系统管理员」，但引用快照里可能包含
     * 回答时刻有权读、当前已无权读的文档摘录（如文档后来被设为 FINANCE 门槛）。
     * 普通用户查看时，先按当前角色解析每条引用来源文档的可读性：
     * 无权文档的摘录整体替换为脱敏占位文案，仅保留「有条引用」的事实，不泄露内容；
     * 系统管理员跳过检查，始终看完整快照。
     * </p>
     */
    private List<QaRecordDetailVO.Citation> loadCitationsForViewer(QaRecordEntity record, boolean systemAdmin) {
        List<QaRecordCitationEntity> entities = qaRecordCitationMapper.selectByRecordId(record.getId());
        if (systemAdmin || entities.isEmpty()) {
            return entities.stream().map(this::toCitation).toList();
        }
        Map<Long, DocumentEntity> citationDocs = loadCitationDocuments(entities);
        List<QaRecordDetailVO.Citation> citations = new ArrayList<>(entities.size());
        for (QaRecordCitationEntity entity : entities) {
            QaRecordDetailVO.Citation citation = toCitation(entity);
            if (isReadableByViewer(entity, citationDocs)) {
                citations.add(citation);
                continue;
            }
            citations.add(new QaRecordDetailVO.Citation(
                    citation.documentId(),
                    citation.documentVersionId(),
                    citation.chunkId(),
                    citation.chunkIndex(),
                    citation.startChunkIndex(),
                    citation.endChunkIndex(),
                    citation.fileName(),
                    citation.score(),
                    citation.retrievalSource(),
                    null,
                    null,
                    null,
                    REDACTED_SNIPPET));
        }
        return citations;
    }

    /** 一次性拉取本条记录引用的全部来源文档，避免逐条查询。 */
    private Map<Long, DocumentEntity> loadCitationDocuments(List<QaRecordCitationEntity> entities) {
        Set<Long> documentIds = entities.stream()
                .map(QaRecordCitationEntity::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectBatchIds(documentIds).stream()
                .filter(doc -> doc != null && doc.getId() != null)
                .collect(Collectors.toMap(DocumentEntity::getId, Function.identity()));
    }

    /** 判断当前查看者是否仍可读该引用的来源文档（角色门槛 + 文档存在性）。 */
    private boolean isReadableByViewer(QaRecordCitationEntity entity, Map<Long, DocumentEntity> citationDocs) {
        if (entity.getDocumentId() == null) {
            // 无来源文档的引用（如网页搜索结果）不含内部文档内容，直接放行。
            return true;
        }
        DocumentEntity doc = citationDocs.get(entity.getDocumentId());
        if (doc == null) {
            // 文档已不存在（物理删除）或查询不到：按不可读处理，fail-closed。
            return false;
        }
        return documentRoleGate.canRead(documentRoleGate.currentRole(), doc.getRequiredRole());
    }

    /** 删除该条可见的问答历史记录及其持久化的引用快照。 */
    @Transactional
    public void delete(Long recordId) {
        if (recordId == null || recordId <= 0) {
            throw new BusinessException("QA record id is invalid");
        }
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        boolean systemAdmin = currentUser.systemRole() == SystemRole.ADMIN;
        QaRecordEntity record = qaRecordMapper.selectVisibleRecord(recordId, currentUser.userId(), systemAdmin);
        if (record == null) {
            throw new BusinessException("QA record does not exist or is not visible");
        }
        qaRecordCitationMapper.deleteByRecordId(recordId);
        qaRecordMapper.deleteById(recordId);
    }

    /** 规范分页大小，避免一次请求拉取过多历史记录。 */
    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 将数据库实体转换为列表页展示项。 */
    private QaRecordListItemVO toListItem(QaRecordEntity record) {
        return new QaRecordListItemVO(
                record.getId(),
                record.getUserId(),
                record.getGroupId(),
                record.getQuestion(),
                record.getAnswered(),
                preview(record),
                record.getReasonCode(),
                record.getEvidenceLevel(),
                record.getCitationCount(),
                record.getLatencyMs(),
                record.getUserRating(),
                record.getCreatedAt()
        );
    }

    /** 将数据库实体和引用快照组合为详情响应。 */
    private QaRecordDetailVO toDetail(QaRecordEntity record, List<QaRecordDetailVO.Citation> citations) {
        return new QaRecordDetailVO(
                record.getId(),
                record.getUserId(),
                record.getGroupId(),
                record.getQuestion(),
                record.getAnswer(),
                record.getAnswered(),
                record.getReasonCode(),
                record.getReasonMessage(),
                record.getEvidenceLevel(),
                record.getCitationCount(),
                record.getPromptTokens(),
                record.getCompletionTokens(),
                record.getTotalTokens(),
                record.getIsEstimated(),
                record.getLatencyMs(),
                record.getUserRating(),
                record.getModelName(),
                record.getEndpoint(),
                record.getSuccess(),
                record.getErrorMessage(),
                record.getCreatedAt(),
                evidenceOverviewAssembler.assembleHistoryCitations(citations),
                citations
        );
    }

    /** 将引用快照实体转换为详情页引用项。 */
    private QaRecordDetailVO.Citation toCitation(QaRecordCitationEntity entity) {
        return new QaRecordDetailVO.Citation(
                entity.getDocumentId(),
                entity.getDocumentVersionId(),
                entity.getChunkId(),
                entity.getChunkIndex(),
                entity.getStartChunkIndex(),
                entity.getEndChunkIndex(),
                entity.getFileName(),
                entity.getScore(),
                entity.getRetrievalSource(),
                entity.getVectorScore(),
                entity.getKeywordScore(),
                entity.getHybridScore(),
                entity.getSnippet()
        );
    }

    /** 生成列表页回答预览，优先取回答正文，其次取拒答原因。 */
    private String preview(QaRecordEntity record) {
        String source = StringUtils.hasText(record.getAnswer()) ? record.getAnswer() : record.getReasonMessage();
        if (!StringUtils.hasText(source)) {
            return "";
        }
        String normalized = source.replaceAll("\\s+", " ").trim();
        return normalized.length() <= PREVIEW_LIMIT ? normalized : normalized.substring(0, PREVIEW_LIMIT);
    }
}
