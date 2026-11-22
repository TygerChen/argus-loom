package com.argus.rag.qa.rag;

import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter.VectorHit;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 父子分块（取整节）契约（第十一步后的检索升级）：
 * 命中切片所属 sectionPath 非空且整节不超限 → 返回同节全部切片；
 * 无标题/整节过长 → 退化为历史邻居窗口；跨节不串取。
 */
@DisplayName("证据组装父子分块（EvidenceDocumentAssembler 取整节）")
class EvidenceDocumentAssemblerParentSectionTest {

    private static final Long DOC = 1L;
    private static final Long GROUP = 10L;

    /** 构造一个切片：chunkIndex + text + 可选 sectionPath（写入 metadataJson）。 */
    private static DocumentChunkEntity chunk(int index, String text, String sectionPath) {
        DocumentChunkEntity c = new DocumentChunkEntity();
        c.setId((long) index);
        c.setDocumentId(DOC);
        c.setGroupId(GROUP);
        c.setChunkIndex(index);
        c.setChunkText(text);
        c.setMetadataJson(sectionPath == null ? null : "{\"sectionPath\":\"" + sectionPath + "\"}");
        return c;
    }

    /** 构造命中 chunkIndex 的类簇。 */
    private static RetrievalCluster clusterHit(int chunkIndex) {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(
                new VectorHit(DOC, (long) chunkIndex, chunkIndex, "文本", 0.9));
        c.mergeVectorHit(new VectorHit(DOC, (long) chunkIndex, chunkIndex, "文本", 0.9), 1);
        return new RetrievalCluster(c);
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("groupId", GROUP);
        row.put("documentId", DOC);
        row.put("fileName", "报销制度.md");
        return row;
    }

    private String buildWindow(List<DocumentChunkEntity> chunks, RetrievalCluster cluster) {
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        when(mapper.selectReadyActiveChunksByDocumentId(GROUP, DOC)).thenReturn(chunks);
        EvidenceDocumentAssembler assembler = new EvidenceDocumentAssembler(1, mapper);
        return assembler.buildEvidenceWindow(row(), cluster, new LinkedHashMap<>());
    }

    @Test
    void hitInSectionReturnsWholeSectionNotCrossSection() {
        List<DocumentChunkEntity> chunks = List.of(
                chunk(0, "总则内容", "一、总则"),
                chunk(1, "报销上限是5000元", "二、报销上限"),
                chunk(2, "超出需总经理审批", "二、报销上限"),
                chunk(3, "流程内容", "三、报销流程"));
        String evidence = buildWindow(chunks, clusterHit(1));
        assertNotNull(evidence);
        // 同节两个切片都进
        assertTrue(evidence.contains("报销上限是5000元"));
        assertTrue(evidence.contains("超出需总经理审批"));
        // 相邻节不串取
        assertFalse(evidence.contains("总则内容"));
        assertFalse(evidence.contains("流程内容"));
    }

    @Test
    void noSectionPathFallsBackToNeighborWindow() {
        List<DocumentChunkEntity> chunks = List.of(
                chunk(0, "前文", null),
                chunk(1, "命中内容", null),
                chunk(2, "后文", null));
        String evidence = buildWindow(chunks, clusterHit(1));
        assertNotNull(evidence);
        // 无 sectionPath → 邻居窗口 ±1：三块都进
        assertTrue(evidence.contains("前文"));
        assertTrue(evidence.contains("命中内容"));
        assertTrue(evidence.contains("后文"));
    }

    @Test
    void oversizedSectionFallsBackToNeighborWindow() {
        // 同节 5 块、每块约 700 字 → 整节总长超 3000，触发退化为邻居窗口。
        // 命中 index=2：整节拼接会取 0..4 全部 5 块；退化窗口只取命中块 ±1（1..3）。
        String fill = "填".repeat(700);
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            chunks.add(chunk(i, "块" + i + "标记" + fill, "二、报销上限"));
        }
        String evidence = buildWindow(chunks, clusterHit(2));
        assertNotNull(evidence);
        // 退化窗口只含命中块 ±1
        assertTrue(evidence.contains("块1标记"), "退化窗口应含前一邻居块");
        assertTrue(evidence.contains("块2标记"), "退化窗口应含命中块");
        assertTrue(evidence.contains("块3标记"), "退化窗口应含后一邻居块");
        // 整节两端的块不应进入（若未退化取整节，会拼入全部 5 块）
        assertFalse(evidence.contains("块0标记"), "整节过长应退化为窗口，不取整节全部");
        assertFalse(evidence.contains("块4标记"), "整节过长应退化为窗口，不取整节全部");
    }
}
