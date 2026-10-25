package com.argus.rag.qa.support;

import com.argus.rag.qa.model.KnowledgeAnswerOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QaAnswerParser} 回答解析契约（真实 ObjectMapper，纯 JVM/零 LLM/零网络）。
 * sanitizeJson：剥 Markdown 代码围栏、前后附言并按首尾花括号截取 JSON 对象；
 * parse：空白→null、合法 JSON→实体、非法/不成对花括号→null。
 */
@DisplayName("问答回答解析器（QaAnswerParser）")
class QaAnswerParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QaAnswerParser parser = new QaAnswerParser(objectMapper);

    @Test
    void sanitizeLeavesBareJsonUntouched() {
        String json = "{\"answered\":true,\"answer\":\"你好\"}";
        assertEquals(json, QaAnswerParser.sanitizeJson(json));
    }

    @Test
    void sanitizeRemovesJsonCodeFence() {
        assertEquals("{\"answered\":true}",
                QaAnswerParser.sanitizeJson("```json\n{\"answered\":true}\n```"));
    }

    @Test
    void sanitizeRemovesNonJsonFenceLanguage() {
        assertEquals("{\"answered\":true}", QaAnswerParser.sanitizeJson("```\n{\"answered\":true}\n```"));
    }

    @Test
    void sanitizeExtractsObjectFromSurroundingProse() {
        assertEquals("{\"answered\":true}",
                QaAnswerParser.sanitizeJson("前附言 {\"answered\":true} 后附言"));
    }

    @Test
    void sanitizeKeepsTextWithoutBracesAsIsTrimmed() {
        assertEquals("hello world", QaAnswerParser.sanitizeJson("  hello world  "));
    }

    @Test
    void sanitizeUnbalancedOpeningBraceReturnsAsIs() {
        assertEquals("{", QaAnswerParser.sanitizeJson("{"));
    }

    @Test
    void sanitizeBlankReturnsEmpty() {
        assertEquals("", QaAnswerParser.sanitizeJson("   "));
        assertEquals("", QaAnswerParser.sanitizeJson(""));
    }

    @Test
    void sanitizeDropsTrailingFenceWithoutOpeningLanguage() {
        assertEquals("{\"answered\":true}", QaAnswerParser.sanitizeJson("{\"answered\":true}\n```"));
    }

    @Test
    void parseValidJsonPopulatesRecord() {
        KnowledgeAnswerOutput out = parser.parse("{\"answered\":true,\"answer\":\"财务流程见附件\"}");
        assertTrue(out.answered());
        assertEquals("财务流程见附件", out.answer());
        assertNull(out.reasonCode());
    }

    @Test
    void parseReasonCodeVariant() {
        KnowledgeAnswerOutput out = parser.parse(
                "{\"answered\":false,\"reasonCode\":\"INSUFFICIENT_EVIDENCE\",\"reasonMessage\":\"证据不足\"}");
        assertTrue(!out.answered());
        assertEquals("INSUFFICIENT_EVIDENCE", out.reasonCode());
        assertEquals("证据不足", out.reasonMessage());
    }

    @Test
    void parseHandlesFencedJsonToo() {
        KnowledgeAnswerOutput out = parser.parse("```json\n{\"answered\":true,\"answer\":\"ok\"}\n```");
        assertTrue(out.answered());
        assertEquals("ok", out.answer());
    }

    @Test
    void parseIgnoresUnknownFields() {
        KnowledgeAnswerOutput out = parser.parse("{\"answered\":true,\"answer\":\"a\",\"extra\":1}");
        assertEquals("a", out.answer());
    }

    @Test
    void parseBlankReturnsNull() {
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
        assertNull(parser.parse("   "));
    }

    @Test
    void parseGarbageReturnsNull() {
        assertNull(parser.parse("this is not json at all"));
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        assertNull(parser.parse("{\"answered\":true,"));
    }
}