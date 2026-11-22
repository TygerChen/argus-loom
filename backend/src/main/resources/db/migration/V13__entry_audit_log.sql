-- V13: 入口审计流水（挂号台存根）
-- 设计：每条记录 = 一次进入业务通道的请求。被拒请求（401/403/400/429）同样留痕，
-- 补上「限流命中 / 越权尝试 / 参数攻击事后查不了」的审计盲区。
-- 隐私边界：不记录任何用户输入原文，只记输入长度与「身份声明疑似」标记位；
-- 身份真伪的语义比对属后续「理解任务」步骤，入口只保证系统身份权威。
-- 写入方 EntryAuditFilter（安全链内、JwtAuthenticationFilter 之后）：
-- 异步落库、fail-soft——审计失败只告警，绝不拖垮业务主流程。

CREATE TABLE entry_log (
    id             BIGSERIAL    PRIMARY KEY,
    trace_id       VARCHAR(64)  NOT NULL,
    task_id        BIGINT       NULL,
    channel        VARCHAR(16)  NOT NULL,
    endpoint       VARCHAR(160) NOT NULL,
    user_id        BIGINT       NULL,
    user_code      VARCHAR(64)  NULL,
    system_role    VARCHAR(16)  NULL,
    decision       VARCHAR(16)  NOT NULL,
    status_code    INT          NOT NULL,
    reject_code    VARCHAR(64)  NULL,
    identity_claim BOOLEAN      NOT NULL DEFAULT FALSE,
    input_chars    INT          NULL,
    received_at    TIMESTAMP    NOT NULL,
    latency_ms     BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_entry_log_created       ON entry_log (created_at DESC);
CREATE INDEX idx_entry_log_user_time     ON entry_log (user_id, created_at DESC);
CREATE INDEX idx_entry_log_channel_time  ON entry_log (channel, created_at DESC);

COMMENT ON TABLE entry_log IS '入口审计流水：谁、何时、哪个通道、被收下还是拒绝。task_id 为运行器任务编号预留位（入口阶段恒空）';
