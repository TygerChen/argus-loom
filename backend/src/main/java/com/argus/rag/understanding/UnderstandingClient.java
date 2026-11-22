package com.argus.rag.understanding;

/**
 * 任务理解的 LLM 端口 —— 语义理解与具体 ChatClient 装配解耦的可测性设计。
 *
 * <p>生产实现由 {@code TaskUnderstandingConfiguration} 提供（ChatClient 适配）；
 * 测试直接注入桩实现（返回固定 JSON/非法文本），无需 mock 流式 Fluent API。
 * 与入口审计的 {@code EntryAuditSink} 同款端口模式。</p>
 */
@FunctionalInterface
public interface UnderstandingClient {

    /**
     * 发送任务理解提示词并返回模型原始文本输出。
     *
     * @param userPrompt 渲染后的完整提示词（含前置约束 + 问题 + 输出 schema）
     * @return 模型输出原文（可能不合法——合法性由编排层校验）
     */
    String complete(String userPrompt);
}
