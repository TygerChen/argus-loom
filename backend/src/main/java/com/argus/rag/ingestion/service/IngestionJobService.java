package com.argus.rag.ingestion.service;

import com.argus.rag.common.enums.IngestionJobStatus;
import com.argus.rag.common.enums.IngestionJobType;
import com.argus.rag.ingestion.mapper.IngestionJobMapper;
import com.argus.rag.ingestion.model.entity.IngestionJobEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 摄入任务记录服务：维护 {@code ingestion_jobs} 表中的任务全生命周期。
 *
 * <p>职责边界——本服务<b>只记录</b>任务状态，不执行 ETL。ETL 的实际执行方
 * {@link DocumentIngestionAsyncService} 在开始/成功/失败时调用本服务写入状态，
 * 上传链路在上传落库时调用 {@link #registerIngestDocumentJob} 登记新任务。</p>
 *
 * <p>设计动机：上传完成后用户只能看到文档级状态（PROCESSING/READY/FAILED），
 * 看不到"这次处理"的任务级记录（何时开始、重试几次、错在哪）。任务表让每次
 * 处理（含重试）都有独立可查的记录，是上传流水线可观察性的数据基础。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Service
@Slf4j
public class IngestionJobService {

    /** 任务错误信息的最大存储长度（与 documents.failure_reason 同口径） */
    private static final int LAST_ERROR_MAX_LENGTH = 512;

    /** 摄入任务数据访问层 */
    private final IngestionJobMapper ingestionJobMapper;

    public IngestionJobService(IngestionJobMapper ingestionJobMapper) {
        this.ingestionJobMapper = ingestionJobMapper;
    }

    /**
     * 登记一条文档摄入任务（PENDING），在上传文档落库后调用。
     *
     * @param documentId 文档 ID
     * @param groupId    群组 ID
     * @return 新任务的 ID
     */
    @Transactional
    public Long registerIngestDocumentJob(Long documentId, Long groupId) {
        LocalDateTime now = LocalDateTime.now();
        IngestionJobEntity job = new IngestionJobEntity();
        job.setDocumentId(documentId);
        job.setGroupId(groupId);
        job.setJobType(IngestionJobType.INGEST_DOCUMENT.name());
        job.setStatus(IngestionJobStatus.PENDING.name());
        job.setRetryCount(0);
        job.setMaxRetries(3);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        ingestionJobMapper.insert(job);
        log.info("登记摄入任务: jobId={}, documentId={}, groupId={}", job.getId(), documentId, groupId);
        return job.getId();
    }

    /**
     * 将该文档最新的 PENDING 任务原子认领为 RUNNING（ETL 开始时调用）。
     *
     * <p>无 PENDING 任务时静默跳过——兼容历史文档（任务表启用前上传），
     * 保证状态推进不阻断 ETL 主流程。</p>
     *
     * @param documentId 文档 ID
     * @param workerId   执行者标识（线程名或实例 ID）
     */
    public void markRunning(Long documentId, Long groupId, String workerId) {
        findJobInStatus(documentId, IngestionJobStatus.PENDING.name())
                .ifPresent(job -> ingestionJobMapper.claimRunning(
                        job.getId(),
                        IngestionJobStatus.PENDING.name(),
                        IngestionJobStatus.RUNNING.name(),
                        workerId,
                        LocalDateTime.now()));
    }

    /**
     * 将该文档的 RUNNING 任务标记为成功（ETL 全部完成后调用）。
     *
     * @param documentId 文档 ID
     */
    public void markSucceeded(Long documentId) {
        findJobInStatus(documentId, IngestionJobStatus.RUNNING.name())
                .ifPresent(job -> ingestionJobMapper.markSucceeded(
                        job.getId(),
                        IngestionJobStatus.RUNNING.name(),
                        IngestionJobStatus.SUCCEEDED.name(),
                        job.getWorkerId(),
                        LocalDateTime.now()));
    }

    /**
     * 将该文档的 RUNNING 任务标记为失败并记录截断后的错误信息（ETL 最终失败时调用）。
     *
     * @param documentId 文档 ID
     * @param errorMessage 失败原因（超长自动截断）
     */
    public void markFailed(Long documentId, String errorMessage) {
        findJobInStatus(documentId, IngestionJobStatus.RUNNING.name())
                .ifPresent(job -> ingestionJobMapper.markFailed(
                        job.getId(),
                        IngestionJobStatus.RUNNING.name(),
                        IngestionJobStatus.FAILED.name(),
                        job.getWorkerId(),
                        LocalDateTime.now(),
                        truncateError(errorMessage)));
    }

    /**
     * 查询某文档的全部任务记录（最新在前），供状态查询接口使用。
     *
     * @param documentId 文档 ID
     * @return 任务列表（可能为空）
     */
    public List<IngestionJobEntity> listJobsByDocument(Long documentId) {
        return ingestionJobMapper.selectByDocumentIdOrdered(documentId);
    }

    /** 在指定状态的任务里找该文档最新的一条，找不到返回空 Optional。 */
    private java.util.Optional<IngestionJobEntity> findJobInStatus(Long documentId, String status) {
        return ingestionJobMapper.selectByDocumentIdOrdered(documentId).stream()
                .filter(job -> status.equals(job.getStatus()))
                .findFirst();
    }

    /** 截断错误信息至存储上限，null/空白返回默认文案。 */
    private static String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "文档处理失败";
        }
        return errorMessage.length() <= LAST_ERROR_MAX_LENGTH
                ? errorMessage
                : errorMessage.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
