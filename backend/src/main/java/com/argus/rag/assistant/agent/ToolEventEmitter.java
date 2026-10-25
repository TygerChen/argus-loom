package com.argus.rag.assistant.agent;

import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Agent 工具调用事件发射器。
 * <p>工具在调用前后通过此发射器广播事件。使用 ThreadLocal 存储当前请求的监听器，
 * 确保并发请求之间的事件不会互相泄漏。</p>
 * <p>每个流式请求开始时调用 {@link #register(Consumer)} 绑定监听器，
 * 结束时调用 {@link #clear()} 解绑。</p>
 */
@Component
public class ToolEventEmitter {

    private final ThreadLocal<Consumer<ToolEvent>> listenerHolder = new ThreadLocal<>();

    /**
     * 注册事件监听器（每次 streamChat 调用时绑定到当前线程）。
     */
    public void register(Consumer<ToolEvent> listener) {
        listenerHolder.set(listener);
    }

    /**
     * 解除当前线程的监听器绑定（streamChat 结束时调用）。
     */
    public void clear() {
        listenerHolder.remove();
    }

    /**
     * 发射工具事件到当前线程绑定的监听器。
     * 如果没有注册监听器（同步调用或未绑定），静默忽略。
     */
    public void emit(ToolEvent event) {
        Consumer<ToolEvent> listener = listenerHolder.get();
        if (listener != null) {
            listener.accept(event);
        }
    }

    /**
     * 工具事件。
     *
     * @param toolName 工具名称
     * @param stage    阶段：START（开始调用）或 RESULT（调用完成）
     * @param summary  结果摘要（RESULT 阶段时填充）
     */
    public record ToolEvent(String toolName, String stage, String summary) {
        public static ToolEvent start(String toolName) {
            return new ToolEvent(toolName, "START", null);
        }

        public static ToolEvent result(String toolName, String summary) {
            return new ToolEvent(toolName, "RESULT", summary);
        }
    }
}