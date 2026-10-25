package com.argus.rag.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ChatResult} LLM 调用结果 record 契约（纯 JVM/零 mock）。
 * empty() 产出全空失败结果（null/0）；totalTokens() 为 inputTokens+outputTokens。
 */
@DisplayName("LLM 调用结果（ChatResult）")
class ChatResultTest {

    @Test
    void emptyYieldsBlankFailureResult() {
        ChatResult empty = ChatResult.empty();
        assertNull(empty.content());
        assertNull(empty.responseId());
        assertNull(empty.modelName());
        assertEquals(0, empty.inputTokens());
        assertEquals(0, empty.outputTokens());
        assertEquals(0, empty.totalTokens());
    }

    @Test
    void totalTokensSumsInputAndOutput() {
        ChatResult result = new ChatResult("你好", "rsp-1", 120, 45, "gpt-ai");
        assertEquals(165, result.totalTokens());
    }

    @Test
    void fieldsRetainExactValues() {
        ChatResult result = new ChatResult("回答", "rsp-2", 10, 20, "model-x");
        assertEquals("回答", result.content());
        assertEquals("rsp-2", result.responseId());
        assertEquals("model-x", result.modelName());
    }
}