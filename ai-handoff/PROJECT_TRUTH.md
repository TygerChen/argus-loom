# Argus Loom 当前事实

> 生成时间：2026-10-23
> 基线提交：`2d7ed06`
> 验证环境：Windows、JDK 21、系统 Maven 3.9.16、Node/npm 当前工作区

## 1. 事实优先级

发生冲突时按以下顺序判断：

1. 当前生产源码与数据库迁移
2. 当前自动化测试真实结果
3. 本文件
4. `AGENTS.md` 与 `ai-handoff/` 其他文档
5. `README.md`、教程与历史记忆
6. `.zcode/plans/`、旧架构图、历史会话摘要

本文件也不保证永久正确。任何与本次任务直接相关的关键结论，都应重新搜索调用方、
状态更新、SQL 和测试。

## 2. 仓库与验证状态

- 当前目录是 Git 仓库，分支 `master`。
- 基线提交：`2d7ed06`。
- 工作区存在用户未提交内容：`MEMORY.md` 修改、`docs/architecture/`、
  `docs/superpowers/`、`scripts/rag_eval/` 等。打包时必须保留，但不能误认为已提交。
- 后端全量测试：`1634` tests，`0` failures，`0` errors（2026-10-24 实测）。
- 前端单元测试：`14` tests，全部通过。
- 前端生产构建：成功。
- 构建告警：`vendor-univer` chunk 约 11.4 MB，gzip 约 2.9 MB。

历史记忆中的 `1616`、交接初版的 `1614` 均为旧验证值；`1634` 为 2026-10-24
含新增权限/队列/配置守卫测试后的实测值。后续任何测试数量都应重新运行后记录。

## 3. 技术栈

### 后端

- Java 21
- Spring Boot 3.5.0
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.0
- Spring Security + JWT（jjwt 0.12.6）
- MyBatis-Plus 3.5.15
- Flyway
- PostgreSQL + pgvector
- Elasticsearch 8
- MinIO
- Apache POI、PDFBox
- Spring WebSocket / STOMP
- Micrometer / Prometheus / Actuator

### 前端

- Vue 3
- TypeScript
- Vite 8
- Pinia
- Vue Router
- Element Plus
- UniverJS 0.25.x
- STOMP/SockJS
- Vitest

### 当前没有的基础设施

- 没有真实 Redis 依赖。
- 没有 RabbitMQ 依赖。
- `RedisChunkCacheService` 是进程内有界 LRU + TTL 缓存。
- `docs/architecture/index.html` 和部分旧文档中的 Redis 节点不是当前事实。

## 4. 当前已实现的产品能力

- 认证：登录、注册、Access JWT、httpOnly Refresh Cookie、刷新轮换、登出。
- 用户与角色：`ROLE_USER`、`ROLE_FINANCE`、`ROLE_ADMIN`。
- 群组知识库：创建、邀请、申请加入、成员管理、知识库隔离。
- 文档：小文件直传、分片上传、SHA-256 秒传/去重、预览、下载、删除、重试入库。
- 文档 ETL：MinIO 读取、TXT/MD/PDF/DOCX 解析、清洗、结构感知切片、
  本地 BGE embedding、pgvector、Elasticsearch 双索引。
- RAG 问答：查询规划、向量 + 关键词双路召回、RRF、权限过滤、邻居扩展、
  证据评估、LLM 生成、引用落库、同步与 SSE 流式接口。
- AI 助手：Spring AI Alibaba ReactAgent、工具调用、会话、短期记忆、长期记忆。
- Excel：上传、UniverJS 在线编辑、STOMP 协同、chunk + delta、LWW、Outbox 广播、
  版本、审计、撤销/重做、回收站、导出、分享、ACL。
- 财务：导入批次、幂等键、台账、期间关闭/重开、分析、异常、薪酬、考勤、报表。
- 运维：后台任务队列、健康检查、Prometheus、LLM 用量统计、容量/备份/安全文档。

## 5. 文档上传与 ETL 当前事实

上传成功后，`UploadedDocumentFinalizer` 在同一事务中：

1. 写入文档元数据。
2. 调用 `BackgroundJobService.enqueue`。
3. 写入 `INGEST_DOCUMENT` 后台任务。

幂等键格式：

```text
INGEST:{documentId}:{retryGeneration}
```

初始上传通常为：

```text
INGEST:{documentId}:0
```

失败文档手动重试时使用新的 generation，避免复用历史已成功/死信任务。注意
`retryGeneration` 不是递增计数：初始上传为 `0`，手动重试直接取
`System.currentTimeMillis()` 时间戳。手动重试入口是
`POST /api/documents/{documentId}/retry-ingestion` →
`DocumentDeleteService.retryFailedDocumentIngestion`（仅 FAILED 文档可重试，
先重置为 PROCESSING 再入队）。手动重试走全新幂等键（新任务行），不触发死信复活；
同键死信复活只发生在同键再次入队时。

软删除文档不走任务队列，同步删除向量与 ES chunk。上传落库同时会写一条
`ingestion_jobs` 观测记录。

