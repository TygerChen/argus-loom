package com.argus.rag.engine.freeweb;

/**
 * 免费网络搜索的单条结果。
 *
 * @param title       标题
 * @param url         原始链接
 * @param snippet     摘要文字
 * @param source      来源标记，如 BING / BING_HTML
 * @param publishedAt 发布时间，可能为空
 */
public record WebSearchResult(
        String title,
        String url,
        String snippet,
        String source,
        String publishedAt
) {
}
