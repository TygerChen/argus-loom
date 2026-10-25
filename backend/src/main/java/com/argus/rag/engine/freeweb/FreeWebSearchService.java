package com.argus.rag.engine.freeweb;

import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 国内可直连的免费网络搜索服务。
 *
 * <p>不依赖任何 API Key：优先读取必应中文站的 RSS 结果，RSS 不可用或无结果时
 * 再退回到必应中文 HTML 页面解析。两条通道都只调用国内可访问的 cn.bing.com。</p>
 */
@Service
public class FreeWebSearchService {

    private static final Logger log = LoggerFactory.getLogger(FreeWebSearchService.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${web-search.enabled:true}")
    private boolean enabled;
    @Value("${web-search.timeout-seconds:8}")
    private int timeoutSeconds;
    @Value("${web-search.top-k:10}")
    private int topK;
    @Value("${web-search.bing.language:zh-CN}")
    private String bingLanguage;

    @PreDestroy
    public void shutdown() {
        try {
            executor.shutdownNow();
        } catch (Exception ignore) {
            // 尽力关闭线程池（忽略失败，不阻塞正常的销毁流程）
        }
    }

    /**
     * 执行一次免费网络搜索。
     *
     * @param query 用户提供的搜索词
     * @return 去重并按相关度截断后的结果列表；不可用或搜索为空时返回空列表
     */
    public List<WebSearchResult> search(String query) {
        if (!enabled || !StringUtils.hasText(query)) {
            return List.of();
        }
        String trimmedQuery = query.trim();
        String safeQuery = trimmedQuery.length() > 200
                ? trimmedQuery.substring(0, 200)
                : trimmedQuery;
        final String finalQuery = normalizeQuery(safeQuery);

        try {
            // HTML 通道的 b_algo 结果对中文新闻更贴近（央视/科技媒体等真实站点），
            // 因此优先使用 HTML，RSS 作为兜底，避免拿到百科类泛化结果。
            List<WebSearchResult> htmlResults = timeoutGet(() -> searchBingHtml(finalQuery));
            if (htmlResults != null && !htmlResults.isEmpty()) {
                return capAndDedupe(htmlResults);
            }
            List<WebSearchResult> rssResults = timeoutGet(() -> searchBingRss(finalQuery));
            if (rssResults != null && !rssResults.isEmpty()) {
                return capAndDedupe(rssResults);
            }
            return List.of();
        } catch (Exception e) {
            log.warn("免费搜索失败: query={}, error={}", finalQuery, e.getMessage());
            return List.of();
        }
    }

    private <T> T timeoutGet(java.util.concurrent.Callable<T> task) {
        try {
            return executor.submit(task).get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private List<WebSearchResult> searchBingRss(String query) throws Exception {
        String url = "https://cn.bing.com/search?q=" + encode(query)
                + "&format=rss&setlang=zh-cn&setmkt=" + encode(bingLanguage);
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) {
            return List.of();
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document document = builder.parse(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8))
        );

        List<WebSearchResult> results = new ArrayList<>();
        NodeList items = document.getElementsByTagName("item");
        for (int index = 0; index < items.getLength(); index++) {
            Node item = items.item(index);
            String title = childText(item, "title");
            String link = childText(item, "link");
            String description = childText(item, "description");
            String publishDate = childText(item, "pubDate");
            if (!StringUtils.hasText(title) || !StringUtils.hasText(link)) {
                continue;
            }
            results.add(new WebSearchResult(
                    stripHtml(title),
                    link.trim(),
                    stripHtml(description),
                    "必应中国",
                    StringUtils.hasText(publishDate) ? publishDate : null
            ));
        }
        return results;
    }

    private List<WebSearchResult> searchBingHtml(String query) throws Exception {
        String url = "https://cn.bing.com/search?q=" + encode(query)
                + "&setlang=zh-hans&setmkt=" + encode(bingLanguage);
        Document document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .timeout((int) TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds)))
                .get();

