# 开发、启动与验证指南

## 1. 环境要求

| 组件 | 要求 |
| --- | --- |
| JDK | 21，推荐 Corretto 21 |
| Maven | 项目有 Wrapper；离线环境可使用本机 Maven |
| Node.js | `^20.19.0` 或 `>=22.12.0` |
| PostgreSQL | 14+，必须安装 pgvector |
| Elasticsearch | 8.x |
| MinIO | 单机版即可 |
| Python | 仅部分脚本和本地辅助服务需要 |

## 2. 推荐启动方式

Windows 一键启动：

```powershell
cd F:\ChatGPT\argus-loom
.\scripts\start-all.ps1
```

启动顺序通常为：Elasticsearch -> MinIO -> 本地辅助服务 -> 后端 -> 前端。

默认端口：

| 服务 | 端口 |
| --- | --- |
| 后端 | 10001 |
| 前端 | 8081 |
| PostgreSQL | 5432 |
| Elasticsearch | 9200 |
| MinIO API / Console | 9000 / 9001 |

## 3. 配置

复制 `.env.example` 为 `.env`，填写真实值。禁止把 `.env` 提交或发给其他 AI。

关键变量：

- `POSTGRES_PASSWORD`
- `ARGUS_RAG_JWT_SECRET`
- `OPENAI_API_KEY` / `OPENAI_BASE_URL`
- `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`
- `ES_HOST` / `ES_PORT`

`application.yml` 会尝试导入根目录或 `backend/` 上层的 `.env`，因此 IDEA 直接运行
后端也能读到配置。不同 profile 的优先级和默认值以实际 YAML 为准。

安全提醒：当前 `application-dev.yml` 中存在仓库级默认密码值。不要把该文件原样
交给第三方 AI 或放入公开仓库；交接包生成脚本会对副本脱敏。

## 4. IDEA 启动后端

1. Project SDK 和 Language Level 都选择 JDK 21。
2. Maven 使用项目自带 Wrapper，或配置本机 Maven。
3. 运行 `com.argus.rag.ArgusRagApplication`。
4. Active profile 默认 `local`。
5. 先确认 PostgreSQL 服务已启动。

常见错误：

- `TypeTag :: UNKNOWN`：通常是 JDK 版本或 Lombok/编译器不匹配，改用 JDK 21。
- `password authentication failed`：检查 `.env` 中 `POSTGRES_PASSWORD`。
- Flyway 连不上数据库：先检查 `localhost:5432` 和 `postgresql-x64-16` 服务。
- PostgreSQL 无法启动：检查数据目录和 `pg_notify` 等系统目录是否完整。

## 5. 后端验证

```powershell
cd F:\ChatGPT\argus-loom\backend
$env:JAVA_HOME='F:\DevelopmentTools\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;G:\IDEA\Maven\apache-maven-3.9.16\bin;$env:Path"
mvn -o test
```

当前实测基线：

```text
Tests run: 1634, Failures: 0, Errors: 0, Skipped: 0
```

只跑相关测试时，优先使用类名筛选，例如：

```powershell
mvn -o -Dtest=DocumentUploadServiceTest test
```

## 6. 前端验证

```powershell
cd F:\ChatGPT\argus-loom\frontend
npm test
npm run build
```

当前实测：

- Vitest：3 个测试文件，14 个测试通过。
- Vite build：成功。
- 告警：Univer vendor chunk 过大。

本地开发：

```powershell
npm run dev
```

## 7. 修改代码的标准流程

1. 用 `rg` 找控制器、服务、Mapper、SQL、测试和生产调用方。
2. 明确正常路径、失败路径、事务边界和权限边界。
3. 找到最小可复现测试；没有测试先补失败测试。
4. 实现最小改动，不做无关重构。
5. 运行聚焦测试。
6. 运行后端全量测试；前端改动再跑测试和构建。
7. 更新 `ai-handoff/PROJECT_TRUTH.md` 中已经过时的事实。

## 8. 数据库变更

- 不修改 `V1`-`V8` 已执行迁移。
- 新迁移从 `V9__...sql` 开始。
- 迁移必须可向前兼容滚动升级。
- 大表变更优先采用“加列 -> 双写/回填 -> 切读 -> 后删”的两阶段。

## 9. 异步与资源边界

- Web 请求线程不得执行解析、大文件、长模型调用之外的无限任务。
- 文档 ETL 使用 `background_job`。
- Excel 导出使用 `excelExportExecutor`。
- 检索并行使用 `retrievalExecutor`。
- `@Async` / `@Scheduled` 使用项目已有线程池。
- 禁止 `parallelStream()` 和公共 ForkJoinPool 承担业务重活。
- 队列必须考虑拒绝策略、超时、租约、重试上限和死信。

## 10. 权限变更检查

每次改动至少检查：

- 用户能否通过猜 ID 访问他人文档、工作表、版本、导出任务。
- WebSocket 订阅是否重新校验文档权限。
- 历史问答引用在权限撤销后是否仍泄露正文。
- 管理员是否错误绕过财务角色门槛。
- 前端隐藏按钮是否被误当成后端授权。

## 11. 数据与隐私

不要提交或打包：

- `.env`
- `backend/upload/`
- `logs/`
- `backend/target/`
- `frontend/node_modules/`
- `frontend/dist/`
- 真实财务文件、备份和数据库 dump

## 12. 完成定义

一个任务只有在以下条件满足时才算完成：

- 代码真实接入生产调用链。
- 失败路径有明确行为和用户可理解的反馈。
- 权限与幂等没有被弱化。
- 自动化测试覆盖关键分支。
- 全量验证通过，或明确记录未通过项。
- 教程/交接事实已同步。
