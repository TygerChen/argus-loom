-- 任务 10：财务期间关闭 + 导入文件幂等。
-- 关闭的期间禁止该月流水的导入与编辑；重新打开必须填写原因并留审计。
-- 文件哈希用于拦截同一文件的重复导入（同账本内唯一）。

CREATE TABLE IF NOT EXISTS finance_period (
    id BIGSERIAL PRIMARY KEY,
    period_month VARCHAR(7) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    closed_by BIGINT NULL,
    closed_reason VARCHAR(500) NULL,
    closed_at TIMESTAMP NULL,
    reopened_by BIGINT NULL,
    reopen_reason VARCHAR(500) NULL,
    reopened_at TIMESTAMP NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_finance_period_month
    ON finance_period (period_month);

COMMENT ON TABLE finance_period IS
    '财务期间开关：CLOSED 状态下该月流水禁止导入与编辑，重开必须填写原因';

ALTER TABLE finance_import_batch ADD COLUMN IF NOT EXISTS file_hash VARCHAR(128) NULL;

CREATE INDEX IF NOT EXISTS idx_finance_import_batch_file_hash
    ON finance_import_batch (file_hash);

COMMENT ON COLUMN finance_import_batch.file_hash IS
    '导入文件 SHA-256；同账本重复文件导入会被拒绝';