        List<WebSearchResult> results = new ArrayList<>();
        for (Element item : document.select("li.b_algo")) {
            Element anchor = item.selectFirst("h2 a");
            if (anchor == null) {
                continue;
            }
            String title = anchor.text();
            String link = anchor.absUrl("href");
            String snippet = item.selectFirst(".b_caption p") == null
                    ? item.select("p").first() == null ? "" : item.select("p").first().text()
                    : item.selectFirst(".b_caption p").text();
            if (!StringUtils.hasText(title) || !StringUtils.hasText(link)) {
                continue;
            }
            results.add(new WebSearchResult(title, link, snippet, "必应中国", null));
        }
        return results;
    }

    /**
     * 去掉模型生成的关键词里的绝对日期（例如 2026年9月17日），
     * 让必应按主题和时效性检索，避免把日期当成普通词导致泛化结果。
     */
    /** @return 处理后的查询（包内可见 + static，无实例态，供 JVM 行为锁直接调用）。 */
    static String normalizeQuery(String query) {
        String normalized = query
                .replaceAll("\\d{4}年\\d{1,2}月\\d{1,2}日", " ")
                .replaceAll("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}", " ")
                .trim();
        // 即使是模型生成的稀疏关键词，也尽量带“最新”这类时效词，
        // 避免必应把“中国 科技 新闻”理解成百科站点而不是新闻内容。
        if (normalized.matches(".*(新闻|资讯|热点|动态|行情).*") && !normalized.contains("最新")) {
            normalized = "最新 " + normalized;
        }
        return normalized.isBlank() ? query.trim() : normalized;
    }

    private List<WebSearchResult> capAndDedupe(List<WebSearchResult> source) {
        Map<String, WebSearchResult> unique = new LinkedHashMap<>();
        for (WebSearchResult result : source) {
            if (result == null || !StringUtils.hasText(result.url())) {
                continue;
            }
            unique.putIfAbsent(normalizeUrl(result.url()), result);
        }
        List<WebSearchResult> output = new ArrayList<>(unique.values());
        if (output.size() > topK) {
            return output.subList(0, topK);
        }
        return output;
    }

    private String childText(Node parent, String tag) {
        if (parent == null) {
            return "";
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (tag.equals(child.getNodeName())) {
                return child.getTextContent() == null ? "" : child.getTextContent().trim();
            }
        }
        return "";
    }

    private static String stripHtml(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return Jsoup.parse(text).text().trim();
    }

    /**
     * 抓取指定 URL 的页面正文内容（提取 &lt;body&gt; 内的文本，去除脚本/样式/导航等噪音）。
     * 用于搜索结果的"深度阅读"——搜索只给标题+摘要，本方法获取实际页面数据。
     *
     * @param url 目标网页地址
     * @return 截断后的纯文本正文（最多 3000 字符）；抓取失败返回空串
     */
    public String fetchPageContent(String url) {
        if (!enabled || !StringUtils.hasText(url)) {
            return "";
        }
        try {
            // SSRF 红线：目标可能是模型给出的任意字符串，解析后必须落在公网。
            URI target = URI.create(url.trim());
            if (!SafeRemoteUrlPolicy.isAllowed(target)) {
                log.warn("页面抓取被拒绝（非公网 http/https 地址）: url={}", url);
                return "";
            }
            log.info("页面抓取开始: url={}", url);
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout((int) TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds)))
                    .get();

            // 移除脚本、样式、导航、页脚等噪音元素
            doc.select("script, style, nav, footer, header, aside, iframe, noscript, .ad, .advertisement").remove();

            // 优先提取 <article> 或 <main> 的内容
            Element contentEl = doc.selectFirst("article, main, .article-content, .news-content, .detail-content, .content, #content, .price, .quote, .table-wrapper, table");
            if (contentEl == null) {
                contentEl = doc.body();
            }
            if (contentEl == null) {
                log.info("页面抓取完成: url={}, body为空", url);
                return "";
            }

            String text = contentEl.text().trim();
            log.info("页面抓取完成: url={}, 文本长度={}", url, text.length());
            // 截断到 3000 字符，避免给模型过大上下文
            if (text.length() > 3000) {
                text = text.substring(0, 3000) + "…";
            }
            return text;
        } catch (Exception e) {
            log.warn("页面抓取失败: url={}, error={}", url, e.getMessage());
            return "";
        }
    }

    /** 包内静态，供 JVM 行为测试调用。 */
    static String normalizeUrl(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
