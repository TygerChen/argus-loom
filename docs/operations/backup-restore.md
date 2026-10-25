# 备份与恢复手册

## RPO / RTO 目标

| 数据 | 备份策略 | RPO | RTO |
| --- | --- | --- | --- |
| PostgreSQL（结构化数据 + pgvector） | 每日全量 pg_dump + WAL 归档 | ≤ 15 分钟 | ≤ 2 小时 |
| MinIO（原始上传文件） | mc mirror 每日增量到备份桶 | ≤ 24 小时 | ≤ 1 小时 |
| ES 索引 | 可重建（来源 = PostgreSQL + MinIO），可不做快照 | - | 重建 ≤ 4 小时 |

## 备份

```powershell
# 每日 02:00（任务计划程序）
pg_dump -Fc "$env:DB_URL" -f "D:\backup\argus_$(Get-Date -Format yyyyMMdd).dump"
mc mirror --overwrite minio/argus-rag-documents backup/argus-rag-documents
```

备份文件保留 30 天，异地（另一台机器/网盘）再存一份。

## 恢复顺序（演练过才可信，建议每季度一次）

1. 起新 PostgreSQL，pg_restore 导入；确认 flyway_schema_history 到 V8。
2. 恢复 MinIO 文件（mc mirror 反向）。校验文件数与 excel_document.file_path 一致。
3. 启动应用（prod profile）；ES 索引由入库链路重建或用运维脚本全量重刷。
4. 验证：登录 → 知识库检索带引用 → 上传一份测试 Excel → 财务总览数字与备份前一致。
5. 演练记录写进值班日志：日期、备份点、耗时、遇到的问题。

## 脱敏

给开发/演示环境用备份时，必须先脱敏：用户表手机号/邮箱置空、JWT 密钥换新、
财务 finance_ledger_record 金额可按比例缩放。
