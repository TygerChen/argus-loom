-- ==============================
-- Excel 模块建表脚本（PostgreSQL）
-- DataLoom 融合到 Argus 基座
-- ==============================

-- 1. 文档主表（元数据）
CREATE TABLE IF NOT EXISTS excel_document (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255)   NOT NULL,
    sheet_count     INT            DEFAULT 0,
    sheet_names     VARCHAR(2000),
    version         BIGINT         DEFAULT 1,
    status          INT            DEFAULT 1,
    file_path       VARCHAR(500),
    file_size       BIGINT         DEFAULT 0,
    creator_id      VARCHAR(64)    DEFAULT 'system',
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);

-- 2. Sheet 元信息表
CREATE TABLE IF NOT EXISTS excel_sheet (
    id                    BIGSERIAL PRIMARY KEY,
    document_id           BIGINT         NOT NULL,
    sheet_index           INT            DEFAULT 0,
    sheet_name            VARCHAR(255)   NOT NULL,
    total_rows            INT            DEFAULT 0,
    total_cols            INT            DEFAULT 0,
    chunk_count           INT            DEFAULT 0,
    merge_config_json     TEXT,
    column_len_json       TEXT,
    row_len_json          TEXT,
    config_json           TEXT,
    hyperlink_config_json TEXT,
    images_config_json    TEXT,
    condition_format_json TEXT,
    chart_json            TEXT,
    active                INT            DEFAULT 0,
    status                INT            DEFAULT 1,
    create_time           TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_excel_sheet_document_id ON excel_sheet(document_id);

-- 3. Sheet 数据分块表
CREATE TABLE IF NOT EXISTS excel_sheet_chunk (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    sheet_id        BIGINT         NOT NULL,
    chunk_index     INT            DEFAULT 0,
    row_start       INT            DEFAULT 0,
    row_end         INT            DEFAULT 0,
    celldata_json   TEXT,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_excel_chunk_sheet_id ON excel_sheet_chunk(sheet_id);
CREATE INDEX IF NOT EXISTS idx_excel_chunk_document_id ON excel_sheet_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_excel_chunk_sheet_chunk ON excel_sheet_chunk(sheet_id, chunk_index);

-- 4. 操作日志表（审计+撤销重做数据源）
CREATE TABLE IF NOT EXISTS excel_operation_log (
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT         NOT NULL,
    sheet_id          BIGINT,
    row_index         INT            DEFAULT 0,
    col_index         INT            DEFAULT 0,
    operation_type    VARCHAR(32)    NOT NULL,
    old_value         TEXT,
    new_value         TEXT,
    user_id           BIGINT,
    user_display_name VARCHAR(64),
    batch_id          VARCHAR(64),
    create_time       TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oplog_doc_time ON excel_operation_log(document_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_oplog_batch ON excel_operation_log(batch_id);

-- 5. 文档快照表（定时自动保存）
CREATE TABLE IF NOT EXISTS excel_document_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    sheet_id        BIGINT         NOT NULL,
    snapshot_json   TEXT           NOT NULL,
    cell_count      INT            DEFAULT 0,
    version         INT            DEFAULT 1,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_snapshot_doc ON excel_document_snapshot(document_id, create_time DESC);

-- 6. 文档权限表（OWNER/EDITOR/VIEWER）
CREATE TABLE IF NOT EXISTS excel_document_permission (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    user_id         BIGINT         NOT NULL,
    role            VARCHAR(16)    NOT NULL DEFAULT 'VIEWER',
    can_share       INT            DEFAULT 0,
    granted_at      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_perm_doc_user ON excel_document_permission(document_id, user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_perm_unique ON excel_document_permission(document_id, user_id);

-- 7. 分享链接表（持久化 + 过期）
CREATE TABLE IF NOT EXISTS excel_document_share (
    id              BIGSERIAL PRIMARY KEY,
    token           VARCHAR(32)    NOT NULL UNIQUE,
    document_id     BIGINT         NOT NULL,
    role            VARCHAR(16)    NOT NULL DEFAULT 'VIEWER',
    created_by      BIGINT,
    expires_at      TIMESTAMP      NOT NULL,
    created_at      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_share_token ON excel_document_share(token);
CREATE INDEX IF NOT EXISTS idx_share_doc ON excel_document_share(document_id);

-- ==============================
-- Excel 模块补充表（DataLoom 完整功能移植）
-- ==============================

-- 回收站字段（软删除保留数据，恢复后原样还原）
ALTER TABLE excel_document ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(64);
ALTER TABLE excel_document ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_excel_document_status_deleted ON excel_document(status, deleted_at);

-- 8. 文档版本快照主表（MANUAL / STRUCTURE / CELL / ROLLBACK_POINT）
CREATE TABLE IF NOT EXISTS excel_document_version (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    doc_version     BIGINT         NOT NULL DEFAULT 1,
    snapshot_type   VARCHAR(32)    NOT NULL DEFAULT 'MANUAL',
    creator_id      BIGINT,
    creator_name    VARCHAR(64),
    summary         VARCHAR(500),
    sheet_count     INT            DEFAULT 0,
    cell_count      BIGINT         DEFAULT 0,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_version_doc_time ON excel_document_version(document_id, create_time DESC);

-- 9. 版本 Sheet 元信息快照
CREATE TABLE IF NOT EXISTS excel_document_version_sheet (
    id                    BIGSERIAL PRIMARY KEY,
    version_id            BIGINT         NOT NULL,
    sheet_index           INT            DEFAULT 0,
    sheet_name            VARCHAR(255)   NOT NULL,
    total_rows            INT            DEFAULT 0,
    total_cols            INT            DEFAULT 0,
    chunk_count           INT            DEFAULT 0,
    merge_config_json     TEXT,
    column_len_json       TEXT,
    row_len_json          TEXT,
    config_json           TEXT,
    hyperlink_config_json TEXT,
    images_config_json    TEXT,
    condition_format_json TEXT,
    chart_json            TEXT,
    active                INT            DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_version_sheet_version ON excel_document_version_sheet(version_id);

-- 10. 版本分块快照
CREATE TABLE IF NOT EXISTS excel_document_version_chunk (
    id              BIGSERIAL PRIMARY KEY,
    version_id      BIGINT         NOT NULL,
    version_sheet_id BIGINT        NOT NULL,
    chunk_index     INT            DEFAULT 0,
    row_start       INT            DEFAULT 0,
    row_end         INT            DEFAULT 0,
    celldata_json   TEXT
);
CREATE INDEX IF NOT EXISTS idx_version_chunk_version ON excel_document_version_chunk(version_id);
CREATE INDEX IF NOT EXISTS idx_version_chunk_sheet ON excel_document_version_chunk(version_sheet_id);

-- 11. 单元格级操作历史（撤销/重做账本 + 审计明细）
CREATE TABLE IF NOT EXISTS excel_cell_history (
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT         NOT NULL,
    sheet_id          BIGINT,
    row_no            INT            DEFAULT -1,
    col_no            INT            DEFAULT -1,
    action            VARCHAR(16)    NOT NULL DEFAULT 'CHANGE',
    before_value_json TEXT,
    after_value_json  TEXT,
    base_rev          BIGINT         DEFAULT 0,
    after_rev         BIGINT         DEFAULT 0,
    batch_id          VARCHAR(64)    NOT NULL,
    operator_id       BIGINT,
    operator_name     VARCHAR(64),
    source            VARCHAR(16)    DEFAULT 'HTTP',
    merged            INT            DEFAULT 0,
    conflict          INT            DEFAULT 0,
    create_time       TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_history_doc_batch ON excel_cell_history(document_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_history_doc_time ON excel_cell_history(document_id, create_time DESC);

-- 12. 文档级撤销/重做栈
CREATE TABLE IF NOT EXISTS excel_edit_stack (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    batch_id        VARCHAR(64)    NOT NULL,
    parent_batch_id VARCHAR(64),
    action          VARCHAR(16)    NOT NULL,
    applied         INT            DEFAULT 1,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_edit_stack_doc ON excel_edit_stack(document_id, applied, id DESC);

-- 13. 异步导出任务
CREATE TABLE IF NOT EXISTS excel_export_task (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    task_no         VARCHAR(64)    NOT NULL,
    user_id         BIGINT,
    username        VARCHAR(64),
    status          INT            DEFAULT 0,
    progress        INT            DEFAULT 0,
    file_name       VARCHAR(255),
    object_key      VARCHAR(500),
    download_url    VARCHAR(500),
    error_message   VARCHAR(2000),
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    finish_time     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_export_task_status ON excel_export_task(status);
CREATE INDEX IF NOT EXISTS idx_export_task_user ON excel_export_task(username);

-- 14. 运营审计日志
CREATE TABLE IF NOT EXISTS excel_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT,
    username        VARCHAR(64),
    action          VARCHAR(64)    NOT NULL,
    target_type     VARCHAR(32),
    target_id       VARCHAR(64),
    detail          VARCHAR(2000),
    ip              VARCHAR(64),
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON excel_audit_log(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_username ON excel_audit_log(username);

-- 15. 协同操作日志（断线回放 + 运营审计）
CREATE TABLE IF NOT EXISTS excel_collab_log (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    sheet_id        BIGINT,
    row_no          INT            DEFAULT 0,
    col_no          INT            DEFAULT 0,
    actor_id        BIGINT,
    actor_name      VARCHAR(64),
    request_id      VARCHAR(64),
    base_rev        BIGINT         DEFAULT 0,
    server_rev      BIGINT         DEFAULT 0,
    value_text      TEXT,
    result_value_text TEXT,
    merged          INT            DEFAULT 0,
    conflict        INT            DEFAULT 0,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_collab_log_doc_time ON excel_collab_log(document_id, create_time DESC);

-- 16. 协同在线会话
CREATE TABLE IF NOT EXISTS excel_collab_room (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    actor_id        BIGINT,
    actor_name      VARCHAR(64),
    session_id      VARCHAR(128),
    node_id         VARCHAR(64),
    can_edit        INT            DEFAULT 1,
    sheet_id        BIGINT,
    cursor_row      INT            DEFAULT 0,
    cursor_col      INT            DEFAULT 0,
    presence_json   TEXT,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_collab_room_doc ON excel_collab_room(document_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_collab_room_session ON excel_collab_room(session_id);

-- 17. Excel 系统策略配置（版本留存、自动快照间隔等）
CREATE TABLE IF NOT EXISTS excel_system_config (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500),
    updated_by   VARCHAR(64),
    updated_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
