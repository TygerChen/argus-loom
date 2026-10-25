package com.argus.rag.qa.service;

import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.service.QaRetrievalService.QaRetrievalResult;
import com.argus.rag.qa.rag.HybridChunkRetrievalService;
import com.argus.rag.qa.rag.ReadyChunkDocumentRetriever;
import com.argus.rag.qa.rag.RetrievedEvidenceBundle;
import com.argus.rag.qa.support.CitationAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QaRetrievalService} QA 检索门面契约（mock 文档检索器/混合检索器/引用组装器，纯 JVM/零网络）：
 * - 单知识库：委托 documentRetriever 取证据束 + 引用组装并原样返回；
 * - 多知识库：仅对去重、剔除 null/非法 ID 后的组执行混合检索，空组短路返回空束，
 *   逐组汇总文档、按「最差」语义归并证据等级（INSUFFICIENT<WEAK<PARTIAL<SUFFICIENT）、
 *   取最新非空白指导语；
 * - 组间公平轮换合并、总量上限 overallTopK 截断、无 groupId 元数据的文档被剔除、
 *   命中但证据全 NONE 时兜底 WEAK；
 * - 诊断：perGroupTopK 正确透传给混合检索器。
 */
@DisplayName("QA 检索门面（QaRetrievalService）")
class QaRetrievalServiceTest {

    private ReadyChunkDocumentRetriever documentRetriever;
    private HybridChunkRetrievalService hybrid;
    private CitationAssembler citationAssembler;
    private QaRetrievalService service;
    private QaRetrievalService tinyService;

    private static final String QUERY = "q";
    private static final int PER_GROUP = 4;

    @BeforeEach
    void setUp() {
        documentRetriever = mock(ReadyChunkDocumentRetriever.class);
        hybrid = mock(HybridChunkRetrievalService.class);
        citationAssembler = mock(CitationAssembler.class);
        service = new QaRetrievalService(documentRetriever, hybrid, citationAssembler, PER_GROUP, 12);
        tinyService = new QaRetrievalService(documentRetriever, hybrid, citationAssembler, PER_GROUP, 2);
        when(citationAssembler.assembleDocuments(anyList())).thenReturn(List.of());
    }

    // ---------- helpers ----------

    private static Document doc(Long groupId, String text) {
        return groupId == null ? new Document(text) : new Document(text, Map.of("groupId", groupId));
    }

    private static RetrievedEvidenceBundle bundle(List<Document> docs, EvidenceLevel level, String guidance) {
        return new RetrievedEvidenceBundle(docs, level, guidance);
    }

    private void stubGroup(Long groupId, EvidenceLevel level, String guidance, Document... docs) {
        when(hybrid.retrieve(groupId, QUERY, PER_GROUP))
                .thenReturn(bundle(List.of(docs), level, guidance));
    }

    // ---------- 单知识库 ----------

    @Test
    void singleGroupDelegatesToRetrieverAndAssembler() {
        List<Document> docs = List.of(doc(6L, "x"));
        RetrievedEvidenceBundle bundle = bundle(docs, EvidenceLevel.SUFFICIENT, "g");
        when(documentRetriever.retrieveEvidence(6L, QUERY)).thenReturn(bundle);
        var fakeCitation = mock(com.argus.rag.qa.model.vo.AskQuestionResponse.Citation.class);
        when(citationAssembler.assembleDocuments(docs)).thenReturn(List.of(fakeCitation));

        QaRetrievalResult res = service.retrieveEvidence(6L, QUERY);

        assertEquals(bundle, res.bundle());
        assertSame(fakeCitation, res.citations().get(0));
        verify(documentRetriever).retrieveEvidence(6L, QUERY);
    }

    // ---------- 多知识库：入参短路 ----------

    @Test
    void multiGroupEmptyOrInvalidIdsReturnsEmptyAndSkipsRetrieval() {
        QaRetrievalResult res = service.retrieveEvidence(List.of(), QUERY);
        assertTrue(res.bundle().documents().isEmpty());
        assertEquals(EvidenceLevel.NONE, res.bundle().evidenceLevel());
        assertTrue(res.citations().isEmpty());
        verify(hybrid, never()).retrieve(anyLong(), anyString(), anyInt());
    }

    @Test
    void multiGroupDeduplicatesAndFiltersInvalidIds() {
        stubGroup(6L, EvidenceLevel.NONE, null);
        stubGroup(7L, EvidenceLevel.NONE, null);

        QaRetrievalResult res = service.retrieveEvidence(
                Arrays.asList(6L, 6L, null, 0L, -1L, 7L), QUERY);
        assertTrue(res.bundle().documents().isEmpty());
        verify(hybrid, times(1)).retrieve(eq(6L), eq(QUERY), eq(PER_GROUP));
        verify(hybrid, times(1)).retrieve(eq(7L), eq(QUERY), eq(PER_GROUP));
    }

