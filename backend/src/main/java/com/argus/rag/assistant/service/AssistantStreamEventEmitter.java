package com.argus.rag.assistant.service;

import com.argus.rag.assistant.agent.ToolEventEmitter;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;

/**
 * 流式聊天事件发射器接口。
 * <p>定义 SSE 事件输出契约，用于将 {@link AssistantChatStreamEvent} 事件推送给前端。</p>
 */
public interface AssistantStreamEventEmitter {

    /**
     * 发送一个流式聊天事件
     *
     * @param event 聊天流事件对象，不能为 null
     */
    void emit(AssistantChatStreamEvent event);

    /**
     * 发送工具调用事件（默认通过 emit 委托 toolCall 静态工厂）。
     */
    default void emitToolEvent(ToolEventEmitter.ToolEvent toolEvent) {
        emit(AssistantChatStreamEvent.toolCall(
                null, null, null,
                toolEvent.toolName(),
                toolEvent.stage()
        ));
    }
}
