-- ================================================
-- Argus Excel 模块表结构（Flyway V2，PostgreSQL）
-- 来源：ExcelSchemaInitializer SQL 清单，含版本/回收站/财务表
-- ================================================

CREATE TABLE IF NOT EXISTS excel_document (id BIGSERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL, sheet_count INT DEFAULT 0, sheet_names VARCHAR(2000), version BIGINT DEFAULT 1, status INT DEFAULT 1, file_path VARCHAR(500), file_size BIGINT DEFAULT 0, creator_id VARCHAR(64) DEFAULT 'system', create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE TABLE IF NOT EXISTS excel_sheet (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_index INT DEFAULT 0, sheet_name VARCHAR(255) NOT NULL, total_rows INT DEFAULT 0, total_cols INT DEFAULT 0, chunk_count INT DEFAULT 0, merge_config_json TEXT, column_len_json TEXT, row_len_json TEXT, config_json TEXT, hyperlink_config_json TEXT, images_config_json TEXT, condition_format_json TEXT, chart_json TEXT, active INT DEFAULT 0, status INT DEFAULT 1, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_excel_sheet_document_id ON excel_sheet(document_id)
;

ALTER TABLE excel_sheet ADD COLUMN IF NOT EXISTS version INT DEFAULT 1
;

CREATE TABLE IF NOT EXISTS excel_sheet_chunk (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_id BIGINT NOT NULL, chunk_index INT DEFAULT 0, row_start INT DEFAULT 0, row_end INT DEFAULT 0, celldata_json TEXT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_excel_chunk_sheet_id ON excel_sheet_chunk(sheet_id)
;

CREATE INDEX IF NOT EXISTS idx_excel_chunk_document_id ON excel_sheet_chunk(document_id)
;

CREATE INDEX IF NOT EXISTS idx_excel_chunk_sheet_chunk ON excel_sheet_chunk(sheet_id, chunk_index)
;

CREATE TABLE IF NOT EXISTS excel_operation_log (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_id BIGINT, row_index INT DEFAULT 0, col_index INT DEFAULT 0, operation_type VARCHAR(32) NOT NULL, old_value TEXT, new_value TEXT, user_id BIGINT, user_display_name VARCHAR(64), batch_id VARCHAR(64), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_oplog_doc_time ON excel_operation_log(document_id, create_time DESC)
;

CREATE INDEX IF NOT EXISTS idx_oplog_batch ON excel_operation_log(batch_id)
;

CREATE TABLE IF NOT EXISTS excel_document_snapshot (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_id BIGINT NOT NULL, snapshot_json TEXT NOT NULL, cell_count INT DEFAULT 0, version INT DEFAULT 1, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_snapshot_doc ON excel_document_snapshot(document_id, create_time DESC)
;

CREATE TABLE IF NOT EXISTS excel_document_permission (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, user_id BIGINT NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'VIEWER', can_share INT DEFAULT 0, granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_perm_doc_user ON excel_document_permission(document_id, user_id)
;

CREATE UNIQUE INDEX IF NOT EXISTS idx_perm_unique ON excel_document_permission(document_id, user_id)
;

CREATE TABLE IF NOT EXISTS excel_document_share (id BIGSERIAL PRIMARY KEY, token VARCHAR(32) NOT NULL UNIQUE, document_id BIGINT NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'VIEWER', created_by BIGINT, expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_share_token ON excel_document_share(token)
;

CREATE INDEX IF NOT EXISTS idx_share_doc ON excel_document_share(document_id)
;

ALTER TABLE excel_document ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(64)
;

ALTER TABLE excel_document ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP
;

CREATE INDEX IF NOT EXISTS idx_excel_document_status_deleted ON excel_document(status, deleted_at)
;

CREATE TABLE IF NOT EXISTS excel_document_version (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, doc_version BIGINT NOT NULL DEFAULT 1, snapshot_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL', creator_id BIGINT, creator_name VARCHAR(64), summary VARCHAR(500), sheet_count INT DEFAULT 0, cell_count BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_version_doc_time ON excel_document_version(document_id, create_time DESC)
;

CREATE TABLE IF NOT EXISTS excel_document_version_sheet (id BIGSERIAL PRIMARY KEY, version_id BIGINT NOT NULL, sheet_index INT DEFAULT 0, sheet_name VARCHAR(255) NOT NULL, total_rows INT DEFAULT 0, total_cols INT DEFAULT 0, chunk_count INT DEFAULT 0, merge_config_json TEXT, column_len_json TEXT, row_len_json TEXT, config_json TEXT, hyperlink_config_json TEXT, images_config_json TEXT, condition_format_json TEXT, chart_json TEXT, active INT DEFAULT 0)
;

CREATE INDEX IF NOT EXISTS idx_version_sheet_version ON excel_document_version_sheet(version_id)
;

CREATE TABLE IF NOT EXISTS excel_document_version_chunk (id BIGSERIAL PRIMARY KEY, version_id BIGINT NOT NULL, version_sheet_id BIGINT NOT NULL, chunk_index INT DEFAULT 0, row_start INT DEFAULT 0, row_end INT DEFAULT 0, celldata_json TEXT)
;

CREATE INDEX IF NOT EXISTS idx_version_chunk_version ON excel_document_version_chunk(version_id)
;

CREATE INDEX IF NOT EXISTS idx_version_chunk_sheet ON excel_document_version_chunk(version_sheet_id)
;

CREATE TABLE IF NOT EXISTS excel_cell_history (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_id BIGINT, row_no INT DEFAULT -1, col_no INT DEFAULT -1, action VARCHAR(16) NOT NULL DEFAULT 'CHANGE', before_value_json TEXT, after_value_json TEXT, base_rev BIGINT DEFAULT 0, after_rev BIGINT DEFAULT 0, batch_id VARCHAR(64) NOT NULL, operator_id BIGINT, operator_name VARCHAR(64), source VARCHAR(16) DEFAULT 'HTTP', merged INT DEFAULT 0, conflict INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_history_doc_time ON excel_cell_history(document_id, create_time DESC)
;

CREATE TABLE IF NOT EXISTS excel_edit_stack (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, batch_id VARCHAR(64) NOT NULL, parent_batch_id VARCHAR(64), action VARCHAR(16) NOT NULL, applied INT DEFAULT 1, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_edit_stack_doc ON excel_edit_stack(document_id, applied, id DESC)
;

CREATE TABLE IF NOT EXISTS excel_export_task (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, task_no VARCHAR(64) NOT NULL, user_id BIGINT, username VARCHAR(64), status INT DEFAULT 0, progress INT DEFAULT 0, file_name VARCHAR(255), object_key VARCHAR(500), download_url VARCHAR(500), error_message VARCHAR(2000), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, finish_time TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_export_task_status ON excel_export_task(status)
;

CREATE INDEX IF NOT EXISTS idx_export_task_user ON excel_export_task(username)
;

CREATE TABLE IF NOT EXISTS excel_audit_log (id BIGSERIAL PRIMARY KEY, user_id BIGINT, username VARCHAR(64), action VARCHAR(64) NOT NULL, target_type VARCHAR(32), target_id VARCHAR(64), detail VARCHAR(2000), ip VARCHAR(64), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_audit_time ON excel_audit_log(create_time DESC)
;

CREATE INDEX IF NOT EXISTS idx_audit_username ON excel_audit_log(username)
;

CREATE TABLE IF NOT EXISTS excel_collab_log (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_id BIGINT, row_no INT DEFAULT 0, col_no INT DEFAULT 0, actor_id BIGINT, actor_name VARCHAR(64), request_id VARCHAR(64), base_rev BIGINT DEFAULT 0, server_rev BIGINT DEFAULT 0, value_text TEXT, result_value_text TEXT, merged INT DEFAULT 0, conflict INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_collab_log_doc_time ON excel_collab_log(document_id, create_time DESC)
;

CREATE TABLE IF NOT EXISTS excel_collab_room (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, actor_id BIGINT, actor_name VARCHAR(64), session_id VARCHAR(128), node_id VARCHAR(64), can_edit INT DEFAULT 1, sheet_id BIGINT, cursor_row INT DEFAULT 0, cursor_col INT DEFAULT 0, presence_json TEXT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_collab_room_doc ON excel_collab_room(document_id)
;

CREATE UNIQUE INDEX IF NOT EXISTS idx_collab_room_session ON excel_collab_room(session_id)
;

CREATE TABLE IF NOT EXISTS excel_system_config (id BIGSERIAL PRIMARY KEY, config_key VARCHAR(100) NOT NULL UNIQUE, config_value VARCHAR(500), updated_by VARCHAR(64), updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE TABLE IF NOT EXISTS excel_sheet_chunk_delta (id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL, sheet_id BIGINT NOT NULL, chunk_index INT NOT NULL, cell_row INT NOT NULL, cell_col INT NOT NULL, cell_value TEXT, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE UNIQUE INDEX IF NOT EXISTS uk_excel_sheet_chunk_delta_key ON excel_sheet_chunk_delta(sheet_id, chunk_index, cell_row, cell_col)
;

CREATE INDEX IF NOT EXISTS idx_excel_sheet_chunk_delta_document_id ON excel_sheet_chunk_delta(document_id)
;

CREATE INDEX IF NOT EXISTS idx_excel_sheet_chunk_delta_sheet ON excel_sheet_chunk_delta(sheet_id)
;

CREATE TABLE IF NOT EXISTS finance_import_batch (id BIGSERIAL PRIMARY KEY, document_id BIGINT, book_key VARCHAR(64), batch_version INT NOT NULL DEFAULT 1, is_current INT NOT NULL DEFAULT 1, region_name VARCHAR(64), file_name VARCHAR(255), file_path VARCHAR(500), file_size BIGINT NOT NULL DEFAULT 0, total_sheets INT NOT NULL DEFAULT 0, imported_sheets INT NOT NULL DEFAULT 0, total_rows INT NOT NULL DEFAULT 0, mapped_rows INT NOT NULL DEFAULT 0, skipped_rows INT NOT NULL DEFAULT 0, status INT NOT NULL DEFAULT 1, error_summary VARCHAR(2000), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_finance_batch_doc ON finance_import_batch(document_id)
;

CREATE UNIQUE INDEX IF NOT EXISTS uk_finance_batch_book_version ON finance_import_batch(book_key, batch_version)
;

CREATE TABLE IF NOT EXISTS finance_ledger_record (id BIGSERIAL PRIMARY KEY, batch_id BIGINT NOT NULL, document_id BIGINT, sheet_index INT NOT NULL DEFAULT 0, sheet_name VARCHAR(255), excel_row INT NOT NULL DEFAULT 0, record_type VARCHAR(20), business_date DATE, day_label VARCHAR(2000), summary VARCHAR(2000), counterparty VARCHAR(2000), department VARCHAR(2000), category VARCHAR(2000), expense_name VARCHAR(2000), debit DECIMAL(30,6), credit DECIMAL(30,6), balance DECIMAL(30,6), fixed_purchase DECIMAL(30,6), fixed_redemption DECIMAL(30,6), fixed_balance DECIMAL(30,6), prev_balance DECIMAL(30,6), income DECIMAL(30,6), expense DECIMAL(30,6), currency_code VARCHAR(16), raw_json TEXT, formula_text VARCHAR(2000), row_status INT NOT NULL DEFAULT 1, warnings VARCHAR(2000), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
;

CREATE INDEX IF NOT EXISTS idx_finance_ledger_batch ON finance_ledger_record(batch_id)
;

CREATE INDEX IF NOT EXISTS idx_finance_ledger_document ON finance_ledger_record(document_id)
;

CREATE INDEX IF NOT EXISTS idx_finance_ledger_date ON finance_ledger_record(business_date)
;

CREATE INDEX IF NOT EXISTS idx_finance_ledger_sheet ON finance_ledger_record(document_id, sheet_name)
;

