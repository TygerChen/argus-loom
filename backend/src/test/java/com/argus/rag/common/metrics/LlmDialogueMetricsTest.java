package com.argus.rag.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmDialogueMetrics} 对话可观测埋点契约（SimpleMeterRegistry 纯 JVM，无真实 Prometheus）。
 * 指标名 llm.dialogue、标签 dialogue/mode/outcome 固定；start/finish 后 Timer 计数+耗时登记成功。
 */
@DisplayName("对话可观测埋点（LlmDialogueMetrics）")
class LlmDialogueMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmDialogueMetrics metrics = new LlmDialogueMetrics(registry);

    @Test
    void timerCarriesFixedTags() {
        io.micrometer.core.instrument.Timer t = metrics.timer("assistant", "stream", true);
        assertEquals("assistant", t.getId().getTag("dialogue"));
        assertEquals("stream", t.getId().getTag("mode"));
        assertEquals("success", t.getId().getTag("outcome"));
        assertTrue(t.getId().getName().equals("llm.dialogue"));
    }

    @Test
    void finishRegistersTimerWithOutcome() {
        io.micrometer.core.instrument.Timer.Sample sample = metrics.start();
        metrics.finish(sample, "qa", "sync", false);
        var timer = registry.find("llm.dialogue").tag("outcome", "error").tag("dialogue", "qa").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 0);
    }

    @Test
    void successTagMapsToColumns() {
        io.micrometer.core.instrument.Timer.Sample sample = metrics.start();
        metrics.finish(sample, "assistant", "sync", true);
        assertNotNull(registry.find("llm.dialogue").tag("outcome", "success").tag("mode", "sync").timer());
    }
}