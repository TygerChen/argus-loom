-- V14: entry_log 风险标记扩展（第一步"验风险"下半场）
-- 设计：入口对请求打风险标签（risk_level / action_type / need_confirmation / safety_flagged），
-- 但入口不裁决——真正拦截在运行器/工具层。标签是给下游（模型/运行器/工具）看的共识信号。
-- V13 已在库上执行，遵守"不改已执行迁移"红线，增量列用 ALTER IF NOT EXISTS。
-- tenant_id：当前单租户，恒为 null，列先行预留（多租户演进时不改表）。

ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS session_id        BIGINT      NULL;
ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS tenant_id         VARCHAR(64) NULL;
ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS risk_level        VARCHAR(16) NULL;
ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS action_type       VARCHAR(24) NULL;
ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS need_confirmation BOOLEAN     NULL;
ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS safety_flagged    BOOLEAN     NULL;

CREATE INDEX IF NOT EXISTS idx_entry_log_risk ON entry_log (risk_level, created_at DESC);

COMMENT ON COLUMN entry_log.risk_level IS '风险级别 LOW/HIGH：敏感动作(删除/导出/外发/批量改)在入口即标 HIGH';
COMMENT ON COLUMN entry_log.action_type IS '动作类型 READ/DELETE/EXPORT/EXTERNAL_SEND/BATCH_MODIFY：由入口文本分类器标记，运行器据此决定是否需要确认门';
