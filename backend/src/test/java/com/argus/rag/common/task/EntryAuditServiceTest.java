package com.argus.rag.common.task;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.task.entity.EntryLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 入口审计服务契约：字段完整映射、失败 fail-soft（提交拒绝/落库异常都不传播）。
 * <p>执行器注入 {@code Runnable::run} 同步直跑（不 mock 线程池）；
 * 存储走 {@link EntryAuditSink} 端口，测试只认端口。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("入口审计服务（EntryAuditService）")
class EntryAuditServiceTest {

    private static final String TRACE_ID = "trace-abc-12345678";
    private static final String ENDPOINT_QA = "POST /api/qa/ask";
    private static final String ENDPOINT_ASSISTANT = "POST /api/assistant/chat";

    @Mock
    private EntryAuditSink auditSink;

    private EntryAuditService service;

    @BeforeEach
    void setUp() {
        service = new EntryAuditService(auditSink, Runnable::run);
    }

    private TaskTicket rejectedTicket() {
        return new TaskTicket(
                TRACE_ID,
                null,
                null,
                3001L,
                TaskChannel.KB_QA,
                ENDPOINT_QA,
                7L,
                "U0007",
                SystemRole.USER,
                false,
                true,
                false,
                true,
                18,
                "1,3",
                TaskActionType.EXPORT,
                TaskRiskLevel.HIGH,
                true,
                TaskDecision.REJECTED,
                403,
                "HTTP_403",
                LocalDateTime.of(2026, 11, 21, 10, 0),
                42L
        );
    }

    @Test
    void persistsTicketFieldsFaithfully() {
        service.audit(rejectedTicket());

        ArgumentCaptor<EntryLogEntity> captor = ArgumentCaptor.forClass(EntryLogEntity.class);
        verify(auditSink).accept(captor.capture());
        EntryLogEntity entity = captor.getValue();
        assertEquals(TRACE_ID, entity.getTraceId());
        assertNull(entity.getTaskId(), "task_id 是运行器预留位");
        assertEquals("KB_QA", entity.getChannel());
        assertEquals(ENDPOINT_QA, entity.getEndpoint());
        assertEquals(7L, entity.getUserId());
        assertEquals("U0007", entity.getUserCode());
        assertEquals("USER", entity.getSystemRole());
        assertEquals("REJECTED", entity.getDecision());
        assertEquals(403, entity.getStatusCode());
        assertEquals("HTTP_403", entity.getRejectCode());
        assertEquals(Boolean.TRUE, entity.getIdentityClaim());
        assertEquals(18, entity.getInputChars());
        assertEquals(3001L, entity.getSessionId());
        assertEquals("EXPORT", entity.getActionType());
        assertEquals("HIGH", entity.getRiskLevel());
        assertEquals(Boolean.TRUE, entity.getNeedConfirmation());
        assertEquals(Boolean.FALSE, entity.getSafetyFlagged());
        assertEquals(Boolean.TRUE, entity.getDupSuspected());
        assertEquals("1,3", entity.getRequestedGroups());
        assertEquals(42L, entity.getLatencyMs());
        assertNotNull(entity.getCreatedAt(), "落库时刻由服务端生成");
        assertNotNull(entity.getReceivedAt());
    }

    @Test
    void acceptedTicketHasNoRejectCode() {
        TaskTicket accepted = new TaskTicket(
                TRACE_ID,
                null,
                null,
                null,
                TaskChannel.ASSISTANT,
                ENDPOINT_ASSISTANT,
                7L,
                "U0007",
                SystemRole.USER,
                false,
                false,
                false,
                false,
                100,
                null,
                TaskActionType.READ,
                TaskRiskLevel.LOW,
                false,
                TaskDecision.ACCEPTED,
                200,
                null,
                LocalDateTime.now(),
                10L
        );
        service.audit(accepted);

        ArgumentCaptor<EntryLogEntity> captor = ArgumentCaptor.forClass(EntryLogEntity.class);
        verify(auditSink).accept(captor.capture());
        assertEquals("ACCEPTED", captor.getValue().getDecision());
        assertNull(captor.getValue().getRejectCode());
    }

    @Test
    void nullTicketIsSkippedSilently() {
        assertDoesNotThrow(() -> service.audit(null));
        verifyNoInteractions(auditSink);
    }

    @Test
    void submissionRejectionDoesNotPropagate() {
        Executor failingExecutor = task -> {
            throw new RejectedExecutionException("pool saturated");
        };
        EntryAuditService failingService = new EntryAuditService(auditSink, failingExecutor);
        assertDoesNotThrow(() -> failingService.audit(rejectedTicket()),
                "线程池拒绝只告警不传播");
        verifyNoInteractions(auditSink);
    }

    @Test
    void sinkFailureDoesNotPropagate() {
        doThrow(new RuntimeException("storage unavailable"))
                .when(auditSink).accept(any(EntryLogEntity.class));
        assertDoesNotThrow(() -> service.audit(rejectedTicket()),
                "存储异常只告警不传播（审计是旁路）");
    }
}
