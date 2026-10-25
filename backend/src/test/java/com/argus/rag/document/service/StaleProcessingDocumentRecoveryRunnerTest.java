package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.document.mapper.DocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link StaleProcessingDocumentRecoveryRunner} 启动回收遗留 PROCESSING 文档契约（mock mapper，纯 JVM/零 DB）。
 * staleBefore 必须是"当前时间减阈值"（默认 10 分钟）而非 null——多实例部署下新实例启动时，
 * 其他活实例 10 分钟内仍在更新的 PROCESSING 文档不能被误杀（曾传 null 全量标记，对抗性审查发现）。
 */
@DisplayName("遗留处理中文档回收（StaleProcessingDocumentRecoveryRunner）")
class StaleProcessingDocumentRecoveryRunnerTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final StaleProcessingDocumentRecoveryRunner runner =
            new StaleProcessingDocumentRecoveryRunner(documentMapper, 10);

    @Test
    void runMarksStaleProcessingAsFailedWithReasonAndNow() {
        runner.run(new DefaultApplicationArguments());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentMapper).markStaleProcessingDocumentsFailed(
                eq(DocumentStatus.PROCESSING.name()),
                eq(DocumentStatus.FAILED.name()),
                reason.capture(),
                staleBefore.capture(),
                now.capture());
        assertFalse(reason.getValue().isBlank());
        assertNotNull(now.getValue());
        // 时间窗 ≈ now - 10 分钟：只回收超过阈值未更新的遗留，不误杀活实例在途文档
        LocalDateTime lower = LocalDateTime.now().minusMinutes(11);
        LocalDateTime upper = LocalDateTime.now().minusMinutes(9);
        org.junit.jupiter.api.Assertions.assertTrue(
                staleBefore.getValue().isAfter(lower) && staleBefore.getValue().isBefore(upper),
                "staleBefore 应约为 now-10min，实际=" + staleBefore.getValue());
    }

    @Test
    void runIsInvariantToApplicationArgumentsAndStatusNames() {
        runner.run(null);
        verify(documentMapper).markStaleProcessingDocumentsFailed(
                anyString(), anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }
}