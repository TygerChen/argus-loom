package com.argus.rag.assistant.agent;

import com.argus.rag.engine.freeweb.FreeWebSearchService;
import com.argus.rag.engine.freeweb.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 联网搜索工具——使用必应中国免费搜索（无需 API Key）。
 * <p>搜索后自动抓取结果页面的正文内容，让模型能获取实际数据（如价格、数值等），
 * 而不仅仅是搜索结果的标题和摘要片段。</p>
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** 页面正文最大字符数（截断） */
    private static final int MAX_PAGE_CONTENT_LENGTH = 2500;

    /** 跳过这些域名——JS 渲染 SPA，Jsoup 无法抓到内容 */
    private static final List<String> SKIP_DOMAINS = List.of(
            "toutiao.com", "tophub.today", "weibo.com", "zhihu.com",
            "douyin.com", "bilibili.com"
    );

    private final FreeWebSearchService webSearchService;

    public WebSearchTool(FreeWebSearchService freeWebSearchService) {
        this.webSearchService = freeWebSearchService;
    }

    @Tool(description = "搜索互联网获取实时信息（免费通道，无需API Key）。当用户询问新闻、最新事件、实时动态、价格行情或知识库中没有的公开信息时使用")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query
    ) {
        try {
            List<WebSearchResult> results = webSearchService.search(query);
            log.info("WebSearchTool: query={}, results count={}", query, results == null ? 0 : results.size());
            if (results == null || results.isEmpty()) {
                return "未找到关于「" + query + "」的相关网页结果。";
            }
            StringBuilder builder = new StringBuilder();
            int fetchedCount = 0;

            for (int index = 0; index < results.size(); index++) {
                WebSearchResult result = results.get(index);
                builder.append(index + 1).append(". ")
                        .append(result.title())
                        .append("\n");
                if (result.snippet() != null && !result.snippet().isBlank()) {
                    builder.append(result.snippet().trim()).append("\n");
                }
                builder.append("来源: ").append(result.url()).append("\n");

                // 跳过 JS 渲染的 SPA 站点，抓取有实质内容的页面
                if (shouldSkip(result.url())) {
                    builder.append("\n");
                    continue;
                }

                // 每次搜索最多抓取 5 个页面正文（避免耗时过长）
                if (fetchedCount < 5) {
                    String pageContent = webSearchService.fetchPageContent(result.url());
                    log.info("WebSearchTool: fetched page {} from {}, content length={}",
                            index + 1, result.url(), pageContent == null ? 0 : pageContent.length());
                    if (pageContent != null && !pageContent.isBlank()) {
                        fetchedCount++;
                        if (pageContent.length() > MAX_PAGE_CONTENT_LENGTH) {
                            pageContent = pageContent.substring(0, MAX_PAGE_CONTENT_LENGTH) + "…";
                        }
                        builder.append("【页面正文】").append(pageContent).append("\n");
                    }
                }
                builder.append("\n");
            }
            return builder.toString().trim();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private boolean shouldSkip(String url) {
        if (url == null) return true;
        String lower = url.toLowerCase();
        return SKIP_DOMAINS.stream().anyMatch(lower::contains);
    }
}
