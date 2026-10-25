-- ================================================
-- Argus V4: 文档级角色门槛（RAG 检索层数据隔离）
-- 单点授权棱锥：用户角色优先级 USER < FINANCE < ADMIN，
-- 文档 required_role 门槛 <= 用户角色 方可被该用户检索到；
-- 否则该文档对用户 “不存在” —— 与“知识库内无此信息”返回一致，
-- 杜绝通过 UNAUTHORIZED/NOT_FOUND 差异泄露受限文档的存在性。
-- ================================================

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS required_role VARCHAR(16) NOT NULL DEFAULT 'USER';

COMMENT ON COLUMN documents.required_role IS
    '文档级角色门槛：USER | FINANCE | ADMIN；缺省=USER(公开)；检索层按用户角色优先级过滤，未达门槛对该用户“不存在”';

-- 检索主查询将按 group_id + required_role + 可读状态过滤，建立联合索引
CREATE INDEX IF NOT EXISTS idx_documents_group_role_status
    ON documents (group_id, required_role, status, deleted);