package com.argus.rag.assistant.trace;

import com.argus.rag.assistant.loop.TaskLoopState;
import com.argus.rag.assistant.model.ResultShape;
import com.argus.rag.assistant.trace.entity.TaskTraceEntity;
import com.argus.rag.assistant.trace.mapper.TaskTraceMapper;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.task.TaskChannel;
import com.argus.rag.verify.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 任务轨迹记录器契约（第十步）：字段完整映射（循环账本+验证+形态）、
 * 无 traceId 跳过、失败 fail-soft（提交拒绝/落库异常都不传播）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("任务轨迹记录器（TaskTraceRecorder）")
class TaskTraceRecorderTest {

    private static final String TRACE_ID = "trace-abc-12345678";

    @Mock
    private TaskTraceMapper taskTraceMapper;

    private TaskTraceRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new TaskTraceRecorder(taskTraceMapper, Runnable::run,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private TaskLoopState stateWithRound() {
        TaskLoopState state = TaskLoopState.defaults();
        state.nextRound();
        return state;
    }

    private TaskTraceRecorder.TaskTraceRecord sampleRecord() {
        return new TaskTraceRecorder.TaskTraceRecord(
                TRACE_ID, 9L, 7L, SystemRole.USER.name(), TaskChannel.ASSISTANT,
                18, stateWithRound(),
                new VerificationResult(false, 30, List.of("10 天"), "数字无证据"),
                ResultShape.REFERENCE, 250, 1200L);
    }

    @Test
    void persistsTraceFieldsFaithfully() {
        recorder.record(sampleRecord());

        ArgumentCaptor<TaskTraceEntity> captor = ArgumentCaptor.forClass(TaskTraceEntity.class);
        verify(taskTraceMapper).insert(captor.capture());
        TaskTraceEntity entity = captor.getValue();
        assertEquals(TRACE_ID, entity.getTraceId());
        assertEquals(9L, entity.getSessionId());
        assertEquals(7L, entity.getUserId());
        assertEquals("USER", entity.getSystemRole());
        assertEquals("ASSISTANT", entity.getChannel());
        assertEquals(18, entity.getQuestionChars());
        assertEquals(1, entity.getRoundsUsed());
        assertEquals(3, entity.getMaxRounds());
        assertEquals(0, entity.getDecisionCount());
        assertEquals(0, entity.getOutOfPlanCount());
        assertEquals("[]", entity.getDecisionsJson(), "无决策时为空数组");
        assertEquals(Boolean.FALSE, entity.getVerificationSupported());
        assertEquals(30, entity.getVerificationScore());
        assertTrue(entity.getUnsupportedClaims().contains("10 天"));
        assertEquals("REFERENCE", entity.getResultShape());
        assertEquals(250, entity.getReplyChars());
        assertEquals(1200L, entity.getLatencyMs());
    }

    @Test
    void blankTraceIdSkips() {
        recorder.record(new TaskTraceRecorder.TaskTraceRecord(
                "  ", 9L, 7L, null, TaskChannel.ASSISTANT, 0, null, null, null, 0, 0L));
        verifyNoInteractions(taskTraceMapper);
    }

    @Test
    void submissionRejectionDoesNotPropagate() {
        Executor failing = task -> {
            throw new RejectedExecutionException("pool saturated");
        };
        TaskTraceRecorder failingRecorder = new TaskTraceRecorder(taskTraceMapper, failing,
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertDoesNotThrow(() -> failingRecorder.record(sampleRecord()), "线程池拒绝只告警不传播");
        verifyNoInteractions(taskTraceMapper);
    }

    @Test
    void persistFailureDoesNotPropagate() {
        doThrow(new RuntimeException("db down")).when(taskTraceMapper).insert(any(TaskTraceEntity.class));
        assertDoesNotThrow(() -> recorder.record(sampleRecord()), "落库异常只告警不传播（留痕是旁路）");
    }
}
