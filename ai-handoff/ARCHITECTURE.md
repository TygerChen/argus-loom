# Argus Loom 架构地图

## 1. 系统全景

```text
Browser
  |
  | HTTP / SSE / WebSocket-STOMP
  v
Vue 3 frontend
  |
  v
Spring Boot backend (modular monolith)
  |-- PostgreSQL + pgvector
  |     |-- business tables
  |     |-- vector_store
  |     |-- background_job
  |     `-- excel_collab_outbox
  |-- Elasticsearch 8
  |-- MinIO
  `-- LLM / embedding runtime
```

这是一个模块化单体，不是微服务。代码按业务域分包，包内再按
`controller -> service -> mapper/model` 分层。部署时可以通过参数运行 API 实例和
Worker 实例，但共享同一套代码、数据库和对象存储。

## 2. 后端业务包

| 包 | 职责 | 关键入口/服务 |
| --- | --- | --- |
| `auth` | 登录、JWT、Refresh Cookie、安全配置 | `AuthController`、`SecurityConfig` |
| `user` | 用户资料、管理员用户管理 | `AccountController`、`AdminUserController` |
| `group` | 知识库群组、邀请、申请、成员 | `GroupManagementController` |
| `document` | 上传、秒传、预览、下载、角色门槛 | `DocumentController`、`DocumentUploadService` |
| `ingestion` | 解析、清洗、切片、向量与 ES 索引 | `EtlDocumentIngestionProcessor` |
| `embedding` | 本地 bge-small-zh 512 维 | `LocalEmbeddingConfig` |
| `engine` | pgvector、ES、MinIO、rerank、web search 适配 | `PgVectorRetrievalAdapter` 等 |
| `qa` | 查询规划、混合检索、证据、引用、问答记录 | `QaController`、`HybridChunkRetrievalService` |
| `assistant` | ReactAgent、工具、会话、短期/长期记忆 | `AssistantAgentFacade` |
| `llm` | 模型客户端抽象 | `ModelClient` |
| `prompt` | 提示词加载 | prompt templates |
| `excel` | 在线表格、协同、版本、导出、回收站、财务导入 | `ExcelDocumentController`、`CollabService` |
| `finance` | 薪酬、考勤、报表、PDF | `FinanceReportController`、`PayrollService` |
| `job` | 通用数据库任务队列和 Worker | `BackgroundJobService`、`BackgroundJobWorker` |
| `metrics` | LLM 用量、成本和运营指标 | `AdminMetricsController` |
| `common` | 异常、响应、线程池、限流、日志、健康 | `AppThreadPoolConfig`、`GlobalExceptionHandler` |
| `config` | 全局 Spring/AI/OpenAPI 配置 | `ModelConfig` |
| `evaluation` | 评估占位 | `RagEvaluationPlaceholder` |

## 3. 文档 ETL 旅程

```text
DocumentController
  -> DocumentUploadService
  -> UploadedDocumentFinalizer
       |-- insert documents(status=PROCESSING)
       `-- BackgroundJobService.enqueue(INGEST_DOCUMENT)

BackgroundJobWorker（@Scheduled fixedDelay 15s，每轮领取≤10条，租约 5 分钟；
  硬编码 switch 只认 INGEST_DOCUMENT）
  -> DocumentIngestionAsyncService（Spring Retry 2s/4s/8s ×3，@Recover 置 FAILED）
  -> EtlDocumentIngestionProcessor
  -> MinIO read
  -> DocumentParserFactory
  -> TextCleanupTransformer
  -> StructureAwareChunkTransformer
  -> document_chunks（先删后插）
  -> VectorIngestionService -> pgvector（先删后插 + 确定性 UUID）
  -> DocumentIngestionAsyncService.syncSearchIndex
       -> ElasticsearchChunkIndexService（ES 索引在这一步，不在 Processor 内）
  -> documents(status=READY)