队列语义（以 `BackgroundJob.java`、`BackgroundJobWorker.java`、
`BackgroundJobService.java` 为准）：

- PostgreSQL `background_job`，状态机 `QUEUED → RUNNING → SUCCEEDED | DEAD_LETTER`，
  没有 PENDING / RETRY 状态
- Worker `@Scheduled` fixedDelay 15 秒轮询（可配 `argus.job.poll-interval-ms`），
  每轮最多领取 10 条，提交 `taskExecutor` 执行，租约默认 5 分钟
- 领取 SQL 为 `FOR UPDATE SKIP LOCKED`，同时接管 `lease_until` 过期的 RUNNING 任务
- 队列级退避 `{1, 5, 15}` 分钟；`maxAttempts`（当前 3）耗尽进 `DEAD_LETTER`
- ETL 内部另有一层 Spring Retry `@Retryable`（2s/4s/8s × 3）+ `@Recover`。
  **2026-10-24 演练修正**：两层重试**不是叠加而是互斥**——`@Recover`
  （`DocumentIngestionAsyncService.recover:149`）吞掉异常正常返回，业务性失败
  （如坏 PDF）走"文档 FAILED + 队列任务 SUCCEEDED"，**不进死信**；队列级
  1/5/15 分钟退避只对未受 Spring Retry 包裹的系统级异常生效（实测坏 PDF 演练
  `argus.job.dead_letter` 全程为 0；手动重试端点只接受 FAILED 文档）。
- 同键死信可在再次入队时条件复活（条件 UPDATE 只认 DEAD_LETTER，防并发双复活）
- Worker 是硬编码 switch，只认 `INGEST_DOCUMENT`，未知类型转死信
  `UNKNOWN_JOB_TYPE`；没有 processor 注册机制。`BackgroundJob` 类注释中的
  `EXCEL_EXPORT / FINANCE_IMPORT` 是预留描述，不是当前 Worker 能力
- 整体为 at-least-once，因此 ETL 必须幂等

旧的 `DocumentIngestionRequestedEvent` + `@TransactionalEventListener` +
`DocumentIngestionAsyncListener` 链路已删除。任何文档仍说生产 ETL 依赖这组事件，
都是历史描述。

ETL 边界与幂等事实（2026-10-24 核实）：

- Elasticsearch 索引不在 `EtlDocumentIngestionProcessor` 内，而在
  `DocumentIngestionAsyncService.syncSearchIndex`（调
  `ElasticsearchChunkIndexService.indexReadyChunks`）。
- 幂等靠三层保障：任务开头 `cleanupProcessingArtifacts` 先清 chunk/向量/ES；
  chunk 落库先删后插；向量写入先删后插且向量 ID 用 documentId+chunkIndex
  生成的确定性 UUID。
- 启动时 `StaleProcessingDocumentRecoveryRunner` 把所有遗留 PROCESSING 文档
  批量置 FAILED（不区分中断时间，不重新入队，靠用户手动重试）。此前注释声称的
  `document.ingestion.processing-timeout-minutes` 时间窗从未生效，不要引用。

Excel 导出是例外：继续使用专用 `excelExportExecutor`，不是 `background_job`。

## 6. RAG 当前事实

核心入口（2026-10-24 核实，真实分层）：

```text
QaController
  -> QaService（群组成员校验、落库）
  -> QaChatService（编排、拒答、LLM 调用）
  -> ReadyChunkDocumentRetriever
  -> HybridChunkRetrievalService（规划、双路召回、融合、重排、邻居、证据评估）
  -> CitationAssembler
  -> qa_records / qa_record_citations
```

当前关键参数与事实：

| 项 | 当前值/行为 |
| --- | --- |
| 单个召回通道 topK | `50` |
| 进入重排的 recallK | `50` |
| 最终证据数 default topK | `5` |
| RRF 默认常数 | `k=0`；在 `RetrievalCandidate` 内逐命中累加，不是独立融合步骤 |
| 权限过滤 | `DocumentRoleGate` 调用两次：查询规划前预过滤 + 双路召回后过滤（均在 rerank 之前）；fail-closed 表现为返回空集，与"查无此文"同呈现，不靠异常 |
| 邻居窗口 | 前后各 `1` 个 chunk，在 `RetrievalCandidateSelector` 选出最终 topK=5 之后扩展 |
| `retrievalExecutor` | 仅并行 You.com 网页搜索子查询；向量 + ES 双路召回是顺序执行 |
| 拒答呈现 | 同步路径 HTTP 200 返回 `unanswered(RETRIEVAL_NOT_GROUNDED)`，不调 LLM；SSE 路径发 `error` 事件（仅 message，错误码存 `qa_records.reason_code`） |
| Embedding | 本地 bge-small-zh，`512` 维，JVM 内（langchain4j ONNX） |
| 向量索引 | pgvector HNSW，cosine |
| 关键词索引 | Elasticsearch 8 |
| 查询规划 | DIRECT / REWRITE / DECOMPOSE / GLOBAL，失败回退 DIRECT |
| Reranker | 两档装配（`argus.rag.reranking.provider`）：默认 NoOp；`remote` 时走本地网关 BGE cross-encoder（`RemoteHttpRerankingService`，50 对实测 3.47s，超时/故障/响应异常一律回退 RRF 原序）。2026-10-24 已在 local profile 启用；精选 40 题集上与 NoOp 指标持平（基线饱和），压测集对比进行中 |
| 联网搜索 | You.com，可选，默认关闭 |
| 切片参数 | yml 覆盖值 240/320/32/2（dev/prod）；Java 默认 500/800/80/2。引用数字时以 yml 为准 |

