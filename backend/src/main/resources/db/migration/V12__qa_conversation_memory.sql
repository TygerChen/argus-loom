-- V12: 知识库问答的多轮会话记忆
-- 设计：三层记忆（滚动摘要 / 近期原文窗口 / 本轮检索证据）支撑多轮追问。
-- qa_conversations.summary 为滚动摘要（结构化三段式：话题域/已确认/未决），
-- 由回答完成后异步合并更新（懒触发：窗口内短会话不摘要）；
-- 近期原文不落此表，直接取 qa_records 最近 N 轮（conversation_id 回填）。
-- 任何记忆组件失败均 fail-soft：问答自动退化为单轮模式。

CREATE TABLE qa_conversations (
    id                     BIGSERIAL PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    group_id               BIGINT       NOT NULL,
    title                  VARCHAR(128) NOT NULL DEFAULT '',
    summary                TEXT         NULL,
    summary_version        INT          NOT NULL DEFAULT 0,
    summary_upto_record_id BIGINT       NULL,
    last_question_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_qa_conv_user_group ON qa_conversations (user_id, group_id, last_question_at DESC);

ALTER TABLE qa_records ADD COLUMN conversation_id BIGINT NULL REFERENCES qa_conversations (id) ON DELETE SET NULL;
CREATE INDEX idx_qa_records_conversation ON qa_records (conversation_id, id);

COMMENT ON TABLE qa_conversations IS '问答会话：滚动摘要承载多轮记忆；last_question_at 用于会话延续判定（如 5 分钟内同群组自动延续）';
