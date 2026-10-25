package com.argus.rag.qa.rag;

import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService.KeywordHit;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter.VectorHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalCluster} 聚合行为锁（纯 JVM、同包 package-private 直调、零 IO/DB）。
 * <p>锁契约：连续性判定（同文档序号相邻）；add 维护范围/评分取 max/源聚合；
 * primary 取最高 rankingScore、平手取更小 chunkIndex；source 三态；窗口扩展下界钳 0。</p>
 */
@DisplayName("检索类簇（RetrievalCluster 连续性/评分/源聚合/主候选）")
class RetrievalClusterTest {

    private static final Long DOC = 7L;

    private static VectorHit vh(int chunk, double score) {
        return new VectorHit(DOC, (long) chunk, chunk, "文本", score);
    }

    private static RetrievalCandidate candidate(int chunk, double score, int rank, int merges) {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(vh(chunk, score));
        for (int i = 0; i < merges; i++) {
            c.mergeVectorHit(vh(chunk, score), rank);
        }
        return c;
    }

    private static RetrievalCandidate keywordCandidate(int chunk, long chunkId, double normScore) {
        KeywordHit h = new KeywordHit(DOC, chunkId, chunk, "文件", "文本", 0D, normScore);
        RetrievalCandidate c = RetrievalCandidate.fromKeywordHit(h);
        c.mergeKeywordHit(h, 1);
        return c;
    }

    // ---------- 构造 / 连续性 ----------

    @Test
    void constructorSetsRangeFromSeed() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        assertEquals(DOC, cluster.documentId());
        assertEquals(Integer.valueOf(3), cluster.primaryChunkIndex());
    }

    @Test
    void isContinuousWithAdjacentSameDocument() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        assertTrue(cluster.isContinuousWith(candidate(4, 0.4, 1, 1)));
        // 序号跳跃——不连续
        assertFalse(cluster.isContinuousWith(candidate(5, 0.4, 1, 1)));
        // 不同文档——不连续
        RetrievalCandidate other = RetrievalCandidate.fromVectorHit(
                new VectorHit(9L, 4L, 4, "文本", 0.4));
        other.mergeVectorHit(new VectorHit(9L, 4L, 4, "文本", 0.4), 1);
        assertFalse(cluster.isContinuousWith(other));
    }

    @Test
    void addExtendsRangeAndTracksMaxScores() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        cluster.add(candidate(5, 0.8, 1, 1));
        cluster.add(candidate(4, 0.6, 1, 1));
        // primary 仍为最早 seed（3）——排名分数相同下更小索引优先
        assertEquals(Integer.valueOf(3), cluster.primaryChunkIndex());
        assertEquals(0.8, cluster.vectorScore(), 1e-9);
    }

    @Test
    void addUnionsVectorAndKeywordSourcesToBoth() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        cluster.add(keywordCandidate(4, 40L, 0.6));
        assertEquals("BOTH", cluster.source());
    }

    @Test
    void keywordOnlySourceStaysKeyword() {
        RetrievalCluster cluster = new RetrievalCluster(keywordCandidate(1, 10L, 0.5));
        assertEquals("KEYWORD", cluster.source());
    }

    // ---------- primary 选择 ----------

    @Test
    void primaryChoosesHigherRankingScore() {
        // A@chunk 3 合并 rank=2 → rankingScore 0.5；B@chunk 9 合并 rank=1 → 1.0
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 2, 1));
        cluster.add(candidate(9, 0.9, 1, 1));
        assertEquals(Integer.valueOf(9), cluster.primaryChunkIndex());
        assertEquals(Long.valueOf(9L), cluster.primaryChunkId());
    }

    @Test
    void primaryTieBreaksToSmallerChunkIndex() {
        // A@chunk 3 与 B@chunk 9 各合并 rank=1 → rankingScore 均为 1.0 → 平局取小索引 3
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        cluster.add(candidate(9, 0.9, 1, 1));
        assertEquals(Integer.valueOf(3), cluster.primaryChunkIndex());
        assertEquals(Long.valueOf(3L), cluster.primaryChunkId());
    }

    // ---------- 来源 / 窗口扩展 ----------

    @Test
    void sourceVectorOnlyWhenNoKeyword() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(1, 0.5, 1, 1));
        assertEquals("VECTOR", cluster.source());
    }

    @Test
    void expandedStartClampsAtZero() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        assertEquals(0, cluster.expandedStartChunkIndex(5));
        assertEquals(3, cluster.expandedStartChunkIndex(0));
    }

    @Test
    void expandedEndAddsWindow() {
        RetrievalCluster cluster = new RetrievalCluster(candidate(3, 0.5, 1, 1));
        assertEquals(5, cluster.expandedEndChunkIndex(2));
        assertEquals(3, cluster.expandedEndChunkIndex(0));
    }
}