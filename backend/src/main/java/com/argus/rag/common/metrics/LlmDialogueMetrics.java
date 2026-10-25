package com.argus.rag.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 对话层可观测埋点（成功率、延迟、P99）。
 *
 * <p>project 已启用 actuator + micrometer-registry-prometheus，但代码内没有任何 Micrometer
 * 埋点。本组件为「一次模型对话调用」统一记录耗时与结果。对外暴露唯一指标：</p>
 * <ul>
 *     <li>{@code llm.dialogue}（Timer）：记录单次对话耗时，标签
 *         {@code dialogue=assistant|qa}、{@code mode=sync|stream}、{@code outcome=success|error}；
 *         开启直方图（histogram），Prometheus 上可直接导出 P50/P90/P99。</li>
 * </ul>
 * <p>成功/失败次数由 Timer 的 {@code outcome} 标签维度计数获得（不额外维护 Counter，避免口径膨胀）。</p>
 */
@Component
public class LlmDialogueMetrics {

    private static final String METRIC = "llm.dialogue";

    private final MeterRegistry meterRegistry;

    public LlmDialogueMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 对话开始：取一个计时起点。 */
    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    /**
     * 对话结束：记录耗时，并按结果标记成功/失败。
     *
     * @param sample    {@link #start()} 的计时起点
     * @param dialogue  对话名：assistant / qa
     * @param mode      调用方式：sync / stream
     * @param success   是否成功完成（非空回复），异常则记为失败
     */
    public void finish(Timer.Sample sample, String dialogue, String mode, boolean success) {
        sample.stop(timer(dialogue, mode, success));
    }

    /**
     * 取「单次对话」的计时器（标签固定）。
     * <p>供异步流式路径在 {@code doOnComplete/doOnError} 处延迟结束计时用：入口先
     * {@link #start()}，流结束时调用 {@link #finish(Timer.Sample, Timer)} 登记耗时。</p>
     */
    public Timer timer(String dialogue, String mode, boolean success) {
        return Timer.builder(METRIC)
                .description("单次模型对话的耗时与结果")
                .tag("dialogue", dialogue)
                .tag("mode", mode)
                .tag("outcome", success ? "success" : "error")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(500),
                        Duration.ofMillis(2000),
                        Duration.ofMillis(5000)
                )
                .register(meterRegistry);
    }

    /** 用已构造好的 Timer 结束一次计时（异步终端事件场景）。 */
    public void finish(Timer.Sample sample, Timer timer) {
        sample.stop(timer);
    }
}