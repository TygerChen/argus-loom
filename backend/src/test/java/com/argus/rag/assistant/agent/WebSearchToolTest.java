package com.argus.rag.assistant.agent;

import com.argus.rag.engine.freeweb.FreeWebSearchService;
import com.argus.rag.engine.freeweb.WebSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebSearchTool} 联网搜索工具契约（mock FreeWebSearchService，纯 JVM/零网络/零外呼）。
 * 空/缺失结果返回「未找到…」；命中则按「序号. 标题/摘要/来源」分块拼装，空摘要跳过；
 * 底层搜索异常被捕获并重组为「搜索失败: …」而非向上冒泡。
 */
@DisplayName("联网搜索工具（WebSearchTool）")
class WebSearchToolTest {

    private final FreeWebSearchService webSearchService = mock(FreeWebSearchService.class);
    /** 元宝客户端未配 Key → isAvailable=false → 降级必应通道（与本测试的 mock 路径一致） */
    private final WebSearchTool tool = new WebSearchTool(webSearchService,
            new com.argus.rag.engine.yuanbao.YuanbaoSearchClient(""));

    private static WebSearchResult result(String title, String snippet, String url) {
        return new WebSearchResult(title, url, snippet, "BING", null);
    }

    @Test
    void nullResultsReportsNotFound() {
        when(webSearchService.search(anyString())).thenReturn(null);
        assertEquals("未找到关于「AI」的相关网页结果。", tool.webSearch("AI", null));
    }

    @Test
    void emptyResultsReportsNotFound() {
        when(webSearchService.search(anyString())).thenReturn(Collections.emptyList());
        assertEquals("未找到关于「AI」的相关网页结果。", tool.webSearch("AI", null));
    }

    @Test
    void singleResultRendersNumberedBlockWithSource() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("标题A", "摘要A", "https://a.example")));
        String out = tool.webSearch("AI", null);
        assertTrue(out.contains("1. 标题A\n摘要A\n来源: https://a.example"));
    }

    @Test
    void nullSnippetIsToleratedWithoutRow() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("标题B", null, "https://b.example")));
        assertTrue(tool.webSearch("AI", null).contains("1. 标题B\n来源: https://b.example"));
    }

    @Test
    void blankSnippetIsSkippedButTitleSourceKept() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("标题C", "   ", "https://c.example")));
        assertTrue(tool.webSearch("AI", null).contains("1. 标题C\n来源: https://c.example"));
    }

    @Test
    void multipleResultsKeepOrderWithBlankLinesBetween() {
        when(webSearchService.search(anyString())).thenReturn(List.of(
                result("一", "s1", "u1"),
                result("二", "s2", "u2")));
        String out = tool.webSearch("AI", null);
        assertTrue(out.contains("1. 一\ns1\n来源: u1"));
        assertTrue(out.contains("2. 二\ns2\n来源: u2"));
    }

    @Test
    void snippetWhitespaceIsTrimmedInRow() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("T", "  padded  ", "url")));
        assertTrue(tool.webSearch("AI", null).contains("1. T\npadded\n来源: url"));
    }

    @Test
    void exceptionIsRewrittenToSearchFailure() {
        when(webSearchService.search(anyString()))
                .thenThrow(new RuntimeException("timeout"));
        assertEquals("搜索失败: timeout", tool.webSearch("AI", null));
    }
}