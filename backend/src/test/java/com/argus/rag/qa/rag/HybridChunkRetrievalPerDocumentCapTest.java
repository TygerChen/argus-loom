package com.argus.rag.qa.rag;

import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 混合检索「每文档 chunk 数上限」端到端约束。
 * <p>方案要求：即使某个文档在向量/关键词通道全面命中，也只允许它占用
 * {@code maxChunksPerDocument} 个上下文席位，防止单文档霸占答案。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("混合检索每文档 chunk 上限")
class HybridChunkRetrievalPerDocumentCapTest {

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
    @DisplayName("单文档全面命中时，上下文中它的切片数不超过配置上限")
    void dominantDocumentIsCappedToConfiguredChunkBudget() {
        String question = ".doc 内部制度汇总";
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                vectorRetrievalAdapter,
                elasticsearchChunkIndexService,
                null,
                documentChunkMapper,
                queryPlanningService,
                documentMapper,
                null,
                null,
                0,
                new RetrievalOptions(50, 50, 50, 2)
        );

        when(queryPlanningService.plan(question))
                .thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, List.of(question)));
        // doc 10 前 6 个切片全部命中，doc 20 仅 1 个切片命中
        when(vectorRetrievalAdapter.search(eq(1L), eq(question), anyInt()))
                .thenReturn(List.of(
                        vectorHit(10L, 101L, 0), vectorHit(10L, 102L, 1), vectorHit(10L, 103L, 2),
                        vectorHit(10L, 104L, 3), vectorHit(10L, 105L, 4), vectorHit(10L, 106L, 5),
                        vectorHit(20L, 201L, 0)
                ));
        when(elasticsearchChunkIndexService.search(eq(1L), eq(question), anyInt()))
                .thenReturn(List.of());
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenAnswer(invocation -> invocation.<List<Long>>getArgument(1).stream()
                        .map(this::readyRow)
                        .toList());
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenAnswer(invocation -> List.of(
                        chunk(101L, 10L, 0), chunk(102L, 10L, 1), chunk(103L, 10L, 2),
                        chunk(104L, 10L, 3), chunk(105L, 10L, 4), chunk(106L, 10L, 5)
                ));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(20L)))
                .thenReturn(List.of(chunk(201L, 20L, 0)));

        RetrievedEvidenceBundle bundle = service.retrieve(1L, question, 4);

        List<Object> documentIds = bundle.documents().stream()
                .map(Document::getMetadata)
                .map(metadata -> metadata.get("documentId"))
                .toList();
        // doc 10 的候选被上限压到 2 个切片（聚成一个证据簇），doc 20 的切片得以进入上下文
        assertThat(documentIds).containsExactly(10L, 20L);
        Map<Object, Object> doc10Metadata = bundle.documents().stream()
                .filter(document -> Long.valueOf(10L).equals(document.getMetadata().get("documentId")))
                .findFirst()
                .map(document -> Map.<Object, Object>copyOf(document.getMetadata()))
                .orElseThrow();
        // 窗口只覆盖切片 0-1，而不是它全面命中的 0-5
        assertThat(doc10Metadata.get("startChunkIndex")).isEqualTo(0);
        assertThat(doc10Metadata.get("endChunkIndex")).isEqualTo(1);
    }

    private PgVectorRetrievalAdapter.VectorHit vectorHit(long documentId, long chunkId, int chunkIndex) {
        return new PgVectorRetrievalAdapter.VectorHit(documentId, chunkId, chunkIndex, "text-" + chunkId, 0.9D);
    }

    private Map<String, Object> readyRow(Long chunkId) {
        return switch (chunkId.intValue()) {
            case 101 -> readyRow(101L, 10L, 0);
            case 102 -> readyRow(102L, 10L, 1);
            case 103 -> readyRow(103L, 10L, 2);
            case 104 -> readyRow(104L, 10L, 3);
            case 105 -> readyRow(105L, 10L, 4);
            case 106 -> readyRow(106L, 10L, 5);
            case 201 -> readyRow(201L, 20L, 0);
            default -> throw new IllegalArgumentException("Unexpected chunkId: " + chunkId);
        };
    }

    private Map<String, Object> readyRow(Long chunkId, Long documentId, Integer chunkIndex) {
        return Map.of(
                "groupId", 1L,
                "chunkId", chunkId,
                "documentId", documentId,
                "chunkIndex", chunkIndex,
                "chunkText", "doc text",
                "fileName", "doc" + documentId + ".md"
        );
    }

    private DocumentChunkEntity chunk(Long chunkId, Long documentId, Integer chunkIndex) {
        DocumentChunkEntity entity = new DocumentChunkEntity();
        entity.setId(chunkId);
        entity.setDocumentId(documentId);
        entity.setChunkIndex(chunkIndex);
        entity.setChunkText("doc text");
        return entity;
    }
}
