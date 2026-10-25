package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.ingestion.service.IngestionJobService;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import com.argus.rag.job.service.BackgroundJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 上传链路中「文档最终记录持久化 + 外部索引登记」的专职组件。
 *
 * <p>把「写文档元数据入库 → 发布异步 ETL 事件 → 失败补偿外部（向量/ES）索引」这一职责
 * 从 {@code DocumentUploadService} 分离出来，上传编排只关心：会话流转 → 存储 →
 * 调用本组件落地文档，而无需知晓 Document 表结构与索引事件细节。</p>
 *
 * <p>默认事务边界仍由调用方（{@code DocumentUploadService} 的 {@code @Transactional} 方法）维持，
 * 本组件为无状态协作对象。</p>
 */
@Slf4j
@Component
public class UploadedDocumentFinalizer {

    private final DocumentMapper documentMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final VectorIngestionService vectorIngestionService;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    private final IngestionJobService ingestionJobService;
    private final BackgroundJobService backgroundJobService;

    public UploadedDocumentFinalizer(DocumentMapper documentMapper,
                                     ApplicationEventPublisher applicationEventPublisher,
                                     VectorIngestionService vectorIngestionService,
                                     ElasticsearchChunkIndexService elasticsearchChunkIndexService,
                                     IngestionJobService ingestionJobService,
                                     BackgroundJobService backgroundJobService) {
        this.documentMapper = documentMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.vectorIngestionService = vectorIngestionService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.ingestionJobService = ingestionJobService;
        this.backgroundJobService = backgroundJobService;
    }

    /**
     * 持久化文档元数据并登记文档（插入 Document 表 + 写入可靠任务队列）。
     *
     * <p><b>为什么入队要在调用方的事务里</b>：本方法运行在上传的 @Transactional 内，
     * 任务行和文档行同事务落库——要么都提交（文档存在且任务存在，Worker 接手），
     * 要么都回滚。这是"数据库当队列"独有的强一致红利，MQ 方案要用
     * Outbox 模式迂回才能拿到（08 篇 Q6）。</p>
     *
     * @param input 文档元数据输入
     * @return 已入库（含自增 id）的文档实体
     */
    public DocumentEntity finalizeUploaded(NewFileInput input) {
        DocumentEntity document = buildDocument(input);
        documentMapper.insert(document);
        log.info("文档元数据入库完成: documentId={}, groupId={}, status={}",
                document.getId(), input.groupId(), document.getStatus());
        ingestionJobService.registerIngestDocumentJob(document.getId(), input.groupId());
        log.info("已登记摄入任务记录: documentId={}, groupId={}", document.getId(), input.groupId());
        enqueueIngestionJob(document.getId(), input.groupId(), 0);
        return document;
    }

    /**
     * 把文档 ETL 任务写入可靠任务队列（background_job 表，08 篇主链路）。
     *
     * <p><b>幂等键为什么带 retry 代数</b>：键 = {@code INGEST:documentId:retry}。
     * 同一代数内重复触发（用户连点、事件重放）命中幂等键直接复用；
     * 用户对失败文档点"重试"（DocumentDeleteService 重新触发）时代数 +1，
     * 新键不会撞上已终态的旧任务。死信任务的可重入由 enqueue 的"死信复活"分支兜底。</p>
     *
     * <p><b>失败语义</b>：入队失败（数据库异常）向上抛出让上传事务回滚——
     * 没有 ETL 任务的文档永远不会变 READY，宁可上传失败也不能留下"假文档"。</p>
     */
    private void enqueueIngestionJob(Long documentId, Long groupId, int retryGeneration) {
        String payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("groupId", groupId));
        } catch (Exception ignored) {
            payload = "{}";
        }
        backgroundJobService.enqueue(new BackgroundJobService.NewBackgroundJobCommand(
                "INGEST_DOCUMENT",
                "DOCUMENT",
                documentId,
                "INGEST:" + documentId + ":" + retryGeneration,
                payload,
                3));
        log.info("已入队文档ETL任务: documentId={}, groupId={}, retryGeneration={}",
                documentId, groupId, retryGeneration);
    }

    private DocumentEntity buildDocument(NewFileInput input) {
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity document = new DocumentEntity();
        document.setGroupId(input.groupId());
        document.setUploaderUserId(input.userId());
        document.setFileName(input.fileName());
        document.setFileExt(input.fileExt());
        document.setContentType(input.contentType());
        document.setFileSize(input.fileSize());
        document.setFileHash(input.fileHash());
        document.setStorageBucket(input.bucket());
        document.setStorageObjectKey(input.objectKey());
        document.setStatus(DocumentStatus.PROCESSING.name());
        document.setDeleted(false);
        document.setUploadedAt(now);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    /**
     * 补偿清理已登记文档的向量 / ES chunk 索引（外部索引登记失败后调用）。
     *
     * @param document 已落库或仅部分完成的文档实体；为 null 或未分配 id 时直接跳过
     */
    public void compensateExternalIndexes(DocumentEntity document) {
        if (document == null || document.getId() == null) {
            return;
        }
        try {
            vectorIngestionService.deleteDocumentVectors(document.getId());
        } catch (RuntimeException exception) {
            log.warn("文档失败补偿时删除向量失败: documentId={}, reason={}",
                    document.getId(), exception.getMessage());
        }
        try {
            elasticsearchChunkIndexService.deleteDocumentChunks(document.getId());
        } catch (RuntimeException exception) {
            log.warn("文档失败补偿时删除 ES 索引失败: documentId={}, reason={}",
                    document.getId(), exception.getMessage());
        }
    }

    /** 上传结果文档的元数据输入。 */
    public record NewFileInput(Long groupId,
                               Long userId,
                               String fileName,
                               String fileExt,
                               String contentType,
                               Long fileSize,
                               String fileHash,
                               String bucket,
                               String objectKey) {
    }
}
