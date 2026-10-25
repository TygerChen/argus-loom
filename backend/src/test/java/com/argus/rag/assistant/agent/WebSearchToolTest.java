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
    private final WebSearchTool tool = new WebSearchTool(webSearchService);

    private static WebSearchResult result(String title, String snippet, String url) {
        return new WebSearchResult(title, url, snippet, "BING", null);
    }

    @Test
    void nullResultsReportsNotFound() {
        when(webSearchService.search(anyString())).thenReturn(null);
        assertEquals("未找到关于「AI」的相关网页结果。", tool.webSearch("AI"));
    }

    @Test
    void emptyResultsReportsNotFound() {
        when(webSearchService.search(anyString())).thenReturn(Collections.emptyList());
        assertEquals("未找到关于「AI」的相关网页结果。", tool.webSearch("AI"));
    }

    @Test
    void singleResultRendersNumberedBlockWithSource() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("标题A", "摘要A", "https://a.example")));
        String out = tool.webSearch("AI");
        assertEquals("1. 标题A\n摘要A\n来源: https://a.example", out);
    }

    @Test
    void nullSnippetIsToleratedWithoutRow() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("标题B", null, "https://b.example")));
        assertEquals("1. 标题B\n来源: https://b.example", tool.webSearch("AI"));
    }

    @Test
    void blankSnippetIsSkippedButTitleSourceKept() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("标题C", "   ", "https://c.example")));
        assertEquals("1. 标题C\n来源: https://c.example", tool.webSearch("AI"));
    }

    @Test
    void multipleResultsKeepOrderWithBlankLinesBetween() {
        when(webSearchService.search(anyString())).thenReturn(List.of(
                result("一", "s1", "u1"),
                result("二", "s2", "u2")));
        String out = tool.webSearch("AI");
        assertTrue(out.contains("1. 一\ns1\n来源: u1"));
        assertTrue(out.contains("2. 二\ns2\n来源: u2"));
        assertTrue(out.startsWith("1."));
        assertTrue(out.endsWith("来源: u2"));
    }

    @Test
    void snippetWhitespaceIsTrimmedInRow() {
        when(webSearchService.search(anyString()))
                .thenReturn(List.of(result("T", "  padded  ", "url")));
        assertEquals("1. T\npadded\n来源: url", tool.webSearch("AI"));
    }

    @Test
    void exceptionIsRewrittenToSearchFailure() {
        when(webSearchService.search(anyString()))
                .thenThrow(new RuntimeException("timeout"));
        assertEquals("搜索失败: timeout", tool.webSearch("AI"));
    }
}