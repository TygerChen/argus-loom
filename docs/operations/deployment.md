# 部署手册（500 人规模）

## 拓扑

```
                +------------+
  用户 --TLS--> |  Nginx x1  |（TLS 终止、静态资源、/ws 转发）
                +-----+------+
        +-------------+-------------+
        v             v             v
   API 实例 A     API 实例 B     Worker 实例
  （同一 jar，   （同一 jar，   （同一 jar，
   不跑 Worker）  不跑 Worker）  只跑 Worker）
        +------+------+------+------+
               v             v
         PostgreSQL 14+   Elasticsearch 8
        （pgvector 扩展）  （argus_rag_document_chunks）
               v
            MinIO（对象存储）
```

> 当前版本的任务队列与 Outbox 基于 PostgreSQL（SKIP LOCKED），多实例安全；
> RabbitMQ/Redis 为后续演进预留（见 capacity-runbook.md 的切换条件）。

## 环境变量（生产必须全部显式提供，缺一启动即失败）

| 变量 | 说明 |
| --- | --- |
| `DB_URL` | PostgreSQL JDBC 连接串（内网地址） |
| `POSTGRES_USERNAME` / `POSTGRES_PASSWORD` | 最小权限数据库账号（见 security-baseline.md） |
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `LLM_CHAT_MODEL` | 模型服务 |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 对象存储 |
| `ES_HOST` / `ES_PORT` / `ES_SCHEME` | 检索集群 |
| `ARGUS_RAG_JWT_SECRET` | ≥ 32 字符随机串，泄露即全员下线 |
| `ARGUS_WS_ALLOWED_ORIGINS` | WebSocket 允许的站点来源，禁止 `*` |
| `SERVER_PORT` | 默认 8080 |

## 启动步骤

1. 数据库：执行 Flyway 迁移（应用启动时自动跑，V1→V8 按序执行）。
2. API 节点：`java -jar backend.jar --spring.profiles.active=prod`（两台，无状态）。
3. Worker 节点：同 jar，加 `--argus.job.worker-enabled=true --spring.profiles.active=prod`。
4. Nginx：`/api` 轮询两台 API；`/ws` 用 `Upgrade` 头转发到同一组。
5. 健康检查：`GET /actuator/health`，只信 `UP`；`/actuator/prometheus` 供抓取。

## 回滚

- 应用回滚：保留数据，直接替换 jar 回上一版本（迁移向前兼容）。
- 迁移回滚：V5+ 均只增列/增表，可人工 DROP 回退；禁止在生产手改 V1-V4。

## 升级窗口

1. 提前公告并暂停财务导入窗口。
2. 摘掉一台 API、升级、观察健康检查、放回流量，再对另一台重复。
3. Worker 最后升级（任务消费幂等，短暂双版本共存安全）。
