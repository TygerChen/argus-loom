package com.argus.rag.verify;

/**
 * 独立校验的 LLM 端口（裁判调用与 ChatClient 装配解耦——与
 * UnderstandingClient/PlanClient 同款端口模式）。
 */
@FunctionalInterface
public interface VerifyClient {

    /**
     * 发送校验提示词，返回裁判模型原始文本（合法性由编排层校验）。
     */
    String complete(String userPrompt);
}
