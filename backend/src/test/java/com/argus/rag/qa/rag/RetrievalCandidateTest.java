package com.argus.rag.qa.rag;

import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService.KeywordHit;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter.VectorHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RetrievalCandidate} 合并 / 源判定 / RRF 累加行为锁
 * （纯 JVM、同包 package-private 直调、零 IO/DB）。
 * <p>锁契约：双通道命中合并且各通道评分取 max、RRF 分数按 rank 累加；
 * source() 三态（VECTOR↔KEYWORD↔BOTH，全空回退 KEYWORD）；构造字段透传。</p>
 */
@DisplayName("检索候选项（RetrievalCandidate 合并逻辑/RRF/source）")
class RetrievalCandidateTest {

    private static final Long DOC = 7L;

    private static VectorHit vh(int chunk, double score) {
        return new VectorHit(DOC, (long) chunk, chunk, "文本", score);
    }

    private static KeywordHit kh(int chunk, double normScore) {
        return new KeywordHit(DOC, (long) chunk, chunk, "文件", "文本", 0D, normScore);
    }

    // ---------- source() 三态 ----------

    @Test
    void freshCandidateNoMatchSourceIsKeyword() {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(vh(1, 0.5));
        // 两个通道均未命中 → 回退 KEYWORD（真实行为）
        assertEquals("KEYWORD", c.source());
        assertEquals(0.0, c.rankingScore(), 1e-9);
    }

    @Test
    void vectorOnlySourceIsVector() {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(vh(1, 0.5));
        c.mergeVectorHit(vh(1, 0.5), 1);
        assertEquals("VECTOR", c.source());
    }

    @Test
    void keywordOnlySourceIsKeyword() {
        RetrievalCandidate c = RetrievalCandidate.fromKeywordHit(kh(1, 0.6));
        c.mergeKeywordHit(kh(1, 0.6), 1);
        assertEquals("KEYWORD", c.source());
    }

    @Test
    void bothChannelsSourceIsBoth() {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(vh(1, 0.5));
        c.mergeVectorHit(vh(1, 0.5), 1);
        c.mergeKeywordHit(kh(1, 0.6), 1);
        assertEquals("BOTH", c.source());
    }

    // ---------- 评分合并取 max ----------

    @Test
    void vectorScoreTakesMaxAcrossMerges() {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(vh(1, 0.5));
        c.mergeVectorHit(vh(1, 0.5), 1);
        c.mergeVectorHit(vh(1, 0.8), 2);
        assertEquals(0.8, c.vectorScore(), 1e-9);
    }

    @Test
    void keywordScoreTakesMaxAcrossMerges() {
        RetrievalCandidate c = RetrievalCandidate.fromKeywordHit(kh(1, 0.7));
        c.mergeKeywordHit(kh(1, 0.7), 1);
        c.mergeKeywordHit(kh(1, 0.3), 2);
        assertEquals(0.7, c.keywordScore(), 1e-9);
    }

    // ---------- RRF 累加 ----------

    @Test
    void rankingScoreAccumulatesRankScores() {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(vh(1, 0.5));
        c.mergeVectorHit(vh(1, 0.5), 1);   // rank1 → 1.0
        c.mergeKeywordHit(kh(1, 0.6), 2);  // rank2 → 0.5
        assertEquals(1.5, c.rankingScore(), 1e-9);
    }

    // ---------- 构造字段透传 ----------

    @Test
    void constructionCarriesIdentityFields() {
        RetrievalCandidate c = RetrievalCandidate.fromVectorHit(
                new VectorHit(11L, 22L, 33, "文本", 0.9));
        assertEquals(Long.valueOf(11L), c.documentId());
        assertEquals(Long.valueOf(22L), c.chunkId());
        assertEquals(Integer.valueOf(33), c.chunkIndex());
    }
}