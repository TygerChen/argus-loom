-- 任务 9：可靠后台任务队列表 + 协同事件 Outbox（PostgreSQL）。
-- background_job 由 SKIP LOCKED 认领（多实例安全），idempotency_key 防重复入队；
-- excel_collab_outbox 承载"先持久化、后广播"的协同事件，广播失败可清扫重发。

CREATE TABLE IF NOT EXISTS background_job (
    id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NULL,
    resource_id BIGINT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    priority INT NOT NULL DEFAULT 0,
    payload_json TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP NULL,
    next_run_at TIMESTAMP NOT NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_background_job_idempotency
    ON background_job (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_background_job_claim
    ON background_job (status, next_run_at);

COMMENT ON TABLE background_job IS
    '可靠后台任务队列：SKIP LOCKED 认领、租约保护、1/5/15 分钟退避、DEAD_LETTER 死信';

CREATE TABLE IF NOT EXISTS excel_collab_outbox (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_collab_outbox_claim
    ON excel_collab_outbox (status, id);

CREATE INDEX IF NOT EXISTS idx_collab_outbox_document
    ON excel_collab_outbox (document_id);

COMMENT ON TABLE excel_collab_outbox IS
    '协同事件 Outbox：事务内落库 PENDING，提交后由发布器广播并标记 SENT，失败可重发';