    // ---------- 多知识库：聚合 ----------

    @Test
    void multiGroupAggregatesWorstEvidenceLevel() {
        stubGroup(6L, EvidenceLevel.SUFFICIENT, "g", doc(6L, "a"));
        stubGroup(7L, EvidenceLevel.INSUFFICIENT, "g2", doc(7L, "b"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L, 7L), QUERY);

        assertEquals(EvidenceLevel.INSUFFICIENT, res.bundle().evidenceLevel());
        assertEquals(2, res.bundle().documents().size());
    }

    @Test
    void multiGroupWorseWinsAcrossPartialWeak() {
        stubGroup(6L, EvidenceLevel.PARTIAL, null, doc(6L, "a1"), doc(6L, "a2"));
        stubGroup(7L, EvidenceLevel.WEAK, null, doc(7L, "b1"), doc(7L, "b2"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L, 7L), QUERY);

        assertEquals(EvidenceLevel.WEAK, res.bundle().evidenceLevel());
        assertEquals(4, res.bundle().documents().size());
    }

    @Test
    void multiGroupHitButNoValidEvidenceFallsBackToWeak() {
        stubGroup(6L, EvidenceLevel.NONE, null, doc(6L, "a"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L), QUERY);

        assertEquals(EvidenceLevel.WEAK, res.bundle().evidenceLevel());
        assertEquals(1, res.bundle().documents().size());
    }

    @Test
    void multiGroupGuideUsesLastNonBlank() {
        stubGroup(6L, EvidenceLevel.SUFFICIENT, "g1", doc(6L, "a"));
        stubGroup(7L, EvidenceLevel.SUFFICIENT, "g2", doc(7L, "b"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L, 7L), QUERY);
        assertEquals("g2", res.bundle().evidenceGuidance());

        // 空白指导语不清除已累积的
        stubGroup(6L, EvidenceLevel.SUFFICIENT, "g1", doc(6L, "a"));
        stubGroup(7L, EvidenceLevel.SUFFICIENT, "   ", doc(7L, "b"));
        QaRetrievalResult res2 = service.retrieveEvidence(List.of(6L, 7L), QUERY);
        assertEquals("g1", res2.bundle().evidenceGuidance());
    }

    @Test
    void multiGroupSkipsEmptyBundleGroups() {
        stubGroup(6L, EvidenceLevel.NONE, null); // 无文档
        stubGroup(7L, EvidenceLevel.SUFFICIENT, "g", doc(7L, "b"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L, 7L), QUERY);

        assertEquals(List.of("b"), res.bundle().documents().stream().map(Document::getText).toList());
        assertEquals(EvidenceLevel.SUFFICIENT, res.bundle().evidenceLevel());
    }

    // ---------- 多知识库：公平轮转 + 截断 ----------

    @Test
    void balanceRoundRobinsAcrossGroupsByInsertionOrder() {
        stubGroup(6L, EvidenceLevel.SUFFICIENT, null, doc(6L, "a1"), doc(6L, "a2"));
        stubGroup(7L, EvidenceLevel.SUFFICIENT, null, doc(7L, "b1"), doc(7L, "b2"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L, 7L), QUERY);
        List<String> texts = res.bundle().documents().stream().map(Document::getText).toList();

        assertEquals(List.of("a1", "b1", "a2", "b2"), texts);
    }

    @Test
    void balanceTruncatesToCap() {
        stubGroup(6L, EvidenceLevel.SUFFICIENT, null, doc(6L, "a1"), doc(6L, "a2"));
        stubGroup(7L, EvidenceLevel.SUFFICIENT, null, doc(7L, "b1"), doc(7L, "b2"));

        QaRetrievalResult res = tinyService.retrieveEvidence(List.of(6L, 7L), QUERY);

        assertEquals(List.of("a1", "b1"), res.bundle().documents().stream().map(Document::getText).toList());
    }

    @Test
    void balanceDropsDocumentsWithoutGroupIdMetadata() {
        stubGroup(6L, EvidenceLevel.SUFFICIENT, null, doc(6L, "a"), doc(null, "orphan"));

        QaRetrievalResult res = service.retrieveEvidence(List.of(6L), QUERY);

        assertEquals(List.of("a"), res.bundle().documents().stream().map(Document::getText).toList());
    }
}