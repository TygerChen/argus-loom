package com.argus.rag.engine.yuanbao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯元宝 SearchPro 搜索客户端 —— 联网搜索链路的新通道（引擎适配层）。
 *
 * <p><b>协议要点（接入文档 2026-11-21）</b>：POST {@code https://api.wsa.cloud.tencent.com/SearchPro}，
 * Bearer Key 认证，请求体 {@code {"Query":"..."}}（大写 Q）；
 * 返回 {@code Response.Pages} 是<b>字符串数组</b>——每个元素本身又是一段 JSON，
 * 必须解析两层；HTTP 200 也可能带业务错误（{@code Response.Error}），必须单独检查。</p>
 *
 * <p><b>安全</b>：API Key 只从 {@code YUANBAO_SEARCH_API_KEY}（环境变量 / .env 属性源）读取，
 * 绝不写入源码；目标 URL 为固定 https 地址（无用户输入参与拼 URL，无 SSRF 面）；
 * Key 缺失时 {@link #isAvailable()} 为 false——调用方降级到备用搜索链路。</p>
 *
 * <p><b>成本纪律</b>：只取前 3 条、摘要截短——够模型组织答案，省 token。</p>
 */
@Component
public class YuanbaoSearchClient {

    private static final Logger log = LoggerFactory.getLogger(YuanbaoSearchClient.class);
    private static final String ENDPOINT = "https://api.wsa.cloud.tencent.com/SearchPro";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 3;
    private static final int TITLE_MAX = 60;
    private static final int PASSAGE_MAX = 160;

    private final HttpClient httpClient;
    private final String apiKey;

    public YuanbaoSearchClient(
            @Value("${YUANBAO_SEARCH_API_KEY:}") String apiKey
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Key 是否已配置（未配置时调用方应走备用搜索链路）。 */
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    /**
     * 联网搜索。
     *
     * @param query 搜索词
     * @return 搜索结果（最多 3 条；无结果返回空列表）
     * @throws IOException 网络/协议/业务错误（调用方降级处理，不阻断主链路）
     */
    public List<SearchHit> search(String query) throws IOException, InterruptedException {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Query", query.trim());

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("SearchPro HTTP " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }
        return parse(response.body());
    }

    /**
     * 解析 SearchPro 响应（包私有纯函数，独立可测）：
     * HTTP body → Response.Pages（字符串数组）→ 每个元素再解一层 JSON。
     */
    List<SearchHit> parse(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode resultNode = root.path("Response");
        JsonNode error = resultNode.path("Error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IOException("SearchPro 业务错误：" + error);
        }
        JsonNode pages = resultNode.path("Pages");
        if (!pages.isArray() || pages.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        int used = Math.min(MAX_RESULTS, pages.size());
        for (int i = 0; i < used; i++) {
            JsonNode page = pages.path(i);
            // Pages[i] 是被 JSON 编码过的字符串，必须再解一层
            JsonNode hit = page.isTextual() ? MAPPER.readTree(page.asText()) : page;
            hits.add(new SearchHit(
                    shorten(hit.path("title").asText("(无标题)"), TITLE_MAX),
                    hit.path("url").asText(""),
                    shorten(hit.path("passage").asText(""), PASSAGE_MAX)));
        }
        return hits;
    }

    private static String shorten(String value, int max) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** 单条搜索结果。 */
    public record SearchHit(String title, String url, String passage) {
    }
}
