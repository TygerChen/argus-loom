package com.argus.rag.llm;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

/**
 * 统一的 LLM 调用适配层接口。
 * <p>隔离供应商 SDK，业务层只依赖此接口，更换模型供应商时只需替换实现类。</p>
 * <p>文章"如何设计模型适配层"一节建议的最佳实践。</p>
 */
public interface ModelClient {

    /**
     * 同步聊天。
     *
     * @param messages     对话消息列表（role + content）
     * @param instructions 系统指令/行为约束
     * @return 聊天结果，包含回复文本和用量信息
     */
    ChatResult chat(List<ChatMessage> messages, String instructions);

    /**
     * 流式聊天。
     * <p>模型生成的文本增量通过 {@code deltaConsumer} 逐段推送，
     * 返回值中的 reply 为完整回复文本。</p>
     *
     * @param messages      对话消息列表
     * @param instructions  系统指令
     * @param deltaConsumer 流式增量回调
     * @return 聊天结果
     */
    ChatResult streamChat(List<ChatMessage> messages, String instructions, Consumer<String> deltaConsumer);

    /**
     * 对话消息。
     */
    record ChatMessage(String role, String content) {
    }
}