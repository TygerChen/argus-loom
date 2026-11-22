package com.argus.rag.qa.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索后反思器契约（第三步优化）：JSON 解析 + 编号合法化 + fail-soft 降级。
 * 关键契约：模型编造的证据编号必须被剔除（代码不信任模型输出）；反思失败退化为保留全部。
 */
@DisplayName("检索后反思器（RetrievalRelevanceReflector）")
class RetrievalRelevanceReflectorTest {

    private final RetrievalRelevanceReflector reflector = new RetrievalRelevanceReflector(new ObjectMapper());

    private static Document doc(String id) {
        return Document.builder().id(id).text("证据" + id + "内容").build();
    }

    @Test
    void parseKeepsOnlyValidEvidenceIds() {
        // 模型编造 E999 —— 必须被合法化剔除
        var result = reflector.parse(
                "{\"keepIds\":[\"E1\",\"E999\"],\"rewriteQuery\":null}",
                List.of(doc("E1"), doc("E2")));
        assertFalse(result.allIrrelevant());
        assertEquals(java.util.Set.of("E1"), result.keepIds());
    }

    @Test
    void parseAllIrrelevantWithRewrite() {
        var result = reflector.parse(
                "{\"keepIds\":[],\"rewriteQuery\":\"报销上限是多少\"}",
                List.of(doc("E1")));
        assertTrue(result.allIrrelevant());
        assertTrue(result.hasRewrite());
        assertEquals("报销上限是多少", result.rewriteQuery());
    }

    @Test
    void parseDirtyJsonWithMarkdownFences() {
        // 模型带 Markdown 围栏 + 前后附言 → sanitizeJson 后仍能解析
        var result = reflector.parse(
                "以下是判断结果：\n```json\n{\"keepIds\":[\"E2\"],\"rewriteQuery\":null}\n```\n完成。",
                List.of(doc("E1"), doc("E2")));
        assertEquals(java.util.Set.of("E2"), result.keepIds());
    }

    @Test
    void reflectFallsBackToKeepAllOnChatFailure() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("模型不可用"));
        var result = reflector.reflect(chatClient, "问题", List.of(doc("E1"), doc("E2")));
        // fail-soft：反思失败 → 保留全部证据，不阻断主链路
        assertFalse(result.allIrrelevant());
        assertEquals(java.util.Set.of("E1", "E2"), result.keepIds());
    }
}
