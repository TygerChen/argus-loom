package com.argus.rag.engine.yuanbao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元宝 SearchPro 解析契约（接入文档核心坑位锁定）：
 * Pages 是字符串数组（每元素再解一层 JSON）、业务错误单独检查、截断与上限。
 */
@DisplayName("元宝搜索客户端解析（YuanbaoSearchClient）")
class YuanbaoSearchClientParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final YuanbaoSearchClient client = new YuanbaoSearchClient("test-key");

    @Test
    void parsesPagesStringArrayWithDoubleDecode() throws Exception {
        // 程序化构造：Pages 是字符串数组，每个元素本身是一段 JSON（外层 JSON 编码过）
        var inner1 = MAPPER.createObjectNode()
                .put("title", "金蝶云星空 反结账")
                .put("url", "https://a.example/1")
                .put("passage", "反结账步骤：总账-期末处理");
        var inner2 = MAPPER.createObjectNode()
                .put("title", "反结账常见问题")
                .put("url", "https://b.example/2")
                .put("passage", "反结账需先取消后续期间结账");
        var pages = MAPPER.createArrayNode()
                .add(MAPPER.writeValueAsString(inner1))
                .add(MAPPER.writeValueAsString(inner2));
        var responseNode = MAPPER.createObjectNode();
        responseNode.put("RequestId", "r1");
        responseNode.set("Pages", pages);
        var rootNode = MAPPER.createObjectNode();
        rootNode.set("Response", responseNode);
        String bodyJson = MAPPER.writeValueAsString(rootNode);

        var hits = client.parse(bodyJson);
        assertEquals(2, hits.size(), "Pages 字符串元素必须再解一层 JSON");
        assertEquals("金蝶云星空 反结账", hits.get(0).title());
        assertEquals("https://a.example/1", hits.get(0).url());
        assertTrue(hits.get(0).passage().contains("期末处理"));
    }

    @Test
    void capsResultsAtThreeAndTruncates() throws Exception {
        // 程序化构造 6 条（每条 passage 400 字），验证上限 3 条与摘要截断
        var pages = MAPPER.createArrayNode();
        for (int i = 0; i < 6; i++) {
            var inner = MAPPER.createObjectNode()
                    .put("title", "标题" + i)
                    .put("url", "https://x.example/" + i)
                    .put("passage", "内容".repeat(200));
            pages.add(MAPPER.writeValueAsString(inner));
        }
        var responseNode = MAPPER.createObjectNode();
        responseNode.set("Pages", pages);
        var rootNode = MAPPER.createObjectNode();
        rootNode.set("Response", responseNode);
        String bodyJson = MAPPER.writeValueAsString(rootNode);
        var hits = client.parse(bodyJson);
        assertEquals(3, hits.size(), "只取前 3 条（成本纪律）");
        assertTrue(hits.get(0).passage().length() <= 161, "摘要截断到 160 字");
    }

    @Test
    void businessErrorInsideHttp200Throws() {
        String body = "{\"Response\":{\"Error\":{\"Code\":\"AuthFailure\",\"Message\":\"key invalid\"},\"Pages\":[]}}";
        assertThrows(java.io.IOException.class, () -> client.parse(body),
                "HTTP 200 里的业务错误必须单独抛出");
    }

    @Test
    void emptyPagesReturnsEmptyList() throws Exception {
        String body = "{\"Response\":{\"Pages\":[]}}";
        assertTrue(client.parse(body).isEmpty());
        assertTrue(client.parse("{\"Response\":{}}").isEmpty());
    }

    @Test
    void availabilityDependsOnKey() {
        assertTrue(new YuanbaoSearchClient(" sk-x ").isAvailable());
        assertTrue(!new YuanbaoSearchClient("").isAvailable());
        assertTrue(!new YuanbaoSearchClient("  ").isAvailable());
    }
}
