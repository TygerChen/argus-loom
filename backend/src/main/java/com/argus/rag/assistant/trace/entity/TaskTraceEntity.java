package com.argus.rag.assistant.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 任务轨迹实体（task_trace 表）—— 一次助手对话的"循环账本"落库形态。
 * <p>字段语义见 {@link com.argus.rag.assistant.loop.TaskLoopState} 与
 * {@link com.argus.rag.verify.VerificationResult}；隐私边界见 V16 迁移注释
 * （只存长度与计数，不存问题/答案原文）。</p>
 */
@TableName("task_trace")
public class TaskTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private Long sessionId;
    private Long userId;
    private String systemRole;
    private String channel;
    private Integer questionChars;
    private Integer roundsUsed;
    private Integer maxRounds;
    private Integer decisionCount;
    private Integer outOfPlanCount;
    /** 决策留痕 JSON（工具名+参数摘要+是否在清单，cap 12 条——可审计 reasoning 素材） */
    private String decisionsJson;
    private Boolean verificationSupported;
    private Integer verificationScore;
    private String unsupportedClaims;
    private String resultShape;
    private Integer replyChars;
    private Long latencyMs;
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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSystemRole() {
        return systemRole;
    }

    public void setSystemRole(String systemRole) {
        this.systemRole = systemRole;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Integer getQuestionChars() {
        return questionChars;
    }

    public void setQuestionChars(Integer questionChars) {
        this.questionChars = questionChars;
    }

    public Integer getRoundsUsed() {
        return roundsUsed;
    }

    public void setRoundsUsed(Integer roundsUsed) {
        this.roundsUsed = roundsUsed;
    }

    public Integer getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(Integer maxRounds) {
        this.maxRounds = maxRounds;
    }

    public Integer getDecisionCount() {
        return decisionCount;
    }

    public void setDecisionCount(Integer decisionCount) {
        this.decisionCount = decisionCount;
    }

    public Integer getOutOfPlanCount() {
        return outOfPlanCount;
    }

    public void setOutOfPlanCount(Integer outOfPlanCount) {
        this.outOfPlanCount = outOfPlanCount;
    }

    public String getDecisionsJson() {
        return decisionsJson;
    }

    public void setDecisionsJson(String decisionsJson) {
        this.decisionsJson = decisionsJson;
    }

    public Boolean getVerificationSupported() {
        return verificationSupported;
    }

    public void setVerificationSupported(Boolean verificationSupported) {
        this.verificationSupported = verificationSupported;
    }

    public Integer getVerificationScore() {
        return verificationScore;
    }

    public void setVerificationScore(Integer verificationScore) {
        this.verificationScore = verificationScore;
    }

    public String getUnsupportedClaims() {
        return unsupportedClaims;
    }

    public void setUnsupportedClaims(String unsupportedClaims) {
        this.unsupportedClaims = unsupportedClaims;
    }

    public String getResultShape() {
        return resultShape;
    }

    public void setResultShape(String resultShape) {
        this.resultShape = resultShape;
    }

    public Integer getReplyChars() {
        return replyChars;
    }

    public void setReplyChars(Integer replyChars) {
        this.replyChars = replyChars;
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
