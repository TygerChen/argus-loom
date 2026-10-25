package com.argus.rag.qa.rag;

import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.QueryPlanResult;
import com.argus.rag.qa.model.QueryPlanStrategy;
import com.argus.rag.qa.service.QueryPlanningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("混合切片检索服务")
class HybridChunkRetrievalServiceTest {

    @Mock
    private PgVectorRetrievalAdapter vectorRetrievalAdapter;

    @Mock
    private ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private QueryPlanningService queryPlanningService;

    @Mock
    private DocumentMapper documentMapper;

    @Test
    @DisplayName("按每个文档总结时应优先覆盖不同文档")
    void shouldPreferDifferentDocumentsWhenQuestionAsksEachDocumentSummary() {
        String question = "请根据团队所有文档，总结每个文档的知识要点";
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter,
                elasticsearchChunkIndexService,
                null, // youcomSearchService
                documentChunkMapper,
                queryPlanningService,
                documentMapper,
                null, // rerankingService
                0
        );

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, List.of(question)));
        when(vectorRetrievalAdapter.search(eq(1L), eq(question), anyInt()))
                .thenReturn(List.of(
                        new PgVectorRetrievalAdapter.VectorHit(10L, 101L, 0, "doc1 chunk0", 0.99D),
                        new PgVectorRetrievalAdapter.VectorHit(10L, 102L, 1, "doc1 chunk1", 0.98D),
                        new PgVectorRetrievalAdapter.VectorHit(10L, 103L, 2, "doc1 chunk2", 0.97D),
                        new PgVectorRetrievalAdapter.VectorHit(20L, 201L, 0, "doc2 chunk0", 0.96D),
                        new PgVectorRetrievalAdapter.VectorHit(30L, 301L, 0, "doc3 chunk0", 0.95D)
                ));
        when(elasticsearchChunkIndexService.search(eq(1L), eq(question), anyInt()))
                .thenReturn(List.of());
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenAnswer(invocation -> invocation.<List<Long>>getArgument(1).stream()
                        .map(this::readyRow)
                        .toList());
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenReturn(List.of(
                        chunk(101L, 10L, 0, "doc1 chunk0"),
                        chunk(102L, 10L, 1, "doc1 chunk1"),
                        chunk(103L, 10L, 2, "doc1 chunk2")
                ));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(20L)))
                .thenReturn(List.of(chunk(201L, 20L, 0, "doc2 chunk0")));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(30L)))
                .thenReturn(List.of(chunk(301L, 30L, 0, "doc3 chunk0")));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 3);

        List<Object> documentIds = bundle.documents().stream()
                .map(Document::getMetadata)
                .map(metadata -> metadata.get("documentId"))
                .toList();
        assertThat(documentIds).containsExactly(10L, 20L, 30L);
    }

    private Map<String, Object> readyRow(Long chunkId, Long documentId, Integer chunkIndex, String fileName) {
        return Map.of(
                "groupId", 1L,
                "chunkId", chunkId,
                "documentId", documentId,
                "chunkIndex", chunkIndex,
                "chunkText", fileName + " text",
                "fileName", fileName
        );
    }

    private Map<String, Object> readyRow(Long chunkId) {
        return switch (chunkId.intValue()) {
            case 101 -> readyRow(101L, 10L, 0, "doc1.md");
            case 102 -> readyRow(102L, 10L, 1, "doc1.md");
            case 103 -> readyRow(103L, 10L, 2, "doc1.md");
            case 201 -> readyRow(201L, 20L, 0, "doc2.md");
            case 301 -> readyRow(301L, 30L, 0, "doc3.md");
            default -> throw new IllegalArgumentException("Unexpected chunkId: " + chunkId);
        };
    }

    private DocumentChunkEntity chunk(Long chunkId, Long documentId, Integer chunkIndex, String text) {
        DocumentChunkEntity entity = new DocumentChunkEntity();
        entity.setId(chunkId);
        entity.setGroupId(1L);
        entity.setDocumentId(documentId);
        entity.setChunkIndex(chunkIndex);
        entity.setChunkText(text);
        return entity;
    }

    @Test
    @DisplayName("GLOBAL策略时应当遍历全部READY状态文档的首个切片")
    void shouldTraverseAllReadyDocumentsWhenStrategyIsGlobal() {
        String question = "知识库里有些什么文档，总结大纲";
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter,
                elasticsearchChunkIndexService,
                null, // youcomSearchService
                documentChunkMapper,
                queryPlanningService,
                documentMapper,
                null, // rerankingService
                0
        );

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.GLOBAL, List.of(question)));

        com.argus.rag.document.model.entity.DocumentEntity doc1 = new com.argus.rag.document.model.entity.DocumentEntity();
        doc1.setId(10L);
        doc1.setGroupId(1L);
        doc1.setFileName("doc1.txt");
        doc1.setStatus("READY");
        doc1.setDeleted(false);

        com.argus.rag.document.model.entity.DocumentEntity doc2 = new com.argus.rag.document.model.entity.DocumentEntity();
        doc2.setId(20L);
        doc2.setGroupId(1L);
        doc2.setFileName("doc2.txt");
        doc2.setStatus("READY");
        doc2.setDeleted(false);

        when(documentMapper.selectList(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc1, doc2));

        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(1L, 10L))
                .thenReturn(List.of(
                        chunk(101L, 10L, 0, "doc1 chunk0 text"),
                        chunk(102L, 10L, 1, "doc1 chunk1 text")
                ));

        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(1L, 20L))
                .thenReturn(List.of(
                        chunk(201L, 20L, 0, "doc2 chunk0 text")
                ));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 5);

        assertThat(bundle.documents()).hasSize(2);
        assertThat(bundle.documents().get(0).getText()).contains("doc1 chunk0 text");
        assertThat(bundle.documents().get(1).getText()).contains("doc2 chunk0 text");
        assertThat(bundle.documents().get(0).getMetadata().get("documentId")).isEqualTo(10L);
        assertThat(bundle.documents().get(0).getMetadata().get("chunkIndex")).isEqualTo(0);
        assertThat(bundle.documents().get(0).getMetadata().get("retrievalSource")).isEqualTo("GLOBAL");
        assertThat(bundle.evidenceLevel()).isEqualTo(com.argus.rag.qa.model.EvidenceLevel.SUFFICIENT);
    }

    @Test
    @DisplayName("同一切片在向量+关键词双通道命中时来源为 BOTH 且 RRF 分数累加")
    void shouldMarkBothSourcesAndAccumulateRrfWhenChunkHitInBothChannels() {
        String question = "如何使用该文档";
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter, elasticsearchChunkIndexService, null, documentChunkMapper,
                queryPlanningService, documentMapper, 0);

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, java.util.List.of(question)));
        // 同一 chunk 101 在向量与关键词两路都以 rank1 命中
        when(vectorRetrievalAdapter.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of(new PgVectorRetrievalAdapter.VectorHit(10L, 101L, 0, "doc1 chunk0", 0.90D)));
        when(elasticsearchChunkIndexService.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of(new ElasticsearchChunkIndexService.KeywordHit(10L, 101L, 0, "doc1.md", "doc1 chunk0", 8.0D, 0.70D)));
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenReturn(java.util.List.of(readyRow(101L, 10L, 0, "doc1.md")));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenReturn(java.util.List.of(chunk(101L, 10L, 0, "doc1 chunk0")));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 5);

        assertThat(bundle.documents()).hasSize(1);
        Map<String, Object> meta = bundle.documents().get(0).getMetadata();
        assertThat(meta.get("retrievalSource")).isEqualTo("BOTH");
        // 两路各 rank=1 → rankScore(1)=1/1 各一分，累加为 2.0
        assertThat(meta.get("hybridScore")).isEqualTo(2.0D);
        assertThat(meta.get("vectorScore")).isEqualTo(0.90D);
        assertThat(meta.get("keywordScore")).isEqualTo(0.70D);
        // 单文档即使双源，size<2 不满足 SUFFICIENT，判定为 PARTIAL
        assertThat(bundle.evidenceLevel()).isEqualTo(com.argus.rag.qa.model.EvidenceLevel.PARTIAL);
    }

    @Test
    @DisplayName("仅关键词通道命中时来源标记为 KEYWORD")
    void shouldMarkKeywordOnlySource() {
        String question = "关键词检索测试";
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter, elasticsearchChunkIndexService, null, documentChunkMapper,
                queryPlanningService, documentMapper, 0);

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, java.util.List.of(question)));
        when(vectorRetrievalAdapter.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of());
        when(elasticsearchChunkIndexService.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of(new ElasticsearchChunkIndexService.KeywordHit(20L, 201L, 3, "doc2.md", "doc2 chunk3", 6.0D, 0.55D)));
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenReturn(java.util.List.of(readyRow(201L, 20L, 3, "doc2.md")));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(20L)))
                .thenReturn(java.util.List.of(chunk(201L, 20L, 3, "doc2 chunk3")));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 8);

        assertThat(bundle.documents()).hasSize(1);
        assertThat(bundle.documents().get(0).getMetadata().get("retrievalSource")).isEqualTo("KEYWORD");
        assertThat(bundle.documents().get(0).getMetadata().get("vectorScore")).isEqualTo(0.0D);
    }

    @Test
    @DisplayName("邻居窗口向后扩展计入相邻切片文本")
    void shouldExpandEvidenceWindowWithNeighborWindow() {
        String question = "窗口扩展测试问题";
        // neighborWindow=1：命中切片前后各扩展 1 个相邻切片
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter, elasticsearchChunkIndexService, null, documentChunkMapper,
                queryPlanningService, documentMapper, 1);

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, java.util.List.of(question)));
        // 主命中切片 chunkIndex=1，扩展窗口包含相邻 0 和 2
        when(vectorRetrievalAdapter.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of(new PgVectorRetrievalAdapter.VectorHit(10L, 102L, 1, "doc1 chunk1", 0.95D)));
        when(elasticsearchChunkIndexService.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of());
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenReturn(java.util.List.of(readyRow(102L, 10L, 1, "doc1.md")));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenReturn(java.util.List.of(
                        chunk(101L, 10L, 0, "window chunk 0"),
                        chunk(102L, 10L, 1, "window chunk 1"),
                        chunk(103L, 10L, 2, "window chunk 2")));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 8);

        assertThat(bundle.documents()).hasSize(1);
        assertThat(bundle.documents().get(0).getText()).contains("window chunk 0");
        assertThat(bundle.documents().get(0).getText()).contains("window chunk 2");
        assertThat(bundle.documents().get(0).getMetadata().get("startChunkIndex")).isEqualTo(0);
        assertThat(bundle.documents().get(0).getMetadata().get("endChunkIndex")).isEqualTo(2);
    }

    @Test
    @DisplayName("仅单来源单一证据且非双通道时证据等级为 WEAK")
    void shouldMarkWeakEvidenceForSingleLowCoverageHit() {
        String question = "单一命中示例问题";
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter, elasticsearchChunkIndexService, null, documentChunkMapper,
                queryPlanningService, documentMapper, 0);

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, java.util.List.of(question)));
        when(vectorRetrievalAdapter.search(eq(1L), eq(question), anyInt()))
                .thenReturn(java.util.List.of(new PgVectorRetrievalAdapter.VectorHit(10L, 101L, 0, "doc1 chunk0", 0.20D)));
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenReturn(java.util.List.of(readyRow(101L, 10L, 0, "doc1.md")));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenReturn(java.util.List.of(chunk(101L, 10L, 0, "doc1 chunk0")));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 5);

        assertThat(bundle.documents()).hasSize(1);
        assertThat(bundle.evidenceLevel()).isEqualTo(EvidenceLevel.WEAK);
    }
}
