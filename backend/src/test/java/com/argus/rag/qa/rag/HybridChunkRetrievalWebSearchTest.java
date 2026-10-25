package com.argus.rag.qa.rag;

import com.argus.rag.engine.youcom.YoucomSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-04：网页搜索并行通道（{@code HybridChunkRetrievalService.searchWebQueries}）行为锁。
 *
 * <p>此前用 {@code parallelStream} 落在 JVM 公共 ForkJoinPool；现改为专用 {@code retrievalExecutor}：
 * <ul>
 *   <li>执行器未注入 → 顺序执行，结果合并顺序与查询顺序一致；</li>
 *   <li>注入执行器 → 并行执行，结果仍按查询顺序合并（编号稳定）；</li>
 *   <li>提交被拒绝 → 退化为顺序执行，不丢结果；</li>
 *   <li>单个子查询失败 → 跳过该查询，不影响其余结果。</li>
 * </ul>
 * 纯 JVM、零网络。
 */
@DisplayName("网页搜索并行通道（searchWebQueries / P2-04）")
class HybridChunkRetrievalWebSearchTest {

    @SuppressWarnings("unchecked")
    private static List<Document> docs(String tag) {
        return List.of(new Document(tag));
    }

    private static HybridChunkRetrievalService service(YoucomSearchService youcom) {
        // 全部依赖置空：本用例只锁 searchWebQueries 这一条纯编排路径
        return new HybridChunkRetrievalService(null, null, youcom, null, null, null, null, 0);
    }

    @Test
    @DisplayName("未注入执行器时退化为顺序执行，结果按查询顺序合并")
    void sequentialFallbackWhenNoExecutor() {
        YoucomSearchService youcom = mock(YoucomSearchService.class);
        when(youcom.search(anyString())).thenAnswer(inv -> docs("r-" + inv.getArgument(0)));
        HybridChunkRetrievalService service = service(youcom);

        List<Document> merged = service.searchWebQueries(List.of("q1", "q2"));

        assertThat(merged).extracting(Document::getText)
                .containsExactly("r-q1", "r-q2");
    }

    @Test
    @DisplayName("注入执行器时并行执行，结果仍按查询顺序合并")
    void parallelExecutionKeepsQueryOrder() {
        YoucomSearchService youcom = mock(YoucomSearchService.class);
        when(youcom.search(anyString())).thenAnswer(inv -> docs("r-" + inv.getArgument(0)));
        HybridChunkRetrievalService service = service(youcom);
        service.setRetrievalExecutor(Runnable::run);

        List<Document> merged = service.searchWebQueries(List.of("q1", "q2", "q3"));

        assertThat(merged).extracting(Document::getText)
                .containsExactly("r-q1", "r-q2", "r-q3");
    }

    @Test
    @DisplayName("提交被拒绝时退化为顺序执行，不丢结果")
    void rejectedSubmissionFallsBackToSequential() {
        YoucomSearchService youcom = mock(YoucomSearchService.class);
        when(youcom.search(anyString())).thenAnswer(inv -> docs("r-" + inv.getArgument(0)));
        HybridChunkRetrievalService service = service(youcom);
        Executor rejecting = task -> { throw new java.util.concurrent.RejectedExecutionException("full"); };
        service.setRetrievalExecutor(rejecting);

        List<Document> merged = service.searchWebQueries(List.of("q1", "q2"));

        assertThat(merged).extracting(Document::getText)
                .containsExactly("r-q1", "r-q2");
    }

    @Test
    @DisplayName("单个子查询失败只跳过该查询，其余结果保留")
    void singleQueryFailureIsSkipped() {
        YoucomSearchService youcom = mock(YoucomSearchService.class);
        when(youcom.search("bad")).thenThrow(new RuntimeException("network down"));
        when(youcom.search("good")).thenReturn(docs("r-good"));
        HybridChunkRetrievalService service = service(youcom);
        service.setRetrievalExecutor(Runnable::run);

        List<Document> merged = service.searchWebQueries(List.of("bad", "good"));

        assertThat(merged).extracting(Document::getText).containsExactly("r-good");
    }

    @Test
    @DisplayName("搜索服务未注入/查询为空时直接返回空结果")
    void nullServiceOrEmptyQueriesReturnEmpty() {
        HybridChunkRetrievalService withoutSearch = service(null);
        assertThat(withoutSearch.searchWebQueries(List.of("q1"))).isEmpty();

        YoucomSearchService youcom = mock(YoucomSearchService.class);
        HybridChunkRetrievalService withSearch = service(youcom);
        assertThat(withSearch.searchWebQueries(List.of())).isEmpty();
        assertThat(withSearch.searchWebQueries(null)).isEmpty();
    }
}
