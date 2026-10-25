package com.argus.rag.ingestion.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.engine.storage.ObjectStorageService;
import com.argus.rag.ingestion.service.pipeline.ChunkService;
import com.argus.rag.ingestion.service.pipeline.parser.DocumentParserFactory;
import com.argus.rag.ingestion.service.pipeline.transformer.StructureAwareChunkTransformer;
import com.argus.rag.ingestion.service.pipeline.transformer.TextCleanupTransformer;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EtlDocumentIngestionProcessor} ETL 摄入处理器契约（mock 全部协作者，纯 JVM/零 DB/零存储/零 LLM）。
 * 锁「文档不存在」短路：findDocument 返回空实体 → 拒绝「待入库文档不存在」，不触达读取/解析/清洗/切片/向量。
 * （完整 ETL 成功路径经 StoredDocumentReader 走真实 MinIO+POI，同 FinancePdfRenderer 先例不硬做。）
 */
@DisplayName("ETL 摄入处理器（EtlDocumentIngestionProcessor）")
class EtlDocumentIngestionProcessorTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final DocumentParserFactory parserFactory = mock(DocumentParserFactory.class);
    private final TextCleanupTransformer cleanTransformer = mock(TextCleanupTransformer.class);
    private final StructureAwareChunkTransformer chunkTransformer = mock(StructureAwareChunkTransformer.class);
    private final ChunkService chunkService = mock(ChunkService.class);
    private final VectorIngestionService vectorService = mock(VectorIngestionService.class);
    private final EtlDocumentIngestionProcessor processor = new EtlDocumentIngestionProcessor(
            documentMapper, storageService, parserFactory,
            cleanTransformer, chunkTransformer, chunkService, vectorService);

    @Test
    void processMissingDocumentThrowsWithoutTouchingPipeline() {
        when(documentMapper.selectByIdAndGroupId(eq(1L), eq(2L))).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> processor.process(1L, 2L));
        assertEquals("待入库文档不存在", ex.getMessage());
        verify(vectorService, never()).ingestChunks(org.mockito.ArgumentMatchers.any());
        verify(cleanTransformer, never()).apply(org.mockito.ArgumentMatchers.any());
    }
}