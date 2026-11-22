-- V17: report_definition.pipeline_json JSONB → TEXT
-- 根因：实体字段是 String，MyBatis-Plus 以 varchar 传参，PG 拒绝 varchar→jsonb 隐式转换
-- （资金日报的 definition 行早已存在未触发 insert，任务日报首次 insert 踩爆——两实例共用修复）。
-- 内容都是合法 JSON 字符串，转 TEXT 后由应用层保证合法性（写入前 objectMapper 序列化）。

ALTER TABLE report_definition ALTER COLUMN pipeline_json TYPE TEXT;

COMMENT ON COLUMN report_definition.pipeline_json IS '流水线配置 JSON 文本（source/transform/render/schedule/deliver），应用层序列化';
