package com.argus.rag.ingestion.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.argus.rag.ingestion.service.DocumentIngestionProcessor;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步文档 ETL（提取-转换-加载）服务 —— "一份上传文件变成可检索卡片"的执行端
 * （08 篇主链路；04 篇讲"为什么要分块"，这里讲"什么时候分块"）。
 *
 * <p><b>流程四步</b>（ingestDocument，每步都有讲究）：
 * <ol>
 *   <li><b>先清理旧产物</b>：把上次的 chunk、向量、ES 索引全删掉。为什么第一步先删？
 *       因为这套 ETL 必须是<b>可重跑的幂等流程</b>——重试机制（见下）意味着同一次
 *       ETL 可能被完整执行多遍，"先清后建"保证第 N 遍的结果和第 1 遍一致，
 *       不会留下新旧混杂的脏数据。这也是任务队列"at-least-once 投递"能安全运行的前提；</li>
 *   <li><b>解析 + 分块</b>：委托 DocumentIngestionProcessor（内部 = parser 三站流水线）；</li>
 *   <li><b>同步 ES 索引</b>：关键词路立刻可查；</li>
 *   <li><b>状态置 READY</b>：用户端"文档可见"的开关就是这一下。全程失败则
 *       recover 兜底置 FAILED + 记录原因——<b>状态机 PENDING→PROCESSING→READY/FAILED
 *       的每次迁移都在这一类里</b>，用户永远不该看到"半处理"的文档。</li>
 * </ol></p>
 *
 * <p><b>重试为什么是 2s/4s/8s（@Retryable + @Backoff multiplier=2）</b>：
 * 指数退避是重试的标准姿势——连续失败说明下游暂时不可用（数据库/ES 抖动），
 * 固定间隔重试会以相同节奏撞上同一个故障窗口，指数拉开间隔命中率更高，
 * 还能给下游喘息。3 次耗尽后 @Recover 的 recover 接管：清理现场、标 FAILED、
 * 记录原因，把失败变成"用户可见、可重试上传"的确定状态，而不是永远转圈。</p>
 *
 * <p><b>"Quietly"三兄弟的容忍策略（新手最容易忽略的细节）</b>：
 * markJobRunningQuietly/markJobSucceededQuietly/markJobFailedQuietly——
 * 任务记录表（ingestion_jobs）的更新全部 try-catch 包住只打 warn。
 * 为什么？记录表是"观察窗口"，不是"业务事实"：文档状态（document.status）
 * 才是用户看到的事实。观察窗口坏了顶多监控盲区，把业务事实搞坏才是事故——
 * <b>哪些失败可容忍、哪些必须传播，按"它是不是用户可见的事实"划线</b>。
 * 与 QaService 里"记账失败可容忍、落库失败必须抛"是同一条原则的两种应用。</p>
 *
 * <p><b>面试怎么讲这套设计</b>："我们的入库是异步的：上传秒回，ETL 走后台任务队列，
 * 状态机驱动前端展示；失败自动指数退避重试，重试的前提是 ETL 幂等（先清后建），
 * 重试耗尽进死信等人工。监控上看 READY 率、DEAD_LETTER 数量、ETL 耗时三条线。"
 * ——一分钟讲完，每个词都有代码对应。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Service
@Slf4j
public class DocumentIngestionAsyncService {

    /** 失败原因字段最大长度 */
    private static final int FAILURE_REASON_MAX_LENGTH = 512;

    /** 文档数据访问 */
    private final DocumentMapper documentMapper;
    /** 文档分块处理引擎 */
    private final DocumentIngestionProcessor documentIngestionProcessor;
    /** 文档分块数据访问 */
    private final DocumentChunkMapper documentChunkMapper;
    /** 向量导入服务 */
    private final VectorIngestionService vectorIngestionService;
    /** Elasticsearch chunk 索引服务 */
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    /** 摄入任务记录服务（ingestion_jobs 表读写） */
    private final IngestionJobService ingestionJobService;

