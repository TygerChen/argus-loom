package com.argus.rag.common.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 入口审计流水实体（entry_log 表）。
 * <p>一行 = 一次进入业务通道的请求（挂号台存根）。字段语义见
 * {@link com.argus.rag.common.task.TaskTicket}——实体是票据的落库形态，
 * 多出的 {@code createdAt} 是入库时刻（与 receivedAt 区分：异步落库有延迟）。</p>
 */
@TableName("entry_log")
public class EntryLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路追踪号（与日志 MDC 同源，跨表串联的钥匙） */
    private String traceId;

    /** 运行器任务编号（预留，入口阶段恒为 null） */
    private Long taskId;

    /** 业务通道：KB_QA / ASSISTANT / EXCEL / FINANCE */
    private String channel;

    /** 请求形态，如 "POST /api/qa/ask" */
    private String endpoint;

    /** 系统确认的用户 ID（未认证为 null） */
    private Long userId;

    /** 用户业务编号（未认证为 null） */
    private String userCode;

    /** 系统角色（未认证为 null） */
    private String systemRole;

    /** 入口裁决：ACCEPTED / REJECTED */
    private String decision;

    /** HTTP 状态码 */
    private Integer statusCode;

    /** 拒收原因编码（如 HTTP_403），收下时为 null */
    private String rejectCode;

    /** 用户输入疑似包含身份声明（只标记不裁决） */
    private Boolean identityClaim;

    /** 用户输入长度（不记原文） */
    private Integer inputChars;

    /** 会话 ID（助手会话/问答会话，入口可捕获时记录） */
    private Long sessionId;

    /** 租户 ID（当前单租户恒为 null，多租户演进预留） */
    private String tenantId;

    /** 风险级别 LOW/HIGH（敏感动作入口即标 HIGH，只标记不裁决） */
    private String riskLevel;

    /** 动作类型 READ/DELETE/EXPORT/EXTERNAL_SEND/BATCH_MODIFY */
    private String actionType;

    /** 是否需要人工确认门（HIGH 恒 true，运行器执行） */
    private Boolean needConfirmation;

    /** 输入命中注入模式（安全事件标记） */
    private Boolean safetyFlagged;

    /** 疑似滑动窗口内重复请求（只标记不拒绝） */
    private Boolean dupSuspected;

    /** 用户请求体声明的知识库群组（搜索范围声明，非权限授予） */
    private String requestedGroups;

    /** 请求到达时刻 */
    private LocalDateTime receivedAt;

    /** 入口处理耗时（毫秒，含整条调用链） */
    private Long latencyMs;

    /** 落库时刻（异步写入，晚于 receivedAt） */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = userCode;
    }

    public String getSystemRole() {
        return systemRole;
    }

    public void setSystemRole(String systemRole) {
        this.systemRole = systemRole;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getRejectCode() {
        return rejectCode;
    }

    public void setRejectCode(String rejectCode) {
        this.rejectCode = rejectCode;
    }

    public Boolean getIdentityClaim() {
        return identityClaim;
    }

    public void setIdentityClaim(Boolean identityClaim) {
        this.identityClaim = identityClaim;
    }

    public Integer getInputChars() {
        return inputChars;
    }

    public void setInputChars(Integer inputChars) {
        this.inputChars = inputChars;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public Boolean getNeedConfirmation() {
        return needConfirmation;
    }

    public void setNeedConfirmation(Boolean needConfirmation) {
        this.needConfirmation = needConfirmation;
    }

    public Boolean getSafetyFlagged() {
        return safetyFlagged;
    }

    public void setSafetyFlagged(Boolean safetyFlagged) {
        this.safetyFlagged = safetyFlagged;
    }

    public Boolean getDupSuspected() {
        return dupSuspected;
    }

    public void setDupSuspected(Boolean dupSuspected) {
        this.dupSuspected = dupSuspected;
    }

    public String getRequestedGroups() {
        return requestedGroups;
    }

    public void setRequestedGroups(String requestedGroups) {
        this.requestedGroups = requestedGroups;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
