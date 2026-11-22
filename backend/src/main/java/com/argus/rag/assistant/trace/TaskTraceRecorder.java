package com.argus.rag.assistant.trace;

import com.argus.rag.assistant.loop.TaskLoopState;
import com.argus.rag.assistant.trace.entity.TaskTraceEntity;
import com.argus.rag.assistant.trace.mapper.TaskTraceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 任务轨迹记录器 —— 第十步"收尾与留痕"的落库执行者。
 *
 * <p><b>它补齐什么</b>：循环账本（TaskLoopState）此前是内存态、请求结束即丢；
 * 本记录器把"轮次/决策/越界/预算"与"验证结论/结果形态"合成一行 task_trace，
 * trace_id 串联 entry_log 与全链路日志——完整链路从此可追溯、可复用
 * （复用答案/复用检索路径是后续演进）。</p>
 *
 * <p><b>失败语义</b>：与入口审计同款——异步提交、任何失败只告警不传播
 * （留痕是旁路，绝不拖垮交付主流程）。</p>
 */
@Service
public class TaskTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TaskTraceRecorder.class);
    private static final int CLAIMS_MAX_CHARS = 500;

    private final TaskTraceMapper taskTraceMapper;
    private final Executor taskExecutor;
    private final ObjectMapper objectMapper;

    public TaskTraceRecorder(
            TaskTraceMapper taskTraceMapper,
            @Qualifier("taskExecutor") Executor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this.taskTraceMapper = taskTraceMapper;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步落库一条任务轨迹（traceId 为空则跳过——没有追踪号的留痕无法归因）。
     */
    public void record(TaskTraceRecord record) {
        if (record == null || record.traceId() == null || record.traceId().isBlank()) {
            return;
        }
        try {
            taskExecutor.execute(() -> persistQuietly(record));
        } catch (RuntimeException submissionFailure) {
            log.warn("任务轨迹提交线程池失败(不影响主流程): traceId={}, reason={}",
                    record.traceId(), submissionFailure.getMessage());
        }
    }

    private void persistQuietly(TaskTraceRecord record) {
        try {
            taskTraceMapper.insert(toEntity(record));
        } catch (RuntimeException persistFailure) {
            log.warn("任务轨迹落库失败(不影响主流程): traceId={}, reason={}",
                    record.traceId(), persistFailure.getMessage());
        }
    }

    private TaskTraceEntity toEntity(TaskTraceRecord record) {
        TaskLoopState state = record.loopState();
        TaskTraceEntity entity = new TaskTraceEntity();
        entity.setTraceId(record.traceId());
        entity.setSessionId(record.sessionId());
        entity.setUserId(record.userId());
        entity.setSystemRole(record.systemRole());
        entity.setChannel(record.channel() == null ? "ASSISTANT" : record.channel().name());
        entity.setQuestionChars(record.questionChars());
        if (state != null) {
            entity.setRoundsUsed(state.currentRound());
            entity.setMaxRounds(state.maxRounds());
            entity.setDecisionCount(state.decisions().size());
            entity.setOutOfPlanCount(state.outOfPlanDecisionCount());
            entity.setDecisionsJson(decisionsJson(state.decisions()));
        }
        if (record.verification() != null && !record.verification().isEmpty()) {
            entity.setVerificationSupported(record.verification().supported());
            entity.setVerificationScore(record.verification().score());
            entity.setUnsupportedClaims(joinClaims(record.verification().unsupportedClaims()));
        }
        entity.setResultShape(record.shape() == null ? null : record.shape().name());
        entity.setReplyChars(record.replyChars());
        entity.setLatencyMs(record.latencyMs());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    /** 决策留痕 JSON（序列化失败降级为计数摘要——留痕尽力而为）。 */
    private String decisionsJson(List<TaskLoopState.DecisionRecord> decisions) {
        try {
            return objectMapper.writeValueAsString(decisions);
        } catch (Exception serializeFailure) {
            return "[]";
        }
    }

    private String joinClaims(List<String> claims) {
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        String joined = String.join("；", claims);
        return joined.length() <= CLAIMS_MAX_CHARS ? joined : joined.substring(0, CLAIMS_MAX_CHARS) + "…";
    }

    /**
     * 轨迹记录命令对象（组装方：AssistantService 交付段）。
     *
     * @param traceId      追踪号（必填）
     * @param sessionId    会话 ID
     * @param userId       用户 ID
     * @param systemRole   系统角色
     * @param channel      业务通道
     * @param questionChars 问题长度（不存原文）
     * @param loopState    循环账本（内存态快照）
     * @param verification 独立校验结果（可 null）
     * @param shape        结果形态
     * @param replyChars   回复长度
     * @param latencyMs    总耗时
     */
    public record TaskTraceRecord(
            String traceId,
            Long sessionId,
            Long userId,
            String systemRole,
            com.argus.rag.common.task.TaskChannel channel,
            Integer questionChars,
            TaskLoopState loopState,
            com.argus.rag.verify.VerificationResult verification,
            com.argus.rag.assistant.model.ResultShape shape,
            Integer replyChars,
            Long latencyMs
    ) {
    }
}
