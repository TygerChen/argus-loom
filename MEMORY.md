# Argus-Loom 项目记忆文档

> 面向后续 Codex/Claude 会话的速查入口。撰写目的：不看全部源码也能理解模块边界、启动方式、关键契约、权限红线和常见陷阱。
> 项目根目录：`F:\ChatGPT\argus-loom`（该目录本身不是 git 仓库）。

## 1. 项目定位

Argus-Loom 是一个“数据洞察中心”型 Web 应用：把团队知识文档与在线 Excel 数据统一接入 AI 能力。

核心产品能力：
1. **知识库文档**：上传 PDF/Word/Markdown/txt，自动做 ETL、切片、向量化，支持知识库问答与 AI 助手引用。
2. **AI 助手**：基于 Spring AI Alibaba Agent（React 模式）的多工具智能助手，内置知识库检索、财务结构化查询、当前时间、网络搜索，并带短期上下文摘要和长期记忆。
3. **在线 Excel 协作**：基于 UniverJS 的前端在线表格 + WebSocket/STOMP 实时协同 + 后端 delta 持久化 + 快照/版本历史 + xlsx 导入导出。
4. **财务业务工作台**：薪酬、考勤、财务台账导入与查询、财务报表与 PDF 输出，受 `ROLE_FINANCE` 保护。
5. **协作小组**：知识库按群组隔离，群主邀请/申请加入，可对知识库设访问角色门槛。

## 2. 技术栈

### 后端 `backend/`
- Java 21（本地 JDK：`C:\Users\yuanchentai\.jdks\corretto-21.0.11`），Spring Boot 3.5.0，Maven Wrapper。
- Spring Security + JWT（jjwt 0.12.6）；Access Token 短期 + Refresh Token httpOnly Cookie。
- MyBatis-Plus 3.5.15（分页插件 + jsqlparser），Flyway 管理 PostgreSQL 迁移。
- Spring AI / Spring AI Alibaba 1.1.2：DashScope 兼容 OpenAI Chat、本地 BGE 小模型 embedding（512 维 PGVector）。
- PostgreSQL + pgvector（HNSW、cosine）、Elasticsearch 8（关键词检索）、MinIO（对象存储）、Redis（chunk 缓存）。
- WebSocket + STOMP 实时协同；Apache POI（SAX 流式解析大文件）、PDFBox、Knife4j/OpenAPI 文档。

### 前端 `frontend/`
- Vue 3 + TypeScript + Vite 8 + Pinia + Vue Router 5 + Element Plus。
- UniverJS `@univerjs/presets` 0.25.x（在线表格），`@univerjs/preset-sheets-*` 扩展全家桶。
- STOMP/SockJS 实时协同（websocket + SSE）；`marked` 渲染 Markdown，`exceljs` + `file-saver` 导出；`react/react-dom` 是 Univer 依赖。
- 单元测试 Vitest（`npm test`），lint：oxlint + eslint + prettier。

### 基础设施
- PostgreSQL 库名：`argus_rag`；MinIO bucket：`argus-rag-documents`；ES 索引：`argus_rag_document_chunks`。
- 本地 profile：后端 `10001`，前端 `8081`，本地 embedding 服务 `11435`，ES `9200`，MinIO API `9000` / 控制台 `9001`。

## 3. 目录结构

```text
argus-loom/
├── backend/                  Spring Boot 后端
│   ├── src/main/java/com/argus/rag/
│   │   ├── auth/             认证、JWT、Cookie、用户权限
│   │   ├── user/             用户资料、管理员用户管理
│   │   ├── group/            群组/成员/邀请/入组申请
│   │   ├── document/         Word/PDF/MD 上传、角色门槛、预览/下载
│   │   ├── ingestion/        ETL：解析→chunk→向量+ES 索引
│   │   ├── qa/               RAG 问答、混合检索、记录/引用
│   │   ├── assistant/        助手 Agent、会话/记忆/工具
│   │   ├── excel/            在线表格、协同、版本、财务导入、回收站、分享
│   │   ├── finance/          薪酬/考勤/报表/PDF
│   │   ├── metrics/          LLM 用量/成本统计
│   │   ├── engine/           MinIO/ES/向量/搜索/重排适配层
│   │   ├── prompt/           提示模板
│   │   ├── config/           Spring AI、模型、OpenAPI 配置
│   │   └── common/           ApiResponse、异常、限流、日志、安全工具
│   ├── src/main/resources/db/migration/     Flyway SQL
│   ├── pom.xml / mvnw
│   └── docs/                 重构计划与报告
├── frontend/
│   ├── src/views/            首页/登录/文档/群组/Excel/财务/管理
│   ├── src/components/       共享组件
│   ├── src/api/              各模块 API 封装（axios）
│   ├── src/stores/            Pinia（auth/app）
│   ├── src/utils/             HTTP/univerAdapter/collabClient/format/fingerprint
│   └── vite.config.ts         `/api` `/ws` 代理
├── sql/                       schema.sql、excel_schema_pg.sql（辅助，非 Flyway）
├── scripts/                   启动与冒烟测试
├── logs/                      运行时日志
└── .env.example               生产环境变量示例（不含真实值）
```

