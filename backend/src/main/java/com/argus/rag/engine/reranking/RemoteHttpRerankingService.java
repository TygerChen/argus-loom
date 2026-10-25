package com.argus.rag.engine.reranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 远程 HTTP 重排实现：调用本地网关（chat-only-gateway.py）的 {@code POST /rerank}，
 * 由 fastembed 的 BGE cross-encoder（默认 BAAI/bge-reranker-base）对 (query, 候选)
 * 打分，与 JVM 内本地 embedding 的离线/零 API 费用原则一致（ADR-004）。
 *
 * <p><b>降级是第一设计约束</b>：本实现自身永不抛出——HTTP 非法状态码、超时、
 * 响应结构异常（长度不符/索引越界/重复索引）一律回退为与 {@link RerankingService.NoOp}
 * 同口径的保序分数，链路继续用 RRF 原序，只是不享受重排增益。管线侧
 * {@code applyReranking} 还有第二道 try-catch 兜底，双重保险确保模型异常
 * 不会变成问答失败（ROADMAP P1-1 的验收要求）。</p>
 *
 * <p>装配见 {@link RerankingConfiguration}：仅当 {@code argus.rag.reranking.provider=remote}
 * 时生效，默认仍是 NoOp——上线前必须先跑离线对比评测。</p>
 */
public class RemoteHttpRerankingService implements RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RemoteHttpRerankingService.class);

    private final HttpClient http;
    private final URI rerankUri;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RemoteHttpRerankingService(String baseUrl, String model, long timeoutMs) {
        String root = baseUrl == null ? "" : baseUrl.trim();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.rerankUri = URI.create(root + "/rerank");
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "query", query == null ? "" : query,
                    "documents", candidates));
            HttpRequest request = HttpRequest.newBuilder(rerankUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("远程重排返回非 200，回退原始顺序: uri={}, status={}", rerankUri, response.statusCode());
                return fallback(candidates);
            }
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.size() != candidates.size()) {
                log.warn("远程重排响应长度不符，回退原始顺序: expected={}, actual={}",
                        candidates.size(), results.isArray() ? results.size() : -1);
                return fallback(candidates);
            }
            boolean[] seen = new boolean[candidates.size()];
            List<RerankResult> out = new ArrayList<>(candidates.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size() || seen[index]) {
                    log.warn("远程重排响应索引非法，回退原始顺序: index={}", index);
                    return fallback(candidates);
                }
                seen[index] = true;
                out.add(new RerankResult(index, item.path("score").asDouble()));
            }
            out.sort(Comparator.comparingDouble(RerankResult::score).reversed());
            return out;
        } catch (Exception e) {
            log.warn("远程重排调用失败，回退原始顺序: uri={}, error={}", rerankUri, e.getMessage());
            return fallback(candidates);
        }
    }

    /** 与 NoOp 同口径的保序分数：重排不可用时链路继续，只是不改变召回顺序。 */
    private static List<RerankResult> fallback(List<String> candidates) {
        return IntStream.range(0, candidates.size())
                .mapToObj(i -> new RerankResult(i, 1.0 - i * 0.001))
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .toList();
    }
}
