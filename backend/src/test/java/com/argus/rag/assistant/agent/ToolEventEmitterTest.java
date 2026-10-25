package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.agent.ToolEventEmitter.ToolEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolEventEmitter} 工具事件发射契约（ThreadLocal，纯 JVM/零 mock/零网络）。
 * register+emit → 监听器按序收到事件；emit 无监听器静默；clear 解绑后 emit 失效；
 * {@link ToolEvent#start}/{@link ToolEvent#result} 工厂固定 stage/summary。
 */
@DisplayName("工具事件发射器（ToolEventEmitter）")
class ToolEventEmitterTest {

    private final ToolEventEmitter emitter = new ToolEventEmitter();

    @Test
    void emitDeliversToRegisteredListenerInOrder() {
        List<ToolEvent> received = new ArrayList<>();
        emitter.register(received::add);
        emitter.emit(ToolEvent.start("search"));
        emitter.emit(ToolEvent.result("search", "3 条结果"));
        assertEquals(2, received.size());
        assertEquals("search", received.get(0).toolName());
        assertEquals("START", received.get(0).stage());
        assertEquals("3 条结果", received.get(1).summary());
    }

    @Test
    void emitWithoutListenerIsSilentNoOp() {
        AtomicReference<ToolEvent> sink = new AtomicReference<>();
        emitter.emit(ToolEvent.start("x"));
        assertNull(sink.get()); // no listener, no throw
    }

    @Test
    void clearDetachesListenerForSameThread() {
        List<ToolEvent> received = new ArrayList<>();
        emitter.register(received::add);
        emitter.clear();
        emitter.emit(ToolEvent.start("search"));
        assertTrue(received.isEmpty());
    }

    @Test
    void reRegisterOverridesPreviousListener() {
        List<ToolEvent> first = new ArrayList<>();
        List<ToolEvent> second = new ArrayList<>();
        emitter.register(first::add);
        emitter.register(second::add);
        emitter.emit(ToolEvent.start("tool"));
        assertTrue(first.isEmpty());
        assertEquals(1, second.size());
    }

    @Test
    void startFactorySetsStageAndEmptySummary() {
        ToolEvent e = ToolEvent.start("qa");
        assertEquals("qa", e.toolName());
        assertEquals("START", e.stage());
        assertNull(e.summary());
    }

    @Test
    void resultFactoryCarriesSummary() {
        ToolEvent e = ToolEvent.result("qa", "答案");
        assertEquals("RESULT", e.stage());
        assertEquals("答案", e.summary());
    }

    @Test
    void listenersAreThreadIsolated() throws Exception {
        List<ToolEvent> mainSink = new ArrayList<>();
        emitter.register(mainSink::add);
        AtomicReference<ToolEvent> otherReceived = new AtomicReference<>();
        Thread other = new Thread(() -> {
            emitter.emit(ToolEvent.start("cross"));
        });
        other.start();
        other.join();
        assertNull(otherReceived.get()); // 另一线程无监听 → 静默
        assertEquals(0, mainSink.size()); // 本线程监听器未收到子线程事件
        emitter.emit(ToolEvent.start("own"));
        assertEquals(1, mainSink.size());
    }
}