## 4. 启动与调试

### 一键启动（Windows 本地）

```powershell
cd F:\ChatGPT\argus-loom
.\scripts\start-all.ps1
```

脚本会按顺序拉 ES（`F:\DevelopmentTools\elasticsearch\elasticsearch-8.9.2`）、MinIO、本地 embedding Python 服务，然后调用 `start-argus-servers.ps1` 启动后端 `mvnw -q spring-boot:run` 与前端 `npm run dev`，逐一等待就绪。前端就绪后浏览器访问 `http://localhost:8081`。

单独重启后端：

```powershell
.\scripts\restart-argus-backend.ps1
```

### 后端测试（offline Maven）

```bash
export JAVA_HOME='C:\Users\yuanchentai\.jdks\corretto-21.0.11'
export PATH="$JAVA_HOME/bin:$PATH"
cd /f/ChatGPT/argus-loom/backend && ./mvnw -o test
```

前端测试：`cd frontend && npm test`；构建：`npm run build`。

### 冒烟/调试脚本

- `scripts/full_smoke_test.py`：全链路冒烟（登录/文档/QA/Excel/财务/版本等）。
- `scripts/sse_raw_probe.py`：直接探测 SSE 流。
- `scripts/manual_stream_test.py`、`scripts/verify_excel_editor.py`：人工流式/Excel 校验辅助。
- `scripts/debug_smoke.py`、`scripts/local-embedding-server.py`。

## 5. 数据库与迁移

PostgreSQL `argus_rag` 由 Flyway 管理。迁移清单（`backend/src/main/resources/db/migration/`）：

| 脚本 | 内容 |
| --- | --- |
| `V1__init_core_schema.sql` | 用户、角色、群组、文档、上传分片、文档 chunk、向量表、QA 记录、助手会话等核心表 |
| `V2__excel_module_schema.sql` | Excel 文档、sheet、chunk、delta、版本、ACL、导出任务、协同房间/日志等 |
| `V3__finance_role_payroll_report.sql` | 财务角色、薪酬、考勤、报表、台账等 |
| `V4__document_required_role.sql` | 文档 `required_role` 与相关表 |

> `sql/schema.sql` 与 `sql/excel_schema_pg.sql`、`sql/fix_sequences.sql` 是辅助/参照脚本，不是 Flyway 迁移源。若执行 Flyway 前先跑 `sql/fix_sequences.sql` 对齐序列。

## 6. 后端关键行为（按业务域）

### 认证与用户

- `/api/auth/login`、`/register`、`/refresh`、`/logout`、`/me`。Access JWT 约 30 分钟；Refresh Token 14 天，httpOnly Cookie `ARGUS_RAG_REFRESH_TOKEN`，刷新时轮换。
- 当前只支持单设备登录：新登录会吊销旧 refresh token（`RefreshTokenService.revokeActiveTokens`）。
- 角色：`ROLE_USER` / `ROLE_FINANCE` / `ROLE_ADMIN`；新增用户默认 `ROLE_USER`。
- 密码策略有校验（长度、复杂度）；`/api/auth/me` 可查当前用户及角色。

### 权限与安全红线（不可弱化）

