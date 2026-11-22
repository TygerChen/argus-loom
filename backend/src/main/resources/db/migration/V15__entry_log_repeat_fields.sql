-- V15: entry_log 补"用户提交信息"与重复请求识别（第一步方案对齐）
-- 1) requested_groups：用户在请求体里选择的知识库群组（只是"希望搜索的范围"声明，
--    绝不代表拥有群组内全部文档权限——资源级过滤仍由检索层 DocumentRoleGate 强制执行）。
-- 2) dup_suspected：入口滑动窗口内识别到的重复请求（同用户+同通道+同输入哈希）。
--    只标记不拒绝——用户可能只是合法地重新提问，裁决交给运行器/业务层。

ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS dup_suspected    BOOLEAN      NULL;
ALTER TABLE entry_log ADD COLUMN IF NOT EXISTS requested_groups VARCHAR(128) NULL;

COMMENT ON COLUMN entry_log.dup_suspected IS '重复请求疑似：同用户+同通道+同输入在滑动窗口内再现（只标记，运行器/业务层裁决）';
COMMENT ON COLUMN entry_log.requested_groups IS '用户请求体声明的知识库群组 ID 列表（搜索范围声明，非权限授予）';
