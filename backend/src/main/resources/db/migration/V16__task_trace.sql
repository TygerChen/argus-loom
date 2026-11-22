-- V16: 任务轨迹（第十步"收尾与留痕"）
-- 一行 = 一次完成的助手对话任务：把内存态循环（轮次/决策/越界/预算）与
-- 交付结果（验证结论/形态）落成可追溯记录，补齐"完整链路"留痕的最后一环。
-- 隐私边界：不存问题/答案原文（已在 assistant_messages），只存长度与计数；
-- 决策留痕存"可审计的 reasoning 素材"（工具名+参数摘要），不存模型黑盒思维。

CREATE TABLE task_trace (
    id                    BIGSERIAL    PRIMARY KEY,
    trace_id              VARCHAR(64)  NOT NULL,
    session_id            BIGINT       NULL,
    user_id               BIGINT       NULL,
    system_role           VARCHAR(16)  NULL,
    channel               VARCHAR(16)  NOT NULL DEFAULT 'ASSISTANT',
    question_chars        INT          NULL,
    rounds_used           INT          NOT NULL DEFAULT 0,
    max_rounds            INT          NOT NULL DEFAULT 0,
    decision_count        INT          NOT NULL DEFAULT 0,
    out_of_plan_count     INT          NOT NULL DEFAULT 0,
    decisions_json        TEXT         NULL,
    verification_supported BOOLEAN     NULL,
    verification_score    INT          NULL,
    unsupported_claims    TEXT         NULL,
    result_shape          VARCHAR(24)  NULL,
    reply_chars           INT          NULL,
    latency_ms            BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_task_trace_trace_id ON task_trace (trace_id);
CREATE INDEX idx_task_trace_user_time  ON task_trace (user_id, created_at DESC);
CREATE INDEX idx_task_trace_shape_time ON task_trace (result_shape, created_at DESC);

COMMENT ON TABLE task_trace IS '任务轨迹：每次助手对话的循环账本（轮次/决策/越界/验证/形态），trace_id 关联 entry_log 与全链路日志';