background_job 状态机：QUEUED -> RUNNING -> SUCCEEDED | DEAD_LETTER
（没有 PENDING/RETRY；队列退避 1/5/15 分钟 ×3）
```

失败路径：

- 首次任务失败后按退避重试。
- 多次失败进入 `DEAD_LETTER`。
- 手动重试使用新的 `retryGeneration` 入队。
- 同键死信可被条件复活。
- 文档最终可进入 `FAILED`，用户可触发重试。

设计要点：

- 文档行与任务行同事务，避免“文档提交了但任务没入队”。
- Worker 用 `SKIP LOCKED` 多实例安全领取。
- 租约防止 Worker 崩溃后任务永久卡住。
- 语义是 at-least-once，因此切片和索引写入必须幂等。

## 4. RAG 问答旅程

```text
QaController
  -> QaService（群组成员校验、落库）
  -> QaChatService（编排、拒答、LLM）
  -> ReadyChunkDocumentRetriever
  -> HybridChunkRetrievalService
       |-- DocumentRoleGate 预过滤（规划之前）
       |-- QueryPlanningService: DIRECT | REWRITE | DECOMPOSE | GLOBAL（失败回退 DIRECT）
       |-- pgvector semantic recall (topK 50) + Elasticsearch keyword recall (topK 50)，顺序执行
       |-- DocumentRoleGate 二次过滤（召回之后、rerank 之前）
       |-- RRF 融合（k=0，在 RetrievalCandidate 内逐命中累加）
       |-- RerankingService (current NoOp，@ConditionalOnMissingBean 装配)
       |-- RetrievalCandidateSelector (final topK 5)
       |-- 邻居窗口 +-1（在选出 topK 之后扩展）
       |-- optional You.com web search（retrievalExecutor 仅并行此步，默认关闭）
       `-- EvidenceAssessor
            if NONE/insufficient -> RETRIEVAL_NOT_GROUNDED, skip LLM
  -> Prompt + LLM
  -> CitationAssembler
  -> qa_records + qa_record_citations
```

双接口：

- `POST /api/qa/ask`
- `POST /api/qa/stream-ask`，SSE

回答失败时仍要区分：

- 业务拒答：证据不足，属于可解释结果。
- 技术失败：模型、检索、数据库或网络异常。

## 5. AI 助手旅程

```text
AssistantChatController
  -> AssistantAgentFacade
  -> AssistantReactAgentFactory
  -> Spring AI Alibaba ReactAgent
       |-- AssistantKnowledgeBaseTool
       |-- StructuredDataQueryTool
       |-- CurrentTimeTool
       `-- WebSearchTool
  -> AssistantConversationService
  -> short-term context
  -> long-term user memories
```

助手和 `qa` 是两条链路，但可以复用知识库检索能力。工具调用时 `groupId/userId`
必须来自可信上下文，不能相信模型或客户端任意传入的权限字段。

## 6. Excel 协作旅程

```text
Vue UniverJS
  -> STOMP /ws
  -> ExcelSubscribeInterceptor / STOMP auth
  -> CollabController
  -> CollabService
       |-- authorization
       |-- LWW + revision conflict
       |-- excel_sheet_chunk_delta
       `-- excel_collab_outbox
  -> broadcast to topic
```

持久化模型：

- `excel_document`：工作簿。
- `excel_sheet`：工作表。
- `excel_sheet_chunk`：基础 celldata。
- `excel_sheet_chunk_delta`：增量修改。
- 合并时采用 LWW，并检查 revision。

缓存：

- `RedisChunkCacheService` 是进程内缓存，不是 Redis。
- 写 chunk/delta 后必须使对应 sheet/document 缓存失效。

广播：

- 协同消息是"直发 + Outbox 重放"双路径：`CollabController` 先直发 topic，
  `ExcelCollabEventPublisher` 再每 500ms 扫 `excel_collab_outbox` 重放，
  订阅者会收到重复消息，靠客户端按 requestId 去重。

