package com.argus.rag.qa.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * QA 多轮会话实体，对应 qa_conversations 表（V12 迁移）。
 * <p>
 * 一行代表一次多轮问答会话：滚动摘要（summary）承载长程记忆，
 * summary_upto_record_id 标记摘要已覆盖到的 qa_records 位置（懒触发增量合并的游标），
 * last_question_at 用于会话延续判定（同用户同群组、时间窗内自动延续）。
 * </p>
 * <p>
 * 记忆层整体 fail-soft：任何摘要/会话组件失败，问答自动退化为单轮模式。
 * </p>
 */
@Data
@TableName("qa_conversations")
public class QaConversationEntity {

    /** 会话主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 会话归属用户 ID。 */
    private Long userId;
    /** 知识库群组 ID（会话隔离边界）。 */
    private Long groupId;
    /** 会话标题，取首个问题截断生成。 */
    private String title;
    /** 滚动摘要（结构化三段式：话题域/已确认/未决），回答完成后异步合并更新。 */
    private String summary;
    /** 摘要乐观锁版本，防止并发合并互相覆盖。 */
    private Integer summaryVersion;
    /** 摘要已覆盖到的 qa_records 位置；其后的记录等待下次合并。 */
    private Long summaryUptoRecordId;
    /** 最近一次提问时间，用于会话延续时间窗判定。 */
    private LocalDateTime lastQuestionAt;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