    /**
     * 构造异步 ETL 服务，注入所有依赖。
     *
     * @param documentMapper                 文档数据访问层
     * @param documentIngestionProcessor     文档分块处理引擎
     * @param documentChunkMapper            文档分块数据访问层
     * @param vectorIngestionService         向量导入服务
     * @param elasticsearchChunkIndexService ES chunk 索引服务
     * @param ingestionJobService            摄入任务记录服务
     */
    public DocumentIngestionAsyncService(
            DocumentMapper documentMapper,
            DocumentIngestionProcessor documentIngestionProcessor,
            DocumentChunkMapper documentChunkMapper,
            VectorIngestionService vectorIngestionService,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            IngestionJobService ingestionJobService
    ) {
        this.documentMapper = documentMapper;
        this.documentIngestionProcessor = documentIngestionProcessor;
        this.documentChunkMapper = documentChunkMapper;
        this.vectorIngestionService = vectorIngestionService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.ingestionJobService = ingestionJobService;
    }

    /**
     * 异步执行文档 ETL 流程。
     *
     * <p>带重试机制：遇到 RuntimeException 时最多重试 3 次，
     * 退避策略为首次 2s，后续每次乘以 2（2s / 4s / 8s）。
     * 所有重试耗尽后由 {@link #recover} 兜底。
     *
     * @param documentId 文档 ID
     * @param groupId    文档所属群组 ID
     * @throws BusinessException 文档不存在或状态更新失败时抛出
     */
    @Retryable(
            retryFor = RuntimeException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    @Transactional
    public void ingestDocument(Long documentId, Long groupId) {
        DocumentEntity document = requireDocument(documentId, groupId);
        log.info("开始异步执行文档ETL: documentId={}, groupId={}", documentId, groupId);
        markJobRunningQuietly(documentId, groupId);
        cleanupProcessingArtifacts(documentId);
        documentIngestionProcessor.process(documentId, groupId);
        syncSearchIndex(document);
        markDocumentStatus(documentId, groupId, DocumentStatus.READY.name(), null, LocalDateTime.now());
        markJobSucceededQuietly(documentId);
        log.info("异步文档ETL完成: documentId={}, groupId={}, status={}", documentId, groupId, DocumentStatus.READY.name());
    }

    /**
     * 重试全部失败后的兜底恢复方法。
     *
     * <p>清理中间产物，将文档状态标记为 FAILED 并记录截断后的失败原因。
     * 此方法由 Spring Retry 的 {@link Recover} 机制自动调用。
     *
     * @param exception  导致最终失败的异常
     * @param documentId 文档 ID
     * @param groupId    文档所属群组 ID
     */
    @Recover
    @Transactional
    public void recover(RuntimeException exception, Long documentId, Long groupId) {
        log.error("异步文档ETL最终失败: documentId={}, groupId={}, reason={}", documentId, groupId, exception.getMessage(), exception);
        cleanupProcessingArtifacts(documentId);
        markDocumentStatus(
                documentId,
                groupId,
                DocumentStatus.FAILED.name(),
                truncateFailureReason(exception.getMessage()),
                LocalDateTime.now()
        );
        markJobFailedQuietly(documentId, exception.getMessage());
    }

    /** 推进任务为 RUNNING；任务记录异常只告警，不阻断 ETL 主流程。 */
    private void markJobRunningQuietly(Long documentId, Long groupId) {
        try {
            ingestionJobService.markRunning(documentId, groupId, workerId());
        } catch (RuntimeException exception) {
            log.warn("任务状态推进 RUNNING 失败(不影响ETL): documentId={}, reason={}",
                    documentId, exception.getMessage());
        }
    }

    /** 推进任务为 SUCCEEDED；任务记录异常只告警，不回滚已完成的 ETL。 */
    private void markJobSucceededQuietly(Long documentId) {
        try {
            ingestionJobService.markSucceeded(documentId);
        } catch (RuntimeException exception) {
            log.warn("任务状态推进 SUCCEEDED 失败(不影响ETL结果): documentId={}, reason={}",
                    documentId, exception.getMessage());
        }
    }

    /** 推进任务为 FAILED；任务记录异常只告警，不改变文档已标失败的事实。 */
    private void markJobFailedQuietly(Long documentId, String errorMessage) {
        try {
            ingestionJobService.markFailed(documentId, errorMessage);
        } catch (RuntimeException exception) {
            log.warn("任务状态推进 FAILED 失败(不影响文档失败标记): documentId={}, reason={}",
                    documentId, exception.getMessage());
        }
    }

    /** 当前 worker 标识：线程名 + 进程内唯一后缀，便于排查并发执行来源。 */
    private static String workerId() {
        return Thread.currentThread().getName() + "@" + Long.toHexString(System.identityHashCode(Thread.currentThread()));
    }

    /**
     * 查询文档实体，不存在时抛出异常。
     *
     * @param documentId 文档 ID
     * @param groupId    文档所属群组 ID
     * @return 文档实体
     * @throws BusinessException 文档不存在时抛出
     */
    private DocumentEntity requireDocument(Long documentId, Long groupId) {
        DocumentEntity document = documentMapper.selectByIdAndGroupId(documentId, groupId);
        if (document == null) {
            throw new BusinessException(ErrorCodes.DOCUMENT_NOT_FOUND, "待处理文档不存在");
        }
        return document;
    }

    /**
     * 清理上一次处理遗留的中间产物。
     *
     * <p>依次删除 chunk 记录、向量数据和 ES 索引。每项清理失败时仅记录日志，不中断后续清理。
     *
     * @param documentId 文档 ID
     */
    private void cleanupProcessingArtifacts(Long documentId) {
        log.info("开始清理上次处理中间产物: documentId={}", documentId);
        try {
            documentChunkMapper.deleteByDocumentId(documentId);
        } catch (RuntimeException exception) {
            log.warn("清理旧 chunk 失败: documentId={}, reason={}", documentId, exception.getMessage());
        }
        try {
            vectorIngestionService.deleteDocumentVectors(documentId);
        } catch (RuntimeException exception) {
            log.warn("清理旧向量失败: documentId={}, reason={}", documentId, exception.getMessage());
        }
        try {
            elasticsearchChunkIndexService.deleteDocumentChunks(documentId);
        } catch (RuntimeException exception) {
            log.warn("清理旧 ES 索引失败: documentId={}, reason={}", documentId, exception.getMessage());
        }
        log.info("中间产物清理完成: documentId={}", documentId);
    }

    /**
     * 将文档的分块数据同步到 Elasticsearch 搜索索引。
     *
     * @param document 文档实体
     */
    private void syncSearchIndex(DocumentEntity document) {
        log.info("开始同步ES搜索索引: documentId={}, fileName={}", document.getId(), document.getFileName());
        List<DocumentChunkEntity> chunks = documentChunkMapper.selectByDocumentId(document.getId());
        elasticsearchChunkIndexService.indexReadyChunks(document.getFileName(), chunks);
        log.info("ES搜索索引同步完成: documentId={}, indexedChunks={}", document.getId(), chunks.size());
    }

    /**
     * 更新文档状态、失败原因和处理时间。
     *
     * @param documentId    文档 ID
     * @param groupId       文档所属群组 ID
     * @param status        目标状态（READY 或 FAILED）
     * @param failureReason 失败原因（成功时为 null）
     * @param processedAt   处理完成时间
     * @throws BusinessException 更新影响行数为 0 时抛出
     */
    private void markDocumentStatus(
            Long documentId,
            Long groupId,
            String status,
            String failureReason,
            LocalDateTime processedAt
    ) {
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<DocumentEntity>()
                .eq(DocumentEntity::getId, documentId)
                .eq(DocumentEntity::getGroupId, groupId)
                .set(DocumentEntity::getStatus, status)
                .set(DocumentEntity::getFailureReason, failureReason)
                .set(DocumentEntity::getProcessedAt, processedAt)
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCodes.DOCUMENT_PROCESSING_FAILED, "文档状态更新失败");
        }
    }

    /**
     * 截断失败原因字符串至最大长度，空值返回默认消息。
     *
     * @param failureReason 原始失败原因
     * @return 截断后的失败原因
     */
    /** 包内静态、无实例态，供 JVM 行为锁直接调用。 */
    static String truncateFailureReason(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return "文档处理失败";
        }
        return failureReason.length() <= FAILURE_REASON_MAX_LENGTH
                ? failureReason
                : failureReason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
