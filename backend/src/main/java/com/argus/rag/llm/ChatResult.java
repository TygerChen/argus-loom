package com.argus.rag.llm;

/**
 * LLM 调用结果。
 *
 * @param content       模型生成的文本回复
 * @param responseId    服务端响应 ID，用于问题排查和链路追踪
 * @param inputTokens   输入 Token 数
 * @param outputTokens  输出 Token 数
 * @param modelName     实际使用的模型名称
 */
public record ChatResult(
        String content,
        String responseId,
        int inputTokens,
        int outputTokens,
        String modelName
) {
    /**
     * 创建一个空的失败结果。
     */
    public static ChatResult empty() {
        return new ChatResult(null, null, 0, 0, null);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}