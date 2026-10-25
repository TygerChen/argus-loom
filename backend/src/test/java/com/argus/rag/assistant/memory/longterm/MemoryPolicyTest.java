package com.argus.rag.assistant.memory.longterm;

import com.argus.rag.assistant.memory.longterm.MemoryPolicy.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryPolicy} 长期记忆写入策略契约（纯 JVM/零 mock/零容器）。
 * 判据顺序：memoryType 白名单 → content 非空白 → 敏感词检测 → source/显式请求分流。
 * 敏感词优先于显式请求（USER_EXPLICIT/explicitRequest 无法绕过敏感词拦截）。
 */
@DisplayName("长期记忆写入策略（MemoryPolicy）")
class MemoryPolicyTest {

    private final MemoryPolicy policy = new MemoryPolicy();

    @Test
    void rejectsUnsupportedMemoryType() {
        Decision d = policy.evaluate("SECRET_TYPE", "some content", "USER_EXPLICIT", true);
        assertFalse(d.accepted());
        assertTrue(d.reason().startsWith("unsupported memory type"));
    }

    @Test
    void rejectsNullMemoryType() {
        Decision d = policy.evaluate(null, "content", "USER_EXPLICIT", true);
        assertFalse(d.accepted());
    }

    @Test
    void rejectsBlankContent() {
        Decision d = policy.evaluate("PREFERENCE", "   ", "USER_EXPLICIT", true);
        assertFalse(d.accepted());
        assertEquals("content must not be empty", d.reason());
    }

    @Test
    void rejectsSensitiveTermsCaseInsensitively() {
        assertRejected("PREFERENCE", "my secret password is hunter2");
        assertRejected("FACT", "api_key=abc123");
        assertRejected("TASK", "send to bank card 1111");
        assertRejected("PREFERENCE", "Bearer TOKEN xyz");
    }

    @Test
    void sensitiveTermWinsEvenOverExplicitRequest() {
        Decision d = policy.evaluate("PREFERENCE", "credit card 4111", "USER_EXPLICIT", true);
        assertFalse(d.accepted());
        assertEquals("content may contain sensitive information", d.reason());
    }

    @Test
    void acceptsUserExplicitSourceWithHighConfidence() {
        Decision d = policy.evaluate("PREFERENCE", "always use concise replies", "USER_EXPLICIT", false);
        assertTrue(d.accepted());
        assertEquals(0.98, d.confidence());
        assertFalse(d.candidate());
    }

    @Test
    void acceptsExplicitRequestFlagRegardlessOfSource() {
        Decision d = policy.evaluate("FACT", "company founded 2010", "SOME_SOURCE", true);
        assertTrue(d.accepted());
        assertEquals(0.98, d.confidence());
    }

    @Test
    void modelInferenceIsCandidateNeedingConfirmation() {
        Decision d = policy.evaluate("PREFERENCE", "dev prefers weekends off", "MODEL_INFERENCE", false);
        assertFalse(d.accepted());
        assertTrue(d.candidate());
        assertEquals(0.35, d.confidence());
    }

    @Test
    void userStatementIsRejectedByDefault() {
        Decision d = policy.evaluate("FACT", "lives in beijing", "USER_STATEMENT", false);
        assertFalse(d.accepted());
    }

    @Test
    void toolFactIsRejected() {
        Decision d = policy.evaluate("TASK", "stock price is 100", "TOOL_FACT", false);
        assertFalse(d.accepted());
    }

    @Test
    void adminConfigAcceptsAtHighConfidence() {
        Decision d = policy.evaluate("PREFERENCE", "region=cn", "ADMIN_CONFIG", false);
        assertTrue(d.accepted());
        assertEquals(0.95, d.confidence());
    }

    @Test
    void unknownSourceIsRejected() {
        Decision d = policy.evaluate("FACT", "some fact", "MYSTERY_SOURCE", false);
        assertFalse(d.accepted());
        assertEquals("unknown source: MYSTERY_SOURCE", d.reason());
    }

    @Test
    void normalizedUppercaseTypeIsAccepted() {
        Decision d = policy.evaluate("preference", "plain content here", "ADMIN_CONFIG", false);
        assertTrue(d.accepted(), "key 应升序归一化后命中白名单");
    }

    private void assertRejected(String type, String content) {
        Decision d = policy.evaluate(type, content, "USER_EXPLICIT", true);
        assertFalse(d.accepted(), "应拦截敏感内容: " + content);
        assertEquals("content may contain sensitive information", d.reason());
    }
}