- 后端 `SecurityConfig`：`/api/auth/**`、健康检查、Swagger/Knife4j、`/ws/**` 放行，其余 `/api/**` 必须认证。
- `/api/finance/**` 强制 `hasRole('FINANCE')`；管理端 `/api/admin/**` 强制管理。
- 文档检索按角色做白名单/闭式过滤：`DocumentRoleGate` + `allowedDocumentIds(allowedRoleNames)`；**不得改动该判定实现**（项目红线）。
- Excel 权限判定集中在 `ExcelPermissionService.userCanAccessDocument`：`finance_required` 优先，owner > ACL（EDITOR/VIEWER）；**不得改动该判定实现**（项目红线）。
- `UploadInputGuard` 校验文件名/扩展名/大小；直传上限 10MB，分片上传最高 256MB。
- 错误一律 `ApiResponse` 外包：`{ success, data, message }`，业务异常用 `ErrorCodes` 统一编码。

### 文档与 RAG

上传链路：init → 分片 PUT `/upload-chunk` → `complete`；或小文件直传 `POST /api/documents`。

ETL（`DocumentIngestionAsyncService` → `EtlDocumentIngestionProcessor`）：
`MinIO → DocumentParserFactory(txt/md/pdf/docx) → TextCleanupTransformer → StructureAwareChunkTransformer(240 tokens/overlap 32) → pgvector embeddings → Elasticsearch chunks`。

问答链路 `HybridChunkRetrievalService`：
查询改写/规划(`QueryPlanningService`) → PGVector 向量 + ES 关键词 → RRF 融合 → 聚类 + neighbor context → 重排(`RerankingService` 默认 NoOp) → 证据包 → LLM 回答（U / E1/E2/E3 证据等级）→ 引用锚点。同步 `/api/qa/ask`、流式 `/api/qa/stream-ask`（SSE）。

可选网页搜索：`YoucomSearchService`（需 `YOUCOM_API_KEY`）和 `FreeWebSearchService`（Bing 元搜索，无需 Key）。

### 助手（Assistant）

- `AssistantReactAgentFactory` 构建 ReactAgent；`AssistantAgentFacade` 提供同步超时与 `streamChat()`。
- 工具：`AssistantKnowledgeBaseTool`（KB 检索）、`StructuredDataQueryTool`、`CurrentTimeTool`、`WebSearchTool`。
- 会话（`assistant_sessions`）+ 短记忆（`assistant_session_contexts`，BEFORE_MODEL 注入摘要+近期消息）+ 长记忆（`user_memories`：提取、策略、检索用户长期事实）。
- `AssistantStreamEventEmitter` 负责 SSE 事件；`AssistantShortTermMemoryHook` 结合 `AssistantMemorySummarizer`。

### Excel 协作与持久化

关键服务：
- `ExcelParserService`（POI/快速解析）+ `StreamingParserService`（SAX 流式，大文件）。
- `ExcelSheetService`：sheet 与 chunk 层；`SheetChunkMerger` 合并 delta（LWW）。
- `CollabService`：WebSocket + STOMP，`/ws`（前端 `collabClient.js`）；消息含 `operationId`、`cellData`、`rev`、`timestamp`，LWW 冲突解决 + Ack。
- `ExcelDocumentLockService`：单实例写锁（避免并发写损坏）。
- `ExcelVersionService`：`version/version_sheet/version_chunk` 分层（MANUAL/CELL/STRUCTURE/ROLLBACK），保留 N 版；`SnapshotService` 定期快照。
- `RedisChunkCacheService` 缓存 chunk；导出走异步任务 `excel_export`；分享链接 7 天过期。
- `univerAdapter.js`：Luckysheet 旧数据与 Univer 格式互转。

### 财务

- `/api/finance/**`（FINANCE 角色）：`FinanceImportController` 导入台账 → `finance_ledger_record`；`PayrollService` 薪酬考勤；`FinanceReportController` 报表 + AI 简报 + PDF。
- `fingerprint.js` / `recordFingerprint` 为财务行更新提供乐观锁（前后端一致避免覆盖）。

### 指标与运营

- LLM 指标收集与成本（token/费用/模块），`/api/admin/metrics` 管理端可见。
- 操作日志 `operation_log`、Excel 运营中心（版本策略、回收站等）。

## 7. 前端关键模块

