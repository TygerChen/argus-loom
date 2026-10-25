package com.argus.rag.engine.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ElasticsearchChunkIndexService#buildKeywordSearchRequest} 的查询体结构行为锁。
 * <p>
 * 该方法已是同包可见 package-private，测试可在同包直接调用，无需改动生产代码即锁定
 * ES 检索请求体的组装语义（filter 过滤 / 初排 should 四维 / resecard 精排 / size 与权重），
 * 均为纯 JVM 组装、不触发任何 HTTP 请求。
 * </p>
 */
class ElasticsearchKeywordRequestBodyTest {

    private ElasticsearchChunkIndexService newService() {
        return new ElasticsearchChunkIndexService(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                "localhost", 9200, "http", "idx-test"
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void requestBuildsExpectedTopLevelAndQueryShape() {
        Map<String, Object> body = newService().buildKeywordSearchRequestBody(7L, "hello world", 9);

        // 顶层
        assertEquals(9, body.get("size"));
        List<String> source = (List<String>) body.get("_source");
        assertEquals(6, source.size());
        assertTrue(source.contains("chunkText"));

        // query.bool
        Map<String, Object> query = (Map<String, Object>) body.get("query");
        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        assertEquals(1, bool.get("minimum_should_match"));
        List<Map<String, Object>> filter = (List<Map<String, Object>>) bool.get("filter");
        assertNotNull(filter);
        assertEquals(3, filter.size());
        assertEquals("status", ((Map<?, ?>) ((Map<?, ?>) filter.get(1)).get("term")).keySet().iterator().next());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldClausesCarryExpectedBoostValues() {
        Map<String, Object> body = newService().buildKeywordSearchRequestBody(7L, "hello", 9);
        Map<String, Object> query = (Map<String, Object>) body.get("query");
        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        List<Map<String, Object>> should = (List<Map<String, Object>>) bool.get("should");
        assertEquals(4, should.size());

        // 第 0 条：fileName match_phrase boost 8
        Map<String, Object> f0 = (Map<String, Object>) should.get(0).get("match_phrase");
        Map<String, Object> f0Field = (Map<String, Object>) f0.get("fileName");
        assertEquals("hello", f0Field.get("query"));
        assertEquals(8, f0Field.get("boost"));

        // 每条 should 都携带 boost（保持可重排语义不被删）
        for (Map<String, Object> clause : should) {
            assertTrue(clause.toString().contains("boost"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void rescoreCarriesQueryWeightAndNestedShould() {
        Map<String, Object> body = newService().buildKeywordSearchRequestBody(7L, "hello", 3);
        Map<String, Object> rescore = (Map<String, Object>) body.get("rescore");
        assertEquals(3, rescore.get("window_size"));

        Map<String, Object> rq = (Map<String, Object>) rescore.get("query");
        assertEquals(0.2D, ((Number) rq.get("query_weight")).doubleValue(), 1e-9);
        assertEquals(1.0D, ((Number) rq.get("rescore_query_weight")).doubleValue(), 1e-9);
        assertEquals("total", rq.get("score_mode"));

        Map<String, Object> rescoreBool = (Map<String, Object>) ((Map<String, Object>) rq.get("rescore_query")).get("bool");
        assertEquals(1, rescoreBool.get("minimum_should_match"));
        List<Map<String, Object>> rShould = (List<Map<String, Object>>) rescoreBool.get("should");
        assertEquals(4, rShould.size());
        assertEquals("fileName", ((Map<?, ?>) ((Map<?, ?>) rShould.get(0).get("match_phrase")).get("fileName")).get("boost") != null
                ? "fileName" : "");
    }
}