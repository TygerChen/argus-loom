package com.argus.rag.engine.youcom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YoucomSearchService#parseResponse} 的响应体解析行为锁。
 * <p>
 * 该方法为同包可见（package-private），测试在相同包内直接调用，无需触发任一真实的
 * YDC HTTP 请求：给定一个响应 JSON 字符串，断言其结果如何被组装为 Spring AI
 * {@link Document}（WEB 序号 / 来源拼接 / 元数据字段 / snippet→description 回退 / 空结果）。
 * </p>
 */
class YoucomSearchServiceParsingTest {

    private YoucomSearchService newService() {
        return new YoucomSearchService("", false, 10, new ObjectMapper());
    }

    @Test
    void emptyBodyYieldsEmptyListNotThrow() {
        YoucomSearchService service = newService();
        List<Document> docs = service.parseResponse("");
        assertEquals(0, docs.size());
    }

    @Test
    void nonArrayResultsYieldsEmptyList() {
        List<Document> docs = newService().parseResponse("{\"results\":{\"title\":\"not-a-venue\"}}");
        assertEquals(0, docs.size());
    }

    @Test
    void emptyResultsArrayYieldsEmptyList() {
        List<Document> docs = newService().parseResponse("{\"results\":[]}");
        assertEquals(0, docs.size());
    }

    @Test
    void singleResultBuildsDocumentFromSnippet() {
        String body = "{\"results\":[{" +
                "\"title\":\"Apple\"," +
                "\"url\":\"https://apple.com\"," +
                "\"snippets\":[\"first snippet\",\"second snippet\"]," +
                "\"description\":\"fallback desc\"" +
                "}]}";
        List<Document> docs = newService().parseResponse(body);
        assertEquals(1, docs.size());

        Document d = docs.get(0);
        assertEquals("WEB1", d.getId());
        assertEquals("来源：Apple\nURL：https://apple.com\nfirst snippet", d.getText());

        Map<String, Object> meta = d.getMetadata();
        assertEquals("WEB1", meta.get("evidenceId"));
        assertEquals("YOUCOM", meta.get("retrievalSource"));
        assertEquals(1.0D, ((Number) meta.get("score")).doubleValue(), 1e-9);
        assertEquals("https://apple.com", meta.get("url"));
        assertEquals("Apple", meta.get("title"));
        assertEquals("WEB_SEARCH", meta.get("coverageMode"));
    }

    @Test
    void multipleResultsNumberIdsAndScores() {
        String body = "{\"results\":[{" +
                "\"title\":\"A\",\"url\":\"https://a.com\",\"snippets\":[\"a\"]" +
                "},{" +
                "\"title\":\"B\",\"url\":\"https://b.com\",\"snippets\":[\"b\"]" +
                "}]}";
        List<Document> docs = newService().parseResponse(body);

        assertEquals(2, docs.size());
        assertEquals("WEB1", docs.get(0).getId());
        assertEquals("来源：A\nURL：https://a.com\na", docs.get(0).getText());
        assertEquals("WEB2", docs.get(1).getId());
        assertEquals("来源：B\nURL：https://b.com\nb", docs.get(1).getText());

        assertEquals(1.0D, ((Number) docs.get(0).getMetadata().get("score")).doubleValue(), 1e-9);
        assertEquals(0.5D, ((Number) docs.get(1).getMetadata().get("score")).doubleValue(), 1e-9);
    }

    @Test
    void fallsBackToDescriptionWhenSnippetsMissingOrEmpty() {
        // 无 snippets 字段 → description
        String noSnippets = "{\"results\":[{" +
                "\"title\":\"T\",\"url\":\"u\",\"description\":\"d1\"" +
                "}]}";
        List<Document> docs1 = newService().parseResponse(noSnippets);
        assertEquals(1, docs1.size());
        assertEquals("来源：T\nURL：u\nd1", docs1.get(0).getText());

        // snippets 为空数组 → 也回退到 description
        String emptySnippets = "{\"results\":[{" +
                "\"title\":\"T\",\"url\":\"u\",\"snippets\":[],\"description\":\"d2\"" +
                "}]}";
        List<Document> docs2 = newService().parseResponse(emptySnippets);
        assertEquals(1, docs2.size());
        assertEquals("来源：T\nURL：u\nd2", docs2.get(0).getText());
    }

    @Test
    void snippetEmptyStringAlsoFallsBackToDescription() {
        String body = "{\"results\":[{" +
                "\"title\":\"T\",\"url\":\"u\",\"snippets\":[\"\"],\"description\":\"dd\"" +
                "}]}";
        List<Document> docs = newService().parseResponse(body);
        assertEquals(1, docs.size());
        assertEquals("WEB1", docs.get(0).getId());
        assertTrue(docs.get(0).getText().endsWith("dd"));
    }
}