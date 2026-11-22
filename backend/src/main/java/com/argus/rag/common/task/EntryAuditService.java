package com.argus.rag.common.task;

import com.argus.rag.common.task.entity.EntryLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/**
 * 入口审计服务 —— 把任务单异步落库到 entry_log（经由 {@link EntryAuditSink} 端口）。
 *
 * <p><b>失败语义（与项目"记账失败不废回答"原则同源）</b>：入口审计是
 * <b>尽力而为的旁路</b>——提交线程池被拒、存储抛异常，一律只打 WARN，
 * 绝不向上传播。原因：审计挂了影响的是"事后可查"，把它接成主链路的强依赖，
 * 等于让挂号台的登记簿钢笔没水时医院停诊。</p>
 *
 * <p><b>为什么显式提交而不是 @Async</b>：本项目约定异步一律复用
 * {@code AppThreadPoolConfig.taskExecutor}，且显式提交可规避
 * "@Async 自调用不代理"的经典坑（QaConversationMemoryService 同款选择）。</p>
 *
 * <p><b>测试友好</b>：执行器参数声明为 {@link Executor} 抽象——生产由
 * {@code taskExecutor} Bean 供给，测试可直接传 {@code Runnable::run} 同步直跑，
 * 无需 mock 线程池；存储走 {@link EntryAuditSink} 端口，测试只认端口。</p>
 */
@Service
public class EntryAuditService {

    private static final Logger log = LoggerFactory.getLogger(EntryAuditService.class);

    private final EntryAuditSink auditSink;
    private final Executor taskExecutor;

    public EntryAuditService(
            EntryAuditSink auditSink,
            @Qualifier("taskExecutor") Executor taskExecutor
    ) {
        this.auditSink = auditSink;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 审计一张任务单：异步落库到 entry_log。
     *
     * @param ticket 入口任务单（null 直接跳过）
     */
    public void audit(TaskTicket traceTicket) {
        TaskTicket ticket = traceTicket;
        if (ticket == null) {
            return;
        }
        try {
            taskExecutor.execute(() -> persistQuietly(ticket));
        } catch (RuntimeException submissionFailure) {
            log.warn("入口审计提交线程池失败(不影响主流程): traceId={}, reason={}",
                    ticket.traceId(), submissionFailure.getMessage());
        }
    }

    /** 单条落库，任何异常只告警（观察窗口坏了不是业务事故）。 */
    private void persistQuietly(TaskTicket ticket) {
        try {
            auditSink.accept(toEntity(ticket));
        } catch (RuntimeException persistFailure) {
            log.warn("入口审计落库失败(不影响主流程): traceId={}, channel={}, reason={}",
                    ticket.traceId(), ticket.channel(), persistFailure.getMessage());
        }
    }

    private EntryLogEntity toEntity(TaskTicket ticket) {
        EntryLogEntity entity = new EntryLogEntity();
        entity.setTraceId(ticket.traceId());
        entity.setTaskId(ticket.taskId());
        entity.setChannel(ticket.channel() == null ? null : ticket.channel().name());
        entity.setEndpoint(ticket.endpoint());
        entity.setUserId(ticket.userId());
        entity.setUserCode(ticket.userCode());
        entity.setSystemRole(ticket.systemRole() == null ? null : ticket.systemRole().name());
        entity.setDecision(ticket.decision() == null ? null : ticket.decision().name());
        entity.setStatusCode(ticket.statusCode());
        entity.setRejectCode(ticket.rejectCode());
        entity.setIdentityClaim(ticket.identityClaimSuspected());
        entity.setInputChars(inputCharsOf(ticket));
        entity.setSessionId(ticket.sessionId());
        entity.setTenantId(ticket.tenantId());
        entity.setRiskLevel(ticket.riskLevel() == null ? null : ticket.riskLevel().name());
        entity.setActionType(ticket.actionType() == null ? null : ticket.actionType().name());
        entity.setNeedConfirmation(ticket.needConfirmation());
        entity.setSafetyFlagged(ticket.safetyFlagged());
        entity.setDupSuspected(ticket.duplicateSuspected());
        entity.setRequestedGroups(ticket.requestedGroups());
        entity.setReceivedAt(ticket.receivedAt());
        entity.setLatencyMs(ticket.latencyMs());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static Integer inputCharsOf(TaskTicket ticket) {
        return ticket.inputChars();
    }
}
