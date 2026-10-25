package com.argus.rag.qa.rag;

import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.security.DocumentRoleGate;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索层角色隔离（单一授权点）+ 反泄露（fail-closed）测试。
 * 验证：未命中的受限文档切片绝不到达返回结果；无可读文档时直接返回“无信息”。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("检索层角色隔离（HybridChunkRetrievalService 授权点）")
class HybridChunkRetrievalRoleIsolationTest {

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
    @Mock
    private DocumentRoleGate roleGate;

    private HybridChunkRetrievalService service() {
        return new HybridChunkRetrievalService(
                vectorRetrievalAdapter,
                elasticsearchChunkIndexService,
                null, // youcomSearchService
                documentChunkMapper,
                queryPlanningService,
                documentMapper,
                null, // rerankingService
                roleGate,
                0
        );
    }

    @Test
    @DisplayName("fail-closed：当前角色无可检索文档 → 直接返回 NONE，连查询都不发（防旁路）")
    void emptyPermissionShortCircuitsWithoutQuerying() {
        when(roleGate.allowedDocumentIds(eq(1L))).thenReturn(Set.of());

        RetrievedEvidenceBundle bundle = service().retrieve(1L, "问我早安", 5);

        assertThat(bundle.documents()).isEmpty();
        assertThat(bundle.evidenceLevel()).isEqualTo(com.argus.rag.qa.model.EvidenceLevel.NONE);
        // 关键反旁路断言：完全不可用权限时，绝不触碰底层检索通道/DB
        verify(vectorRetrievalAdapter, never()).search(any(), eq("问我早安"), anyInt());
        verify(elasticsearchChunkIndexService, never()).search(any(), eq("问我早安"), anyInt());
    }

    @Test
    @DisplayName("受限文档的切片在授权点被剔除，绝不到达返给用户的证据")
    void restrictedChunkIsDropped() {
        String q = "公司财务政策是什么";
        when(roleGate.allowedDocumentIds(eq(1L))).thenReturn(Set.of(10L, 20L)); // 文档 500 受限
        when(queryPlanningService.plan(q)).thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, List.of(q)));
        when(vectorRetrievalAdapter.search(eq(1L), eq(q), anyInt()))
                .thenReturn(List.of(
                        new PgVectorRetrievalAdapter.VectorHit(10L, 101L, 0, "doc1", 0.97D),   // 允许
                        new PgVectorRetrievalAdapter.VectorHit(500L, 501L, 0, "finance", 0.98D), // 受限！前端必出
                        new PgVectorRetrievalAdapter.VectorHit(20L, 201L, 0, "doc3", 0.96D)      // 允许
                ));
        when(elasticsearchChunkIndexService.search(eq(1L), eq(q), anyInt())).thenReturn(List.of());
        // 仅允许的文档提供可组装的行与活动切片
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenAnswer(invocation -> invocation.<List<Long>>getArgument(1).stream()
                        .map(this::rowOf)
                        .toList());
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenReturn(List.of(chunk(101L, 10L, 0, "doc1")));
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(20L)))
                .thenReturn(List.of(chunk(201L, 20L, 0, "doc3")));

        List<Document> docs = service().retrieve(1L, q, 5).documents();

        assertThat(docs).isNotEmpty();
        List<Long> seenDocIds = docs.stream().map(d -> d.getMetadata().get("documentId")).map(Long.class::cast).toList();
        assertThat(seenDocIds).doesNotContain(500L);        // 受限文档绝不出现
        // 不再为受限文档 500 拉任何窗口
        verify(documentChunkMapper, never())
                .selectReadyActiveChunksByDocumentId(eq(1L), eq(500L));
    }

    @Test
    @DisplayName("允许文档正常放行（不因门槛过度收紧）")
    void allowedDocFlowsThrough() {
        String q = "常见制度";
        when(roleGate.allowedDocumentIds(eq(1L))).thenReturn(Set.of(10L));
        when(queryPlanningService.plan(q)).thenReturn(new QueryPlanResult(QueryPlanStrategy.DIRECT, List.of(q)));
        when(vectorRetrievalAdapter.search(eq(1L), eq(q), anyInt()))
                .thenReturn(List.of(new PgVectorRetrievalAdapter.VectorHit(10L, 101L, 0, "doc1", 0.99D)));
        when(elasticsearchChunkIndexService.search(eq(1L), eq(q), anyInt())).thenReturn(List.of());
        when(documentChunkMapper.selectQaReadyChunksByIds(eq(1L), anyList()))
                .thenAnswer(inv -> inv.<List<Long>>getArgument(1).stream().map(this::rowOf).toList());
        when(documentChunkMapper.selectReadyActiveChunksByDocumentId(eq(1L), eq(10L)))
                .thenReturn(List.of(chunk(101L, 10L, 0, "doc1")));

        List<Document> docs = service().retrieve(1L, q, 5).documents();
        assertThat(docs)
                .allMatch(d -> d.getMetadata().get("documentId").equals(10L));
    }

    private Map<String, Object> rowOf(Long chunkId) {
        if (chunkId == 101L) return row(101L, 10L, 0, "doc1.md");
        if (chunkId == 201L) return row(201L, 20L, 0, "doc3.md");
        if (chunkId == 501L) return row(501L, 500L, 0, "finance.xlsx");
        throw new IllegalArgumentException("unexpected chunk: " + chunkId);
    }

    private Map<String, Object> row(Long chunkId, Long documentId, Integer chunkIndex, String fileName) {
        return Map.of(
                "groupId", 1L,
                "chunkId", chunkId,
                "documentId", documentId,
                "chunkIndex", chunkIndex,
                "chunkText", fileName + " 内容",
                "fileName", fileName
        );
    }

    private DocumentChunkEntity chunk(Long chunkId, Long documentId, Integer chunkIndex, String text) {
        DocumentChunkEntity e = new DocumentChunkEntity();
        e.setId(chunkId);
        e.setGroupId(1L);
        e.setDocumentId(documentId);
        e.setChunkIndex(chunkIndex);
        e.setChunkText(text);
        return e;
    }
}