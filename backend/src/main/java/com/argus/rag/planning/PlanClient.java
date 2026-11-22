package com.argus.rag.planning;

/**
 * 方案提议的 LLM 端口（与 {@code UnderstandingClient} 同款端口模式：
 * 语义提议与 ChatClient 装配解耦，测试注入桩实现）。
 */
@FunctionalInterface
public interface PlanClient {

    /**
     * 发送方案提议提示词，返回模型原始文本（合法性由编排层校验）。
     */
    String complete(String userPrompt);
}
