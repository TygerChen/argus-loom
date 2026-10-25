package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.document.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 启动时回收遗留在 PROCESSING 状态的文档。
 *
 * <p>当服务异常中断时，正在处理的文档会长时间保持在 PROCESSING 状态，导致前端永远显示"处理中"。
 * 本组件在 Spring 启动完成后扫描 PROCESSING 文档中"超过阈值未更新"的遗留，标记为 FAILED，
 * 用户可通过重试功能重新触发处理。</p>
 * <p><b>为什么要有时间窗（默认 10 分钟，可配 {@code document.ingestion.stale-processing-threshold-minutes}）</b>：
 * 多实例部署下，新实例启动时其他活实例可能仍在处理文档（updated_at 持续刷新）。
 * 无窗口全量标记会误杀这些在途文档（曾实现为传 null 全量标记，多实例审计发现）。
 * 被误标也不要紧：遗留任务在队列中被 Worker 租约接管执行完成后会把状态改回 READY——
 * 但 FAILED→READY 的状态跳变对用户可见，窗口从源头减少这类噪音。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Component
@Slf4j
public class StaleProcessingDocumentRecoveryRunner implements ApplicationRunner {

    /** 默认失败原因：服务中断导致处理未完成 */
    private static final String DEFAULT_FAILURE_REASON = "文档处理任务因服务中断未完成，请重试。";

    /** 文档数据访问 */
    private final DocumentMapper documentMapper;

    /** 判定"遗留"的时间窗（分钟）：PROCESSING 且超过该时长未更新才回收 */
    private final long staleThresholdMinutes;

    /**
     * 构造启动恢复组件。
     *
     * @param documentMapper       文档数据访问层
     * @param staleThresholdMinutes 遗留判定阈值（分钟），默认 10
     */
    public StaleProcessingDocumentRecoveryRunner(
            DocumentMapper documentMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${document.ingestion.stale-processing-threshold-minutes:10}")
            long staleThresholdMinutes) {
        this.documentMapper = documentMapper;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    /**
     * 应用启动后执行：将遗留的 PROCESSING 文档标记为 FAILED。
     *
     * <p>在事务中执行，确保标记操作的原子性。完成后输出回收数量或"无需回收"日志。
     *
     * @param args 应用启动参数
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now();
        int recovered = documentMapper.markStaleProcessingDocumentsFailed(
                DocumentStatus.PROCESSING.name(),
                DocumentStatus.FAILED.name(),
                DEFAULT_FAILURE_REASON,
                now.minusMinutes(staleThresholdMinutes),
                now
        );
        if (recovered > 0) {
            log.warn(
                    "启动回收遗留处理中文档: recoveredCount={}",
                    recovered
            );
        } else {
            log.info("启动检查遗留处理中文档完成，无需回收");
        }
    }
}
