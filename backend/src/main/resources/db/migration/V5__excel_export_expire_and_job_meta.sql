-- ================================================
-- Argus V5: 导出任务文件过期时间（任务 7 导出闭环）
-- 成功任务产物默认保留 24 小时，超时被清扫为 EXPIRED，
-- 下载被拒绝；失败任务支持授权用户重试（重试=新建任务）。
-- ================================================

ALTER TABLE excel_export_task
    ADD COLUMN IF NOT EXISTS expire_at TIMESTAMP;

COMMENT ON COLUMN excel_export_task.expire_at IS
    '导出产物过期时间；NULL=不限期（历史任务），超时后任务置为 EXPIRED 且不可下载';

CREATE INDEX IF NOT EXISTS idx_excel_export_task_expire
    ON excel_export_task (status, expire_at);
