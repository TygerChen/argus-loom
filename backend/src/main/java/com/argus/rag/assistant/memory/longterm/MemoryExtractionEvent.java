package com.argus.rag.assistant.memory.longterm;

/**
 * 记忆抽取事件：在助手消息落库之后发布。
 * 由 {@link MemoryExtractionListener} 异步处理。
 */
public record MemoryExtractionEvent(
        Long userId,
        Long tenantId,
        Long sessionId,
        String userMessage,
        String reply
) {
}