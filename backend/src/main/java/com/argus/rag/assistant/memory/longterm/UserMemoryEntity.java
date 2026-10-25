package com.argus.rag.assistant.memory.longterm;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户长期记忆实体。
 *
 * <p>理论依据：《记忆系统设计》第 2.7 节 — 记忆条目应包含
 * 来源、置信度、重要性、有效期、访问范围和类型字段。</p>
 *
 * <p>每个记忆条目按 (user_id, tenant_id, topic) 唯一标识一个主题，
 * 同主题的新旧记忆通过 status=SUPERSEDED 链式关联。</p>
 */
@Data
public class UserMemoryEntity {

    /** 主键，自增 */
    private Long id;

    /** 用户 ID，关联 users.id */
    private Long userId;

    /** 租户/群组 ID，用于多租户隔离 */
    private Long tenantId;

    /**
     * 记忆类型。
     * <ul>
     *   <li>PREFERENCE — 用户偏好（如"默认使用 Markdown"）</li>
     *   <li>FACT — 稳定事实（如"用户是 Java 后端工程师"）</li>
     *   <li>TASK — 任务记录（如"上次生成的报告已通过审批"）</li>
     *   <li>SESSION_CONTEXT — 跨会话的上下文信息</li>
     * </ul>
     */
    private String memoryType;

    /**
     * 主题 Key，用于同主题冲突检测。
     * 示例：report.format、response.language、default.editor。
     * 为空表示无明确主题，不参与冲突检测。
     */
    private String topic;

    /** 自然语言记忆内容 */
    private String content;

    /**
     * 记忆来源。
     * <ul>
     *   <li>USER_EXPLICIT — 用户明确请求保存（如"请记住…"）</li>
     *   <li>USER_STATEMENT — 用户普通陈述</li>
     *   <li>MODEL_INFERENCE — 模型根据对话推断</li>
     *   <li>TOOL_FACT — 工具/业务系统返回的事实</li>
     *   <li>ADMIN_CONFIG — 管理员配置的团队级记忆</li>
     * </ul>
     */
    private String source;

    /** 置信度 0.00-1.00 */
    private BigDecimal confidence;

    /** 重要性 0.00-1.00 */
    private BigDecimal importance;

    /**
     * 记忆生命周期状态。
     * 记忆状态机——CANDIDATE（候选）→ PENDING_CONFIRMATION（待确认）→ ACTIVE（生效）。
     * ACTIVE（生效）→ SUPERSEDED（被新记忆取代）/ EXPIRED（过期）/ REVOKED（撤销）/ DELETED（删除）。
     */
    private String status;

    /** 来源会话 ID，用于追溯 */
    private Long sourceSessionId;

    /** 过期时间，过期后状态标记为 EXPIRED */
    private LocalDateTime expiresAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /** 替代本记忆的新记忆 ID */
    private Long supersededBy;

    /** 扩展元数据（JSON 格式，灵活扩展） */
    private String metadataJson;
}