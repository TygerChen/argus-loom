package com.argus.rag.ingestion.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ES 索引全量重建：以 PostgreSQL 的 document_chunks 为准，清空并重写 Elasticsearch 索引。
 *
 * <p><b>为什么需要它</b>：ES 在本系统是可再生的派生数据（PG 才是事实源）——索引损坏、
 * mapping 变更、误删或换 ES 集群时，不需要重跑整个 ETL，直接从 PG 回灌即可。
 * 面试高频题"ES 数据丢了怎么办"的工程答案就是这个端点。</p>
 *
 * <p><b>并发防护</b>：进程内 AtomicBoolean 防止重建被并发触发（重复清空/写入会造成
 * 数据混乱）。注意该防护是单实例语义，多实例部署时应由管理员保证不同时触发
 * （与整体单实例部署前提一致，见多实例审计文档）。</p>
 *
 * <p><b>幂等</b>：重建流程自身可安全重跑——先全量删除再写入，中断后重跑即重新对齐。
 * 单文档写入沿用 ETL 的 bulk 路径，chunk 数据以 PG 为准。</p>
 */
@Service
public class ElasticsearchRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchRebuildService.class);

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ElasticsearchChunkIndexService chunkIndexService;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public ElasticsearchRebuildService(DocumentMapper documentMapper,
                                       DocumentChunkMapper documentChunkMapper,
                                       ElasticsearchChunkIndexService chunkIndexService) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.chunkIndexService = chunkIndexService;
    }

    /** 重建结果统计。 */
    public record RebuildResult(int documents, int indexedChunks, long elapsedMs) {}

    /**
     * 全量重建：清空 ES 全部切片 → 按 READY 文档逐个从 PG 回灌。
     *
     * @return 统计（重建的文档数、写入的切片数、耗时毫秒）
     * @throws BusinessException 已有重建在进行中
     */
    public RebuildResult rebuildAll() {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new BusinessException("ES 索引重建已在进行中，请稍后再试");
        }
        try {
            long start = System.currentTimeMillis();
            List<DocumentEntity> readyDocs = documentMapper.selectList(
                    new LambdaQueryWrapper<DocumentEntity>()
                            .eq(DocumentEntity::getStatus, DocumentStatus.READY.name())
                            .eq(DocumentEntity::getDeleted, false));
            log.info("ES 索引重建开始: readyDocuments={}", readyDocs.size());

            chunkIndexService.deleteAllChunks();

            int docsIndexed = 0;
            int indexedChunks = 0;
            for (DocumentEntity doc : readyDocs) {
                List<DocumentChunkEntity> chunks = documentChunkMapper.selectByDocumentId(doc.getId());
                if (chunks == null || chunks.isEmpty()) {
                    log.warn("ES 索引重建跳过无切片文档: documentId={}, fileName={}",
                            doc.getId(), doc.getFileName());
                    continue;
                }
                chunkIndexService.indexReadyChunks(doc.getFileName(), chunks);
                docsIndexed++;
                indexedChunks += chunks.size();
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("ES 索引重建完成: documents={}, indexedChunks={}, elapsed={}ms",
                    docsIndexed, indexedChunks, elapsed);
            return new RebuildResult(docsIndexed, indexedChunks, elapsed);
        } finally {
            rebuilding.set(false);
        }
    }
}
