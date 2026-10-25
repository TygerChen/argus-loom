package com.argus.rag.qa.rag;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.qa.model.EvidenceLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReadyChunkDocumentRetriever} 检索门面契约：非法 groupId / 损坏的预取证据
 * 必须明确拦截（fail-closed），合法场景放行到 {@link HybridChunkRetrievalService}；
 * 预取命中时不重复检索。用真实 Spring AI {@link Query} record 构造输入，不改接口。
 */
@DisplayName("检索门面参数与预取校验（ReadyChunkDocumentRetriever）")
class ReadyChunkDocumentRetrieverTest {

    private HybridChunkRetrievalService hybrid;
    private ReadyChunkDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        hybrid = mock(HybridChunkRetrievalService.class);
        retriever = new ReadyChunkDocumentRetriever(hybrid, 5);
    }

    private Query queryWith(Long groupId) {
        return new Query("本月利润如何", List.of(), Map.of("groupId", groupId));
    }

    @Test
    void rejectsNullQuery() {
        BusinessException ex = assertThrows(BusinessException.class, () -> retriever.retrieve((Query) null));
        assertEquals("检索请求不能为空", ex.getMessage());
    }

    @Test
    void rejectsMissingGroupId() {
        Query query = new Query("Q");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> retriever.retrieve(query));
        assertEquals("检索上下文缺少 groupId", ex.getMessage());
    }

    @Test
    void rejectsNonPositiveGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> retriever.retrieve(queryWith(0L)));
        assertEquals("groupId 非法", ex.getMessage());
        BusinessException neg = assertThrows(BusinessException.class,
                () -> retriever.retrieve(queryWith(-3L)));
        assertEquals("groupId 非法", neg.getMessage());
    }

    @Test
    void rejectsNonNumericGroupIdString() {
        Query query = new Query("Q", List.of(), Map.of("groupId", "abc"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> retriever.retrieve(query));
        assertEquals("检索上下文中的 groupId 非法", ex.getMessage());
    }

    @Test
    void numericGroupIdProceedsToRetrieval() {
        Document doc = new Document("证据一");
        when(hybrid.retrieve(7L, "Q", 5))
                .thenReturn(new RetrievedEvidenceBundle(List.of(doc), EvidenceLevel.SUFFICIENT, "g"));

        Query query = new Query("Q", List.of(), Map.of("groupId", "7"));
        List<Document> result = retriever.retrieve(query);

        assertEquals(List.of(doc), result);
        verify(hybrid, never()).retrieve(0L, "Q", 5);
    }

    @Test
    void prefetchedNonListIsRejected() {
        Query query = new Query("Q", List.of(), Map.of(
                "groupId", 3L,
                ReadyChunkDocumentRetriever.PREFETCHED_DOCUMENTS_CONTEXT_KEY, "oops"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> retriever.retrieve(query));
        assertEquals("检索上下文中的预取证据格式非法", ex.getMessage());
        verify(hybrid, never()).retrieve(anyLong(), anyString(), anyInt());
    }

    @Test
    void prefetchedListWithNonDocumentIsRejected() {
        Query query = new Query("Q", List.of(), Map.of(
                "groupId", 7L,
                ReadyChunkDocumentRetriever.PREFETCHED_DOCUMENTS_CONTEXT_KEY, List.of(new Object())));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> retriever.retrieve(query));
        assertEquals("检索上下文中的预取证据格式非法", ex.getMessage());
    }

    @Test
    void validPrefetchedDocumentsSkipRetrieval() {
        Document d1 = new Document("a");
        Document d2 = new Document("b");
        Query query = new Query("Q", List.of(), Map.of(
                "groupId", 7L,
                ReadyChunkDocumentRetriever.PREFETCHED_DOCUMENTS_CONTEXT_KEY, List.of(d1, d2)));

        List<Document> result = retriever.retrieve(query);

        assertEquals(List.of(d1, d2), result);
        assertTrue(result.contains(d1));
        // 预取命中即短路，不重复检索
        verify(hybrid, never()).retrieve(anyLong(), anyString(), anyInt());
    }
}