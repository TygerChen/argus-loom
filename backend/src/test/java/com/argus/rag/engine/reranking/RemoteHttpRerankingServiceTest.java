package com.argus.rag.engine.reranking;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteHttpRerankingService} 远程重排契约（JDK 内置 HttpServer 起本地桩，零新依赖）：
 * - 正常 200：按服务端分数降序映射回候选，请求体携带 query/documents/model；
 * - 任何异常路径（非 200、坏 JSON、长度不符、索引越界/重复、超时）一律回退为
 *   与 NoOp 同口径的保序分数——服务自身永不抛出，模型故障不得变成问答失败；
 * - 空候选：直接返回空列表，不发请求。
 */
@DisplayName("远程 HTTP 重排（RemoteHttpRerankingService）")
class RemoteHttpRerankingServiceTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger statusToSend = new AtomicInteger(200);
    private final AtomicReference<String> payloadToSend = new AtomicReference<>("{\"results\":[]}");
    private final AtomicReference<Long> handlerSleepMs = new AtomicReference<>(0L);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/rerank", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long sleep = handlerSleepMs.get();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = payloadToSend.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToSend.get(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private RemoteHttpRerankingService service(long timeoutMs) {
        return new RemoteHttpRerankingService("http://127.0.0.1:" + port, "BAAI/bge-reranker-base", timeoutMs);
    }

    private RemoteHttpRerankingService service() {
        return service(2000);
    }

    @Test
    void rerankOrdersByServerScoreAndSendsPayload() {
        payloadToSend.set("{\"model\":\"BAAI/bge-reranker-base\",\"results\":[{\"index\":1,\"score\":0.92},{\"index\":0,\"score\":0.13},{\"index\":2,\"score\":0.4}]}");
        List<String> candidates = List.of("甲文档片段", "乙文档片段", "丙文档片段");

        List<RerankingService.RerankResult> out = service().rerank("北京住宿报销上限", candidates);

        assertEquals(List.of(1, 2, 0), out.stream().map(RerankingService.RerankResult::index).toList());
        assertEquals(0.92, out.get(0).score(), 1e-9);
        String body = lastBody.get();
        assertTrue(body.contains("北京住宿报销上限"), "请求体应携带 query");
        assertTrue(body.contains("乙文档片段"), "请求体应携带 documents");
        assertTrue(body.contains("BAAI/bge-reranker-base"), "请求体应携带 model");
    }

    @Test
    void httpErrorFallsBackToOriginalOrder() {
        statusToSend.set(500);
        List<String> candidates = List.of("a", "b", "c");

        List<RerankingService.RerankResult> out = service().rerank("q", candidates);

        assertEquals(List.of(0, 1, 2), out.stream().map(RerankingService.RerankResult::index).toList());
        assertTrue(out.get(0).score() > out.get(1).score(), "回退分数应保序递减");
    }

    @Test
    void malformedJsonFallsBack() {
        payloadToSend.set("not-json");
        List<String> candidates = List.of("a", "b");

        List<RerankingService.RerankResult> out = service().rerank("q", candidates);

        assertEquals(List.of(0, 1), out.stream().map(RerankingService.RerankResult::index).toList());
    }

    @Test
    void sizeMismatchFallsBack() {
        payloadToSend.set("{\"results\":[{\"index\":0,\"score\":0.5}]}");
        List<String> candidates = List.of("a", "b");

        List<RerankingService.RerankResult> out = service().rerank("q", candidates);

        assertEquals(List.of(0, 1), out.stream().map(RerankingService.RerankResult::index).toList());
    }

    @Test
    void duplicateOrOutOfRangeIndexFallsBack() {
        payloadToSend.set("{\"results\":[{\"index\":0,\"score\":0.5},{\"index\":0,\"score\":0.4}]}");
        List<String> candidates = List.of("a", "b");

        List<RerankingService.RerankResult> out = service().rerank("q", candidates);

        assertEquals(List.of(0, 1), out.stream().map(RerankingService.RerankResult::index).toList());
    }

    @Test
    void timeoutFallsBackWithoutThrowing() {
        handlerSleepMs.set(1500L);
        List<String> candidates = List.of("a", "b", "c");

        List<RerankingService.RerankResult> out = service(200).rerank("q", candidates);

        assertEquals(List.of(0, 1, 2), out.stream().map(RerankingService.RerankResult::index).toList());
    }

    @Test
    void unreachableServerFallsBackWithoutThrowing() {
        RemoteHttpRerankingService dead =
                new RemoteHttpRerankingService("http://127.0.0.1:9", "m", 300);

        List<RerankingService.RerankResult> out = dead.rerank("q", List.of("a", "b"));

        assertEquals(List.of(0, 1), out.stream().map(RerankingService.RerankResult::index).toList());
    }

    @Test
    void emptyCandidatesShortCircuits() {
        assertTrue(service().rerank("q", List.of()).isEmpty());
        assertTrue(service().rerank("q", null).isEmpty());
    }
}