- `api/http.ts`：axios `baseURL=/api`，统一处理 `ApiResponse`，401 触发刷新。
- `stores/auth.ts`：bootstrap/refresh 模式；`stores/app.ts` 当前群组上下文。
- `router/index.ts`：`/app` 子路由（文档/问答/助手/群组/Excel/财务/管理/设置）；`requireAdmin`、`requireFinance` 守卫。
- `views/excel/SheetEditor.vue` + Univer；`useCollabSync` / `useRemotePresence` / `useEditHistory` 协同链路。
- `views/assistant/*`：会话/Composer/Transcript/CitationBar/MemoryPanel。
- `utils/univerAdapter.js`、`utils/fingerprint.js` 与后端保持一致（用于财务乐观锁）。

## 8. 前后端契约要点

- 所有响应包装 `ApiResponse`（HTTP 200 + `success:false` 也可能表示业务失败；SSE 流除外）。
- 文件上传：init + chunk + complete（256MB）；直传 ≤10MB。
- Header：`Authorization: Bearer <accessToken>`；refresh token 由 Cookie 自动带，前端无需手动。
- WebSocket：`/ws`（SockJS/STOMP）；`/topic/excel/{documentId}` 广播；`/app/excel/edit` 编辑指令。
- SSE 接口在 Vite `proxy` 中已关闭缓冲；新增 SSE 时保持。

## 9. 环境变量（`.env.example`）

复制 `.env.example` 为 `.env` 后使用；生产严禁把密钥写进仓库。关键项：
- LLM：`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `LLM_CHAT_MODEL` / `EMBEDDING_MODEL`（OpenAI 兼容 / DashScope）。
- DB：`DB_URL`/`DB_USER`/`DB_PASSWORD`；MinIO `MINIO_ENDPOINT`/Key/Bucket；ES `ES_HOST`/`ES_PORT`。
- 认证：`ARGUS_RAG_JWT_SECRET`（至少 32 字符）等。

## 10. 常见陷阱与约定

- `security` 红线：不得绕过 `ExcelPermissionService` / `DocumentRoleGate` 判定（有测试/审查）。
- Vite 代理对 SSE 已做 `selfHandleResponse:false` + 去缓冲；新增 SSE 接口时保持该配置。
- MyBatis-Plus：测试 mock 回归容易踩 `any(Entity.class)` 与基本类型混用问题；实体 stub 用 typed `any(...)`。
  - 另注意：mock 的 `List` 返回值默认是**空列表**而非 null（缓存"未命中返回 null"的契约在 mock 上会变成空列表，判断未命中需同时兼容空集合）。
- POI 流式解析：不要对大工作簿整读内存，使用 SAX 的 `StreamingParserService`。
- 重构时遵循 `backend/.claude/CLAUDE.md` 维护约定（拒绝事项：不改权限判定、逐批长循环）。
- 线程池：`@Async`/`@Scheduled` 已绑定 `common/config/AppThreadPoolConfig` 的 `taskExecutor`/`taskScheduler`；
  新增异步任务优先复用现有池（通用异步/导出 `excelExportExecutor`/检索并行 `retrievalExecutor`），不要再用公共 ForkJoinPool。
- Excel 分块读路径有进程内缓存（`RedisChunkCacheService`，有界 LRU + TTL）：
  **任何直接改写 `excel_sheet_chunk` / `excel_sheet_chunk_delta` 表的代码都必须调用
  `invalidateSheet` / `invalidateDocument`**，否则会读到旧 celldata。
- 配置键：LLM 超时/Excel 上传路径/WS 来源统一在 `argus.rag.*` 下
  （`argus.rag.llm.*`、`argus.rag.excel.upload-path`、`argus.rag.websocket.allowed-origin-patterns`）；
  绑定关系由 `ApplicationConfigBindingTest` 锁定，别再挂错层级。

## 11. 相关文档

- `backend/docs/refactor-plan.md`：重构计划与每个批次（离线测试节奏）。
- `backend/docs/optimization-report.md`：性能/查询优化报告（Phase 0..45 台账）。
- `backend/docs/optimization-assessment-report.md`：2026-09-19 只读优化评估（P0/P1/P2/P3 问题清单与 A-G 阶段计划）。
- `backend/docs/assessment-stage-progress.md`：**A-G 阶段执行台账**（各阶段改动、验证结论、"评估后不改"清单；续跑先读它）。
- `backend/docs/error-codes-guide.md`：错误码说明。
- `frontend/README.md`：前端脚手架说明。