锁与在线状态：

- `ExcelDocumentLockService` 与 `OnlineUserTracker` 都是 JVM 内实现，
  单实例有效；代码中出现的"分布式锁"字样名不副实。

版本、导出和审计：

- 版本快照、恢复、操作日志、撤销/重做。版本子资源（detail/preview/restore）
  必须校验 versionId 归属 documentId。
- Excel 导出使用专用 `excelExportExecutor`。
- 导出任务的创建、详情、下载都有文档 view 权限校验；产物有过期与清扫。

## 7. 财务旅程

```text
FinanceImportController
  -> FinanceImportService
  -> preview / validation / idempotency
  -> finance_ledger_record
  -> period summary / anomaly / dimension analysis
  -> report / PDF
```

关键约束：

- 所有 `/api/finance/**` 请求必须验证财务角色。
- 期间关闭后，该月写操作 fail-closed。
- 导入幂等键防止同一文件或业务批次重复入库。
- 财务工作簿由 `finance_required` 与 Excel ACL 双层保护。

## 8. 前端结构

| 目录 | 职责 |
| --- | --- |
| `src/views/documents` | 上传、列表、失败重试 |
| `src/views/qa` | 问答、引用、证据面板 |
| `src/views/assistant` | 助手会话、工具事件、记忆 |
| `src/views/groups` | 知识库群组 |
| `src/views/excel` | 表格、协同、版本、导出、财务工作台 |
| `src/views/admin` | 用户、指标、运维 |
| `src/api` | Axios API 封装 |
| `src/stores` | 登录态与全局上下文 |
| `src/router` | 路由和角色守卫 |
| `src/utils` | HTTP、协同、Univer 适配、财务指纹 |

前端守卫只改善用户体验，不能替代后端授权。

## 9. 核心数据表

| 表 | 用途 |
| --- | --- |
| `users` | 用户与角色 |
| `user_refresh_tokens` | Refresh Token 轮换与吊销 |
| `groups` / `group_memberships` | 知识库隔离与成员 |
| `documents` | 文档元数据、状态、角色门槛 |
| `document_upload_sessions` / `document_upload_chunks` | 分片上传 |
| `document_chunks` | 切片与来源定位 |
| `vector_store` | pgvector 512 维向量 |
| `qa_records` / `qa_record_citations` | 问答与引用 |
| `assistant_sessions` / `assistant_session_contexts` | 助手会话与短期记忆 |
| `user_memories` | 长期记忆 |
| `excel_document` / `excel_sheet` | 工作簿与工作表 |
| `excel_sheet_chunk` / `excel_sheet_chunk_delta` | 基础数据与增量 |
| `excel_collab_outbox` | 跨实例协同广播 |
| `background_job` | 通用持久任务 |
| `finance_*` | 财务导入、台账、薪酬、考勤、报表、期间 |

实际字段以 Flyway `V1`-`V8` 和 Mapper XML 为准。

## 10. 请求与失败设计

- REST 通常使用 `ApiResponse` 包装；前端不能只看 HTTP 200，还要检查
  `success` 和业务错误码。
- SSE 是流式协议，不套普通 `ApiResponse`。
- 上传、ETL、导出等长任务必须先返回可追踪状态，不能无限占用请求线程。
- 任何重试都应有上限、退避、幂等和死信/失败可见。
- 权限错误不要泄露资源是否存在；按现有策略返回 403 或 404。

## 11. 架构红线

1. 模块化单体优先，不为了“显得高级”拆微服务。
2. 权限在服务端每一层重新验证，尤其是子资源 ID。
3. 长任务必须有界、可恢复、可观测。
4. 不修改已执行 Flyway 迁移。
5. 不把 PostgreSQL 队列、本地缓存和单实例锁假设成跨实例能力。
6. 不把“接口存在”误写成“能力已经生效”，例如 reranker。