切片配置：

- target：`240` tokens
- max：`320` tokens
- overlap：`32` tokens
- chars-per-token：`2`

不要声称项目已经实现 HyDE、GraphRAG、父子分块、真实 Cross-Encoder rerank 或语义缓存。
这些可以作为演进方案讨论，但不是当前代码事实。

## 7. 权限与安全当前约束（2026-10-24 更新）

P0-1/P0-2 进度（详见 `docs/operations/credential-hygiene-audit.md` 与
`docs/operations/multi-instance-audit.md`）：

- 凭据卫生：`application-dev.yml` 的仓库级默认口令**已随 origin/master 推送到
  Gitee**（自初始提交即入库）——轮换步骤已写入审计文档，**等用户手工执行**；
  已落地：prod profile 启动配置 fail-fast 守卫（`ProductionConfigGuard`）、
  `.gitignore` 补 `backend/upload/`、`application-local.yml` 确认从未入库。
- 多实例状态：14 项证据矩阵已逐项核实到 file:line；新发现 Outbox 重放对
  非保存实例存在**真实消息丢失窗口**（selectPending 无认领语义）。当前仅
  Worker 可安全水平扩展，API 双实例前必须处理限流/写锁/STOMP broker。

### 硬约束清单

- 文档访问和 RAG 引用必须经过文档角色门槛。
- Excel 文档、工作表、版本、导出、分享和回收站访问必须回到所属文档重新鉴权。
  2026-10-24 核实并补齐两处缺口（提交 `76d4f7d`）：导出任务**创建**侧此前缺少
  源文档 view 校验，现与详情/下载同口径；版本 detail/preview 此前不校验
  versionId 归属 documentId，现与 restore 一致按 EXCEL_NOT_FOUND 拒绝跨文档访问。
- 财务 API 必须经过 `ROLE_FINANCE` 校验；系统管理员没有隐式财务豁免。
- 前端 Markdown 渲染必须净化，避免存储型 XSS。
- Web 搜索和远程抓取必须有 SSRF 防护。
- 生产密钥只能来自环境变量。

工作区仍存在一个安全清理项：`application-dev.yml` 中可能有仓库级默认密码值。
打包副本必须脱敏；原仓库是否修改及凭据是否轮换，应由用户确认后单独处理。

## 8. 关键目录

```text
backend/src/main/java/com/argus/rag/
  auth/         登录、JWT、Cookie、Security
  user/         用户与管理员
  group/        知识库群组、成员、邀请
  document/     上传、预览、下载、角色门槛
  ingestion/    解析、清洗、切片、索引
  embedding/    本地 embedding
  engine/       pgvector、ES、MinIO、rerank、web search
  qa/           查询规划、混合检索、证据、引用、问答记录
  assistant/    ReactAgent、工具、会话、记忆
  llm/          模型调用抽象
  prompt/       提示词模板
  excel/        在线表格、协同、版本、审计、财务导入
  finance/      薪酬、考勤、报表、PDF
  job/          background_job 与 Worker
  metrics/      LLM 用量与运营指标
  common/       异常、响应、限流、线程池、日志、健康检查
  config/       全局配置
  evaluation/   评估占位

frontend/src/
  views/        页面
  api/          HTTP API 封装
  stores/       Pinia 状态
  router/       路由与权限守卫
  components/   共享组件
  utils/        协同、Univer、格式化、指纹

backend/src/main/resources/db/migration/
  V1 ... V8     当前 Flyway 迁移
```

## 9. 当前已知风险

1. 本地内存状态在多 API 实例下可能不一致，包括限流、chunk 缓存、在线用户和
   单实例锁；引入多实例前必须逐项审计。
2. Reranker 是 NoOp，RAG 的“两阶段精确排序”尚未真正落地。
3. RAG 首轮真实基线已落盘（2026-10-24：HitRate@5=100%、拒答准确率 100%、综合 95.0%，见 `docs/operations/live-stack-verification-20261024.md`）；后续需定期重跑形成趋势，且该基线限于固定评测语料。
4. Univer 前端 vendor chunk 过大，影响首次加载。
5. 默认配置和 Git 历史中的凭据卫生需要专项处理。
6. “生产可试点”文档描述的是目标与验收门槛，不等于已经完成真实压测和业务验收。

## 10. 当前事实来源

- `backend/pom.xml`
- `frontend/package.json`
- `backend/src/main/resources/application*.yml`
- `backend/src/main/java/com/argus/rag/**`
- `backend/src/main/resources/db/migration/**`
- `backend/src/test/**`
- `docs/operations/**`
- `docs/acceptance/**`
- 2026-10-23 本次测试与构建结果
