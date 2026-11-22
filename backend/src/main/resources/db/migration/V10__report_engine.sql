-- 通用日报引擎：报表定义（一条"数据源→转换→渲染→推送"流水线的配置）
-- 与执行记录（每次生成的产物快照与推送结果）。
-- 资金日报是第一个实例（docs/通用日报引擎设计.md）。

CREATE TABLE IF NOT EXISTS report_definition (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    report_type VARCHAR(32) NOT NULL DEFAULT 'FUND_DAILY',
    pipeline_json JSONB NOT NULL DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_definition_owner_name
    ON report_definition (owner_user_id, name);

COMMENT ON TABLE report_definition IS
    '报表定义：每用户一张日报流水线（数据源/规则/模板/推送配置），JSONB 描述';

CREATE TABLE IF NOT EXISTS report_execution (
    id BIGSERIAL PRIMARY KEY,
    definition_id BIGINT NOT NULL,
    biz_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    result_json TEXT NULL,
    ai_brief_text TEXT NULL,
    html_content TEXT NULL,
    push_status VARCHAR(16) NULL,
    push_detail VARCHAR(512) NULL,
    error_message VARCHAR(1024) NULL,
    duration_ms BIGINT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_execution_def_date
    ON report_execution (definition_id, biz_date);

CREATE INDEX IF NOT EXISTS idx_report_execution_definition
    ON report_execution (definition_id);

COMMENT ON TABLE report_execution IS
    '报表执行记录：一定义一基准日一行（重跑覆盖），result_json 为看板数据源';
