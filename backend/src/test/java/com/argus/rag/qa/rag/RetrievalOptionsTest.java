package com.argus.rag.qa.rag;

import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RetrievalOptions} 检索参数配置对象契约。
 * <p>锁定两件事：默认值与历史硬编码行为一致（升级零回归）；
 * 非法配置自动收敛到安全下限，不会因配置错误拖垮检索链路。</p>
 */
@DisplayName("检索参数配置对象（RetrievalOptions）")
class RetrievalOptionsTest {

    @Test
    @DisplayName("默认参数与原硬编码值保持一致，线上行为不变")
    void defaultsMatchLegacyConstants() {
        RetrievalOptions options = RetrievalOptions.defaults();
        assertThat(options.channelTopK()).isEqualTo(50);
        assertThat(options.recallK()).isEqualTo(50);
        assertThat(options.maxGlobalDocuments()).isEqualTo(50);
        // 0 = 不启用每文档上限，保持历史行为
        assertThat(options.maxChunksPerDocument()).isZero();
    }

    @Test
    @DisplayName("非法参数自动收敛到安全下限，避免配置错误拖垮检索")
    void invalidValuesAreClampedToSafeFloor() {
        RetrievalOptions options = new RetrievalOptions(-5, 0, -1, -3);
        assertThat(options.channelTopK()).isEqualTo(1);
        assertThat(options.recallK()).isEqualTo(1);
        assertThat(options.maxGlobalDocuments()).isEqualTo(1);
        assertThat(options.maxChunksPerDocument()).isZero();
    }

    @Test
    @DisplayName("未启用每文档上限时沿用全局相关性排序，不按文档截断")
    void zeroPerDocumentCapKeepsGlobalRanking() {
        RetrievalOptions options = new RetrievalOptions(50, 50, 50, 0);
        List<RetrievalCandidate> ranked = RetrievalCandidateSelector.selectRankedCandidates(
                candidates(10L, 12), 4, false, options.maxChunksPerDocument());
        assertThat(ranked).hasSize(4);
        assertThat(ranked.stream().map(RetrievalCandidate::documentId)).containsOnly(10L);
    }

    @Test
    @DisplayName("每文档上限生效：单文档最多保留 maxChunksPerDocument 个切片")
    void perDocumentCapLimitsChunksFromOneDocument() {
        RetrievalOptions options = new RetrievalOptions(50, 50, 50, 2);
        List<RetrievalCandidate> ranked = RetrievalCandidateSelector.selectRankedCandidates(
                candidates(10L, 12), 8, false, options.maxChunksPerDocument());
        // 12 条同文档候选，配额 2 → 只保留评分最高的 2 条
        assertThat(ranked).hasSize(2);
    }

    @Test
    @DisplayName("跨文档覆盖模式下每文档上限同样生效")
    void perDocumentCapAlsoAppliesInCoverageMode() {
        RetrievalOptions options = new RetrievalOptions(50, 50, 50, 1);
        List<RetrievalCandidate> ranked = RetrievalCandidateSelector.selectRankedCandidates(
                candidates(10L, 6), 12, true, options.maxChunksPerDocument());
        // 覆盖模式先保底 1 条，配额 1 → 补足阶段不再追加同文档切片
        assertThat(ranked).hasSize(1);
    }

    /** 构造同一文档的 count 个候选，chunkId 依次递增，RRF 分数按排名自然递减。 */
    private List<RetrievalCandidate> candidates(long documentId, int count) {
        List<RetrievalCandidate> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RetrievalCandidate candidate = RetrievalCandidate.fromVectorHit(
                    new PgVectorRetrievalAdapter.VectorHit(documentId, 1000L + i, i, "chunk-" + i, 0.9D));
            candidate.mergeVectorHit(
                    new PgVectorRetrievalAdapter.VectorHit(documentId, 1000L + i, i, "chunk-" + i, 0.9D), i + 1);
            list.add(candidate);
        }
        return list;
    }
}
