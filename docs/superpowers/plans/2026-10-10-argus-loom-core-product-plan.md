# Argus Loom 500 人企业知识库与财务 Excel 系统实施计划

> **面向 AI 代理的工作者：** 使用 `subagent-driven-development` 或 `executing-plans` 逐任务实施；每个任务先补失败测试，再实现，再验证，再独立提交。

**目标：** 将现有 Argus Loom 建成支持 500 名内部注册用户的“企业内部知识库 + 财务 Excel 协作与分析系统”，在权限、并发、可恢复性和审计方面达到内部试点生产标准。

**架构：** 保持 Spring Boot + Vue 的模块化单体，将交互 API 与耗时任务分离为 API 实例和 Worker 实例，但共享同一代码库、数据库和对象存储。通过数据库任务表保证任务可靠领取；通过 Redis 提供跨实例限流、缓存和协同会话状态；通过 STOMP Broker Relay 支持 Excel 协同消息跨 API 实例广播。

**技术栈：** Java 21、Spring Boot 3.5、MyBatis、Flyway、PostgreSQL、Elasticsearch、Redis、RabbitMQ（STOMP Relay）、Vue 3、TypeScript、Vite、Vitest、Prometheus/Micrometer。

**规格：** 本文档；已有业务与安全基线见当前代码、`backend/src/main/java/com/argus/rag/` 和现有 Flyway 迁移。

## 全局约束

- 不接入 FastGPT，不拆分为多个业务微服务，不引入 Kubernetes 作为第一期前置条件。
- 目标为 500 名注册用户，按 100 名同时在线、40 个同时 RAG 请求、20 个同时 Excel 协作连接、5 个同时上传/导入任务进行容量设计。
- 所有耗时操作必须具备有界并发、有界队列、超时、取消、重试上限和可观测指标；禁止使用无界线程池、`parallelStream()`、`ForkJoinPool.commonPool()` 或 HTTP 请求线程执行重任务。
- 现有 `AppThreadPoolConfig`、`RedisChunkCacheService`、`RateLimitAspect` 只能作为过渡基础；生产多实例场景必须替换本地内存状态，不能假设单 JVM。
- 所有授权必须在服务端执行；工作表、版本、导出、附件、问答引用均须回溯到所属资源后重新鉴权。
- 不读取、提交或在文档中写入真实密钥、`.env` 内容、生产连接串或真实财务数据。

## 审查重点（Review Focus）

1. 高峰期上传/导入队列满时，用户应收到可重试的 429/503 和任务状态，绝不能由 `CallerRunsPolicy` 把重活落到 Web 请求线程导致全站阻塞。
2. 两个 API 实例上的 Excel 用户应看到同一份实时编辑结果；任一实例重启不得丢失已确认的单元格修改。
3. 同一个任务被多个 Worker 同时扫描时，只能有一个 Worker 领取并执行；失败重试不得产生重复 chunk、重复财务流水或重复导出文件。
4. 数据库、ES、模型服务或 RabbitMQ 任一依赖变慢/不可用时，应快速降级、熔断并释放线程与连接，不能无限等待或级联雪崩。
5. 500 人规模下任意用户都不能通过资源 ID、WebSocket 帧、历史引用或缓存键读取到自己无权访问的知识或财务资料。

---

> 本计划以当前 `Argus Loom` 代码库为唯一实施对象：不接入 FastGPT，不引入第二个大型平台，不进行微服务拆分。目标是把现有 Java + Vue 单体应用做成可在 500 人企业内部试点的“受控知识库 + 财务 Excel 协作与分析”产品。

## 1. 目标

在 12 至 16 周内，将 Argus Loom 建设为可试点交付的内部知识与财务协作系统，优先完成：

1. **权限可信**：任何文档、工作簿、工作表、版本、导出文件、审计记录、问答记录都不能通过篡改 ID 越权访问或修改。
2. **上传可信**：支持可追踪、可恢复、可限制的文档/Excel 上传与解析，失败可见、可重试，避免压缩炸弹和重复入库。
3. **检索可信**：只从当前用户有权访问的资料中检索；回答包含可验证引用；无法找到证据时明确回答“不确定”。
4. **引用可信**：引用可跳转到对应文档版本、页码/段落/表格区域；权限变化后历史问答也不得泄露内容。
5. **Excel 可协作**：工作簿、工作表、版本、协同编辑、导出、回收站全部使用同一套资源授权规则。
6. **财务可落地**：财务角色能导入模板化数据、校验异常、查看报表、冻结期间并保留完整审计轨迹。

### 试点边界

第一期仅面向一个组织的 500 名内部注册用户，建议按 100 名同时在线、1 万至 5 万份知识文档、1,000 个工作簿、20 个同时协作会话、每天 200 份上传/导入任务进行压测和资源预估。不要在本期承诺多租户 SaaS、外部公开分享、复杂审批流、跨云同步、图片/视频生成或通用网页搜索。

## 2. 当前架构与关键事实

### 技术栈

- 后端：Spring Boot 3.5、Java 21、MyBatis、Flyway、PostgreSQL、Elasticsearch、向量检索。
- 前端：Vue 3、TypeScript、Vite、Vitest。
- 存储：业务数据库、文档存储、Excel 相关表、任务与审计表。
- 协作：STOMP/WebSocket。

### 当前业务模块

- RAG 知识库：`documents`、`document_chunks`、异步解析与切块、检索、问答记录。
- Excel：`excel_document`、`excel_sheet`、单元格增量、ACL、版本、资产、导出、回收站、协同编辑。
- 财务：导入批次、账本、薪资、报表；财务资料以 `finance_required` 角色门槛保护。

### 已确认必须修复的问题

下列问题不是“优化项”，而是上线前阻断项：

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| S1 | Excel 控制器验证工作簿 ID，却按客户端传入的 `sheetId` 读写工作表 | 可跨工作簿读取或篡改单元格 |
| S2 | 结构化数据、版本、操作日志、导出、回收站接口缺少或错误使用 Excel ACL | 可通过 ID 越权读取、恢复、导出或删除 |
| S3 | 管理员快捷权限绕过财务门槛 | 非财务管理员可编辑/管理财务工作簿 |
| S4 | 结构化数据 Agent 工具把 RAG 文档 ID 当作 Excel 工作簿 ID | 可能造成 ID 碰撞越权，也可能导致工具不可用 |
| S5 | Markdown 直接进入 `v-html`，未净化 | 上传文档或模型回复可形成存储型 XSS |
| S6 | 网页抓取直接请求外部 URL，缺少地址与重定向校验 | Agent 可被诱导访问内网服务（SSRF） |
| S7 | Excel 压缩包防护阈值过宽、上传约束不足 | 压缩炸弹/超大表格造成服务不可用 |
| S8 | 协同消息信任客户端用户信息与时间戳 | 审计身份可伪造、LWW 时间戳可被恶意占用 |
| S9 | 问答评分、附件签名、分享链接等存在对象级授权或完整性缺口 | 数据被他人评分/资源链接被滥用 |
| S10 | 任意登录用户可调用图片/视频生成代理 | 成本失控，与核心产品无关 |

## 3. 产品定位与第一期用户故事

### 定位

Argus Loom 不是“什么都能做的 AI 平台”，而是：

> **让内部制度、项目资料、财务工作簿在明确权限下可上传、可检索、可引用、可协作、可追责。**

### 三个高价值场景

1. **制度问答**：员工提问“差旅报销标准是什么”，系统只检索其所属知识库中已发布的制度，给出答案及制度名称、版本、页码/段落链接。
2. **财务工作簿协作**：财务人员导入月度预算或费用明细，成员按 ACL 共同编辑；所有改单元格、恢复版本、导出文件都有审计记录。
3. **月结分析**：财务人员导入标准化流水，系统在入库前展示校验错误；导入后可查询期间汇总、异常值、账实差异，并生成可复核的报表。

### 明确不做

- 不支持匿名访问、公开分享链接直接访问内容。
- 不支持以“超级管理员”绕过财务资料门槛。
- 不允许大模型自行决定是否跳过权限校验。
- 不将通用互联网搜索作为默认 Agent 能力；需要时使用安全代理且默认关闭。
- 不在本期做完整 ERP、审批流引擎、会计记账替代品。

## 4. 总体架构决策

### 4.1 保持模块化单体，不拆微服务

继续使用现有 Spring Boot 单体。权限、上传、索引、Excel、财务之间强一致需求较多；当前用户规模下拆微服务只会增加部署、事务、调试和学习成本。

### 4.2 资源类型必须显式化

禁止再用一个无类型的 `documentId` 同时表达 RAG 文档和 Excel 工作簿。

定义以下资源类型：

```java
public enum ResourceType {
    KNOWLEDGE_DOCUMENT,
    EXCEL_WORKBOOK,
    EXCEL_SHEET,
    EXPORT_FILE,
    QA_RECORD
}
```

对外 DTO、Agent 工具参数、审计日志都必须带 `resourceType`。其中：

- RAG 资料使用 `knowledgeDocumentId`；
- Excel 使用 `workbookId` 和 `sheetId`；
- `sheetId` 只能作为工作簿下的子资源，不能独立信任；
- 不通过“两个表恰好数字相同”的方式关联资源。

不建议本期引入覆盖全系统的新 `business_resource` 大表。优先通过显式 DTO、统一鉴权服务与外键校验解决当前问题，降低改造风险。

### 4.3 权限的唯一判定入口

新增或重构为两个领域服务：

- `DocumentAccessService`：RAG 文档、引用、问答记录访问判定。
- `ExcelAccessService`：工作簿、工作表、版本、操作日志、导出、回收站、附件访问判定。

任何 Controller、STOMP Handler、Agent Tool、定时任务不得自行查询 ACL 后做 if 判断；必须调用领域服务。服务需要提供：

```java
WorkbookAccess requireWorkbookView(Long workbookId, CurrentUser user);
WorkbookAccess requireWorkbookEdit(Long workbookId, CurrentUser user);
WorkbookAccess requireWorkbookManage(Long workbookId, CurrentUser user);
SheetAccess requireSheetView(Long workbookId, Long sheetId, CurrentUser user);
SheetAccess requireSheetEdit(Long workbookId, Long sheetId, CurrentUser user);
```

`requireSheet*` 内部必须验证 `sheet.workbook_id == workbookId`。财务工作簿在任何权限等级之前先验证当前用户拥有 `ROLE_FINANCE`；管理员不例外。

### 4.4 回答与引用的数据契约

每一段 AI 回答携带机器可读的引用数组，不再只返回 Markdown 中的自由文本链接：

```json
{
  "answer": "差旅住宿标准为……",
  "citations": [
    {
      "citationId": "cit_xxx",
      "knowledgeDocumentId": 1024,
      "documentVersion": 3,
      "chunkId": 9981,
      "title": "2026 差旅管理制度",
      "page": 4,
      "section": "住宿标准",
      "quote": "不超过……",
      "score": 0.87
    }
  ],
  "retrievalStatus": "GROUNDED"
}
```

引用展示与原文预览时再次鉴权。若资料在问答后被降权、删除或切换为财务资料，历史问答只能显示“该引用已无访问权限”，不能返回原片段。

### 4.5 状态机优先于布尔字段

将文档/工作簿处理过程统一为可观察状态，避免“上传成功但看不到、不知道是否已经索引”：

```text
UPLOADING -> VALIDATING -> PARSING -> INDEXING -> READY
                                 \-> FAILED
READY -> REINDEXING -> READY | FAILED
READY -> ARCHIVED -> DELETED
```

失败状态必须记录错误码、用户可读信息、失败步骤、可否重试、重试次数。`FAILED` 不能进入检索结果。

### 4.6 500 人规模的并发与多线程模型

本期不依赖“线程越多越快”。交互请求、CPU 密集处理、远程模型调用、文件导出、定时扫描必须隔离，所有队列必须有上限。

#### 部署拓扑（第一期生产目标）

```text
Nginx / 负载均衡
        |
  API-1 + API-2  ---- Redis（限流、缓存、会话/锁）
        |                 |
        +---- RabbitMQ ---+---- Worker-1（解析、索引、导出、财务导入）
        |
 PostgreSQL + Elasticsearch + 对象存储 + LLM/Embedding 服务
```

- **API 实例 2 个**：只处理登录、权限、查询、SSE/HTTP 流与 WebSocket 接入；每实例建议 4 vCPU、8 GB RAM。负载均衡需开启 WebSocket 支持与会话粘性，但业务正确性不能依赖粘性。
- **Worker 实例 1 个起步**：专门执行文档解析、切块、向量化、ES 写入、Excel 导出和财务导入；建议 4 vCPU、8 GB RAM。任务积压持续 15 分钟以上时再横向增加 Worker。
- **PostgreSQL**：建议独立 4 vCPU、16 GB RAM、SSD；连接池按所有 API/Worker 总和控制，不能让每个实例默认开大量连接。
- **Redis 与 RabbitMQ**：生产环境必须独立部署并持久化；开发环境可以使用 Docker Compose。本期不做 Redis Cluster/RabbitMQ 集群，但文档必须写明备份与重启恢复流程。

#### 线程池和背压参数

在 `AppThreadPoolConfig` 中显式定义下表中的 Executor。所有数值必须配置化，不能写死在业务类中。

| Executor | 用途 | 每 API/Worker 实例初始值 | 队列满策略 |
| --- | --- | --- | --- |
| `retrievalExecutor` | 并发检索、ES/重排协调 | core 4 / max 8 / queue 64 | 立即拒绝，映射为 `RETRIEVAL_BUSY` |
| `llmExecutor` | 模型/Embedding 远程调用 | core 8 / max 16 / queue 80 | 立即拒绝，映射为 `MODEL_BUSY` |
| `ingestionExecutor` | 解析、切块、索引 | core 2 / max 4 / queue 20 | 不在 JVM 排队，保留 DB 任务为 `QUEUED` |
| `excelExportExecutor` | XLSX 导出 | core 1 / max 2 / queue 20 | 保留 DB 任务为 `QUEUED` |
| `financeImportExecutor` | 财务导入和校验 | core 1 / max 2 / queue 10 | 保留 DB 任务为 `QUEUED` |
| `collabPersistExecutor` | 协同编辑持久化批处理 | core 2 / max 4 / queue 200 | 触发客户端节流并记录拥塞指标 |
| `taskScheduler` | 轮询、过期清理、指标刷新 | pool 2 | 单次任务加分布式锁，禁止重入 |

将现有 `CallerRunsPolicy` 改为可观测的拒绝策略：交互请求返回 429/503；可靠后台任务留在数据库队列中等待 Worker 领取。严禁因队列满而让 Tomcat/SSE/WebSocket I/O 线程执行解析、导出或模型调用。

#### 可靠任务队列

新增统一 `background_job` 表，文档入库、Excel 解析、导出、财务导入都复用它；业务表只保存业务状态和 `job_id`。

```text
id, job_type, resource_type, resource_id, idempotency_key,
status, priority, payload_json, attempt_count, max_attempts,
lease_owner, lease_until, next_run_at, last_error_code,
created_at, started_at, finished_at, version
```

- Worker 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 批量领取到期任务；领取语句和状态转换必须在一个短事务内完成。
- `idempotency_key` 建唯一索引：上传以内容哈希 + 目标容器 + 版本策略生成，导入以文件哈希 + 模板版本 + 期间生成，导出以工作簿版本 + 导出参数生成。
- Worker 每 30 秒续租；实例崩溃后租约过期的任务才能被其他 Worker 接管。
- 最多重试 3 次，按 1 分钟、5 分钟、15 分钟退避；达到上限进入 `DEAD_LETTER`，需授权用户手动重试。
- 外部副作用必须在任务内再次检查幂等键。DB 成功但 ES/对象存储失败时进入补偿任务，不使用跨系统分布式事务。

#### Excel 协作跨实例规则

1. API 节点不使用 Spring Simple Broker 作为生产消息总线；改用 RabbitMQ STOMP Broker Relay。
2. 每个写入帧带 `workbookId`、`sheetId`、`baseRevision`、客户端请求 `operationId`；服务端以认证用户覆盖身份字段。
3. 服务器在事务内验证权限、验证工作表归属、写入单元格增量和操作日志、递增 `sheet_revision`；事务提交后再广播事件。
4. 事件采用 Outbox 表 + 后台发布器，避免“数据库已保存但消息未广播”或“先广播后事务回滚”。客户端断线重连后按最后 `revision` 拉取增量。
5. 对单个用户限制每秒 20 个协同写入帧、单帧最多 500 个单元格；超过阈值返回 `COLLAB_THROTTLED`，不静默丢数据。

#### 缓存与连接池规则

- `RedisChunkCacheService` 必须演进为真实 Redis 实现或清晰命名为本地开发缓存；缓存 key 包含资源版本与授权范围，不缓存未过滤的资料内容。
- 缓存只做加速，权限判定、导出下载、引用预览永远以数据库/授权服务为准。
- 每个 API 实例 Hikari 最大连接数初始为 15，Worker 为 10；两个 API + 一个 Worker 合计不超过 40。根据 PostgreSQL 的实际 `max_connections` 和监控数据调整。
- 为 ES、LLM、对象存储 HTTP 客户端设置连接池、连接超时（2 秒）、读取超时（30 秒）和调用总超时；模型流式回答最长 90 秒。
- 使用 Resilience4j 或等价机制为 LLM、Embedding、ES、对象存储配置 bulkhead、超时、有限重试和 circuit breaker；禁止无限重试。

## 5. 实施顺序与里程碑

| 里程碑 | 周期 | 交付物 | 上线门槛 |
| --- | --- | --- | --- |
| M0：安全封口 | 第 1-2 周 | Excel/问答/Agent 的越权与 XSS/SSRF 修复 | 所有对象访问接口有反向越权测试 |
| M1：可靠上传 | 第 3-4 周 | 文档/Excel 上传任务、校验、重试、去重 | 失败可见，超限被安全拒绝 |
| M2：可信检索与引用 | 第 5-7 周 | 检索证据链、引用卡片、离线评测 | 无权限资料绝不参与检索 |
| M3：Excel 协作闭环 | 第 8-10 周 | 协同、版本、审计、导出、回收站统一授权 | 可完成一次多人预算表协作 |
| M4：财务试点闭环 | 第 11-13 周 | 导入校验、期间汇总、异常、冻结 | 可完成一次月度流水导入与复核 |
| M5：运维与验收 | 第 14-16 周 | 监控、备份、限流、试点评测、运行手册 | 通过试点验收清单 |

每个任务遵循同一节奏：先新增失败测试，再实现最小改动，再运行模块测试和全量回归；不要跨任务顺手重构。

## 6. 详细开发任务

### 任务 1：建立 Excel 资源授权边界（最高优先级）

**目的**：彻底消除“验证 A 工作簿、实际操作 B 工作表”的 IDOR 问题。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/excel/service/ExcelPermissionService.java`
- 新增 `backend/src/main/java/com/argus/rag/excel/service/ExcelAccessService.java`
- `backend/src/main/java/com/argus/rag/excel/controller/ExcelDocumentController.java`
- `backend/src/main/java/com/argus/rag/excel/controller/StructuredDataController.java`
- `backend/src/main/java/com/argus/rag/excel/controller/ExcelVersionController.java`
- `backend/src/main/java/com/argus/rag/excel/controller/OperationLogController.java`
- `backend/src/main/java/com/argus/rag/excel/controller/ExcelRecycleBinController.java`
- `backend/src/main/java/com/argus/rag/excel/service/ExcelExportService.java`
- `backend/src/main/java/com/argus/rag/excel/websocket/CollabController.java`
- `backend/src/main/java/com/argus/rag/excel/service/CollabService.java`

**实现要求**：

1. 为所有 Excel 子资源查询增加 `workbookId` 条件；版本、操作日志、导出任务、回收站记录也必须回溯到所属工作簿后鉴权。
2. 删除或废弃只接受资源 ID 的不安全服务方法，例如 `canEdit(Long documentId)`、`canManage(Long documentId)`；保留的方法都接收当前用户并执行财务角色检查。
3. REST、WebSocket、异步导出、定时清理使用相同的 `ExcelAccessService`，不得复制权限逻辑。
4. `StructuredDataController` 每个入口先校验工作簿 view 权限；写入/查询时明确校验工作表属于工作簿。
5. `ExcelVersionController` 通过 `documentId + versionId` 查询版本，不得只按 `versionId` 查询。
6. 回收站批量操作先逐条授权；任何一条无权则整体拒绝，返回无权资源数量但不泄露名称。
7. 导出任务创建、状态查看、下载均要求工作簿 view 权限；导出文件归属工作簿，不能仅依据任务创建者判断。

**先写测试**：

- 用户仅有工作簿 A 权限，请求工作簿 A 路由中的工作表 B，返回 403。
- 用户仅有 A 编辑权限，提交 B 的 `sheetId`，B 单元格不发生变化。
- 非财务管理员对 `finance_required=true` 工作簿的 view/edit/manage 均返回 403。
- 无权用户不能查询版本、审计日志、导出任务、回收站记录。
- WebSocket 客户端向 A 主题发送 B 工作表编辑，消息被拒绝且不持久化。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=ExcelAccessServiceTest,ExcelDocumentControllerTest,CollabServiceTest test
mvn -f backend/pom.xml test
```

**建议提交**：`fix(excel): enforce workbook scoped access control`

### 任务 2：修复资源完整性与协同审计可信性

**目的**：确保“谁改了什么”真实可靠，并堵住旁路对象访问。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/excel/websocket/CollabController.java`
- `backend/src/main/java/com/argus/rag/excel/service/CollabService.java`
- `backend/src/main/java/com/argus/rag/excel/service/OperationLogService.java`
- `backend/src/main/java/com/argus/rag/excel/service/ExcelAssetService.java`
- `backend/src/main/java/com/argus/rag/excel/controller/ExcelAssetController.java`
- `backend/src/main/java/com/argus/rag/qa/controller/QaRecordController.java`
- `backend/src/main/java/com/argus/rag/qa/service/QaRecordPersistenceService.java`
- `backend/src/main/java/com/argus/rag/share/service/ShareLinkService.java`

**实现要求**：

1. WebSocket 忽略客户端传入的 `userId`、`displayName`，统一从认证 Principal 注入操作人。
2. 服务端生成操作时间；客户端时间仅可作为显示字段，不能参与单元格 LWW 冲突排序。设置合理的服务端版本号或单调序列。
3. Excel 资产下载先定位其工作簿，再执行 view 权限；迁移旧资产接口改为系统管理员专用后台任务，或直接下线。
4. 附件签名改为真实 HMAC/对象存储签名校验；不能验证时拒绝，而不是返回 `true`。
5. 问答评分更新必须带当前用户与记录可见性条件；普通用户只能评分自己有权查看的记录。
6. 分享链接创建与撤销要求资源 manage 权限。若本期不提供公开访问，移除无实际消费端的分享链接入口，避免制造伪需求。

**先写测试**：

- 客户端伪造管理员 `userId`，审计日志仍记录真实登录用户。
- `Long.MAX_VALUE` 客户端时间戳不影响之后正常编辑。
- 无工作簿权限访问附件返回 403。
- 用户不能给无权访问的 QA 记录评分。
- 非管理者不能撤销其他人的分享链接。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=CollabServiceTest,OperationLogServiceTest,QaRecordPersistenceServiceTest,ExcelAssetServiceTest test
```

**建议提交**：`fix(security): verify actor and resource ownership`

### 任务 3：封堵 XSS、SSRF、上传滥用与非核心高成本接口

**目的**：让“上传一份资料”不会成为执行脚本、扫描内网或刷爆模型账单的入口。

**主要改动文件**：

- 新增 `frontend/src/utils/safeMarkdown.ts`
- 新增 `frontend/src/utils/safeMarkdown.test.ts`
- `frontend/src/components/DocumentPreviewModal.vue`
- `frontend/src/views/qa/components/QaMessage.vue`
- `frontend/src/views/assistant/components/AssistantMessage.vue`
- 新增 `backend/src/main/java/com/argus/rag/security/SafeRemoteUrlPolicy.java`
- `backend/src/main/java/com/argus/rag/agent/service/FreeWebSearchService.java`
- `backend/src/main/java/com/argus/rag/agent/tool/WebSearchTool.java`
- `backend/src/main/java/com/argus/rag/excel/service/ExcelParserService.java`
- `backend/src/main/java/com/argus/rag/generation/controller/GenerationController.java`
- `backend/src/main/resources/application.yml`

**实现要求**：

1. 使用成熟 HTML 净化库（前端可选 DOMPurify），Markdown 渲染统一收口到 `safeMarkdown`；默认禁止原始 HTML、`style`、事件属性、危险 URL 协议。
2. 所有 `v-html` 只能接收 `safeMarkdown` 的输出；禁止在组件内直接 `marked.parse`。
3. 网页抓取只允许 `http/https`，解析 DNS 后拒绝 loopback、link-local、RFC1918 私网、保留地址、IPv6 本地地址；每次重定向后重新校验；限制超时、响应大小、重定向次数与 Content-Type。
4. `web-search.enabled` 默认改为 `false`。启用时仅允许系统管理员在部署配置中开启，不由普通用户开关。
5. Excel 上传增加：压缩条目数、解压后总大小、单条目大小、工作表数量、行数、列数、公式数量、单元格字符数上限；恢复安全的压缩比阈值。超限返回明确错误码。
6. 图片/视频生成接口在第一期删除路由或以配置默认关闭，并仅保留管理员受控入口；不得让普通登录用户消耗公共 API Key。

**先写测试**：

- Markdown 的普通文本、表格、代码块正常渲染；脚本、事件属性、`javascript:` URL 被移除。
- 解析到 `127.0.0.1`、`localhost`、`10.0.0.0/8`、`169.254.0.0/16`、IPv6 本地地址的 URL 被拒绝。
- 重定向到私网地址被拒绝。
- 超过单元格/行数/解压大小限制的 Excel 上传失败，合法小文件成功。
- 普通用户调用生成接口得到 404 或 403，且不会调用上游模型。

**验收命令**：

```powershell
npm --prefix frontend run test
npm --prefix frontend run type-check
mvn -f backend/pom.xml -Dtest=SafeRemoteUrlPolicyTest,ExcelParserServiceTest,GenerationControllerTest test
```

**建议提交**：`fix(security): harden rendering, fetch and file ingestion`

### 任务 4：建设可观察的文档与 Excel 上传流水线

**目的**：让用户明确知道上传是否成功、索引是否完成、失败后如何处理。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java`
- `backend/src/main/java/com/argus/rag/document/service/DocumentPreviewService.java`
- 新增 `backend/src/main/java/com/argus/rag/document/service/IngestionJobService.java`
- 新增 `backend/src/main/java/com/argus/rag/document/model/entity/IngestionJob.java`
- 新增 Flyway 迁移 `backend/src/main/resources/db/migration/V5__ingestion_job_and_document_version.sql`
- `backend/src/main/java/com/argus/rag/excel/service/ExcelParserService.java`
- `frontend/src/views/knowledge/` 下的上传与详情组件
- `frontend/src/views/excel/` 下的上传与详情组件

**数据库设计**：

新增 `ingestion_job`：

```text
id, resource_type, resource_id, status, stage, attempt_count,
error_code, error_message, content_hash, source_filename,
created_by, created_at, started_at, finished_at
```

新增或补齐文档版本字段：

```text
document_version, content_hash, parser_version, indexed_at,
status, failure_code, failure_message
```

**实现要求**：

1. 上传先进入 `VALIDATING`，通过后创建任务；解析、切块、向量化、ES 写入每步更新任务状态。
2. 对同一知识库内相同 `content_hash` 的文件提供“已存在/创建新版本”选择，不静默重复索引。
3. 任务失败保留原始资料与错误码；只允许原上传者、知识库管理员或系统管理员点击重试。
4. 任务幂等：同一任务多次执行不能产生重复 chunk 或重复索引。使用唯一键、版本号或先删后建策略，并写明策略。
5. 文档从 `READY` 变为新版本时，旧版本引用仍可被定位；默认检索仅使用当前 READY 版本。
6. Excel 上传同样显示解析进度、工作表数量、拒绝原因；不得将解析异常吞掉后返回成功。

**先写测试**：

- 正常 PDF/Markdown/DOCX 上传最终为 `READY`，任务有完整阶段记录。
- 解析异常后为 `FAILED`，前端能看到可读错误；授权用户重试后可恢复。
- 同文件重复上传不产生重复 chunk。
- 并发执行同一任务不会生成两份索引。
- 未完成或失败文档不会进入检索。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=DocumentUploadServiceTest,IngestionJobServiceTest,ExcelParserServiceTest test
npm --prefix frontend run test
```

**建议提交**：`feat(ingestion): add observable and idempotent upload jobs`

### 任务 5：建立有权限边界的检索服务

**目的**：先保证“检索到的都是我能看的”，再追求召回率。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/retrieval/` 下的检索服务与 DTO
- `backend/src/main/java/com/argus/rag/document/service/DocumentAccessService.java`
- `backend/src/main/java/com/argus/rag/agent/tool/StructuredDataQueryTool.java`
- `backend/src/main/java/com/argus/rag/agent/` 下的 Agent 工具注册处
- 新增 `backend/src/main/java/com/argus/rag/retrieval/model/RetrievalEvidence.java`
- 新增 `scripts/rag_eval/`

**实现要求**：

1. 检索查询在 SQL/ES 查询阶段就带入群组、角色、状态、版本过滤；不得先召回全部再在内存“尽量过滤”。
2. 将检索结果统一为 `RetrievalEvidence`，至少包含资源类型、资源 ID、文档版本、chunk ID、文本范围、分数、权限快照标识。
3. 混合检索保留现有向量 + 关键词能力，增加可配置的 `topK`、最小分数、重排序阈值、每文档最大 chunk 数，避免一份文档霸占答案。
4. Agent 的结构化查询工具改为显式接收 `excelWorkbookId`；调用 `ExcelAccessService`，不再使用 RAG `DocumentMapper` 或 `DocumentRoleGate` 校验 Excel。
5. 默认不允许 Agent 在无资料证据时编造答案：低于阈值时返回 `NOT_GROUNDED`，引导用户上传资料或换问法。
6. 把检索参数写入配置并提供管理员只读诊断信息，不向普通用户暴露向量、内部提示词或其他文档标题。

**离线评测集**：

在 `scripts/rag_eval/fixtures/` 放入脱敏的 50 至 100 条问答，覆盖制度、项目、财务角色隔离、无答案、同名文档、多版本文档。每条包含期望文档、禁止文档和最低证据分数。

**先写测试**：

- 普通用户检索不到财务资料；财务用户可检索到。
- 文档降权/归档后，检索和历史引用都不可见。
- RAG 文档 ID 与 Excel 工作簿 ID 相同数字时，结构化工具不会误读工作表。
- 低分检索返回 `NOT_GROUNDED`，不生成无引用结论。
- 同一文档的 chunk 数受配置上限约束。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=RetrievalServiceTest,StructuredDataQueryToolTest,DocumentAccessServiceTest test
python scripts/rag_eval/run_eval.py --profile local
```

**建议提交**：`feat(rag): enforce grounded retrieval with typed evidence`

### 任务 6：实现可验证引用与安全的问答历史

**目的**：用户可以复核答案，管理员可以追溯依据，权限变化后不会泄露旧内容。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/qa/` 下的问答生成、持久化、查询服务
- 新增 `backend/src/main/java/com/argus/rag/qa/model/vo/CitationVO.java`
- 新增 Flyway 迁移 `backend/src/main/resources/db/migration/V6__qa_citation_snapshot.sql`
- `frontend/src/views/qa/components/QaMessage.vue`
- `frontend/src/views/assistant/components/AssistantMessage.vue`
- 新增 `frontend/src/components/CitationCard.vue`

**数据设计**：

问答记录保存答案快照；引用单独保存资源 ID、版本号、chunk ID、页码/段落、短摘录、检索分数。不要只保存一段 Markdown 链接。

**实现要求**：

1. 模型输入中的证据使用编号，例如 `[E1]`；输出解析后只能引用本轮证据编号。
2. 后端校验每条引用是否属于本轮检索集，模型伪造的引用一律丢弃并记录诊断日志。
3. `CitationCard` 点击时调用后端授权预览接口；无权限仅显示通用提示，不显示标题、摘录和页码。
4. 问答列表查询应用与实时问答同样的文档角色门槛，修复“历史记录只按作者/群主可见”的逻辑缺口。
5. 用户可以给回答标记“有帮助/无帮助/引用不准确”，反馈绑定用户、问题、引用 ID，供后续评测使用。

**先写测试**：

- 模型返回未在证据集中出现的引用 ID，响应中不包含该引用。
- 用户失去文档权限后，历史 QA 中的引用内容不再返回。
- 一个答案有多处引用时，每张引用卡跳转到正确版本和位置。
- 不同用户无法修改彼此的反馈记录。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=QaCitationServiceTest,QaRecordPersistenceServiceTest test
npm --prefix frontend run test
npm --prefix frontend run build
```

**建议提交**：`feat(qa): add verified citations and access-aware history`

### 任务 7：完善 Excel 版本、审计、导出和回收站闭环

**目的**：让预算表、费用表等核心文件可以安全回退、追溯、导出。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/excel/service/ExcelVersionService.java`
- `backend/src/main/java/com/argus/rag/excel/service/OperationLogService.java`
- `backend/src/main/java/com/argus/rag/excel/service/ExcelExportService.java`
- `backend/src/main/java/com/argus/rag/excel/service/ExcelRecycleBinService.java`
- `backend/src/main/java/com/argus/rag/excel/controller/` 对应控制器
- `frontend/src/views/excel/` 下版本、导出、回收站界面

**实现要求**：

1. 版本创建保存不可变快照、创建人、原因、单元格变更统计、源版本；恢复操作创建一个新版本，而不是覆盖旧版本。
2. 操作日志包含工作簿、工作表、操作人、服务端时间、操作类型、前后差异摘要、关联版本；敏感单元格内容按字段规则脱敏。
3. 导出任务有状态机 `PENDING/RUNNING/SUCCEEDED/FAILED/EXPIRED`，文件有过期时间和所属工作簿；下载必须重新鉴权。
4. 回收站采用软删除保留期（建议 30 天）；恢复需 manage 权限；清理任务只清理过期且无保留标记的资源。
5. 对超大导出使用异步任务和大小上限，避免 HTTP 请求长期占用线程。

**先写测试**：

- 恢复版本后旧版本仍可查询且内容不变。
- 无 manage 权限不能恢复、删除或清空回收站。
- 用户失去 view 权限后无法下载此前创建的导出文件。
- 导出任务失败后显示错误并可由授权用户重试。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=ExcelVersionServiceTest,ExcelExportServiceTest,ExcelRecycleBinServiceTest test
```

**建议提交**：`feat(excel): complete version audit export lifecycle`

### 任务 8：建立受控 Excel 协同编辑模型

**目的**：在不引入复杂 CRDT 的前提下，使多人编辑稳定、可解释、可审计。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/excel/websocket/ExcelSubscribeInterceptor.java`
- `backend/src/main/java/com/argus/rag/excel/websocket/CollabController.java`
- `backend/src/main/java/com/argus/rag/excel/service/CollabService.java`
- `backend/src/main/java/com/argus/rag/excel/model/` 下协同 DTO
- `frontend/src/views/excel/` 下协同客户端逻辑

**实现要求**：

1. 所有 STOMP destination 解析出 `workbookId`，订阅和发送均调用 `ExcelAccessService`；禁止只保护订阅、不保护发送。
2. 每次写单元格带服务器分配的 `revision`；客户端提交基于的 `baseRevision` 落后时返回冲突信息。
3. 第一版采用“单元格级乐观锁 + 服务端最后写入版本号”，不实现 CRDT。冲突时前端给用户“覆盖/刷新/复制为新值”选项。
4. 心跳/光标等临时事件不持久化；真实改单元格才产生审计日志。
5. 连接数、消息频率、单次批量改单元格数要限流，异常用户连接可被断开。

**先写测试**：

- 无 edit 权限用户不能发送单元格写入帧。
- 使用旧 `baseRevision` 写同一单元格得到冲突，不覆盖新值。
- 大批量消息超过上限被拒绝且不产生部分写入。
- 光标事件不会污染操作日志。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=ExcelSubscribeInterceptorTest,CollabServiceTest test
npm --prefix frontend run test
```

**建议提交**：`feat(collab): add revision based spreadsheet collaboration`

### 任务 9：建设可靠任务队列、跨实例协作与并发隔离

**目的**：让多线程处理、Worker 扩容、API 双实例和 Excel 实时协作在 500 人规模下仍具备确定性与可恢复性。

**文件：**

- 修改：`backend/src/main/java/com/argus/rag/common/config/AppThreadPoolConfig.java`
- 修改：`backend/src/main/java/com/argus/rag/common/ratelimit/RateLimitAspect.java`
- 新增：`backend/src/main/java/com/argus/rag/job/model/entity/BackgroundJob.java`
- 新增：`backend/src/main/java/com/argus/rag/job/model/enums/BackgroundJobStatus.java`
- 新增：`backend/src/main/java/com/argus/rag/job/mapper/BackgroundJobMapper.java`
- 新增：`backend/src/main/java/com/argus/rag/job/service/BackgroundJobService.java`
- 新增：`backend/src/main/java/com/argus/rag/job/service/BackgroundJobWorker.java`
- 新增：`backend/src/main/java/com/argus/rag/excel/service/ExcelCollabOutboxService.java`
- 新增：`backend/src/main/java/com/argus/rag/excel/service/ExcelCollabEventPublisher.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/CollabService.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/config/WebSocketConfig.java`
- 修改：`backend/pom.xml`
- 新增：`backend/src/main/resources/db/migration/V8__background_job_collab_outbox.sql`
- 测试：`backend/src/test/java/com/argus/rag/job/service/BackgroundJobServiceTest.java`
- 测试：`backend/src/test/java/com/argus/rag/excel/service/ExcelCollabOutboxServiceTest.java`
- 测试：`backend/src/test/java/com/argus/rag/common/config/AppThreadPoolConfigTest.java`

- [ ] **步骤 1：编写失败的任务领取、租约、幂等和协同 Outbox 测试**

```java
@Test
void onlyOneWorkerCanClaimTheSameQueuedJob() { /* 两个事务竞争同一 job，只有一个返回 CLAIMED */ }

@Test
void expiredLeaseCanBeClaimedByAnotherWorker() { /* lease_until 到期后可接管 */ }

@Test
void sameIdempotencyKeyCreatesOneJobOnly() { /* 唯一键冲突返回既有 job */ }

@Test
void collabEventPublishesOnlyAfterDatabaseTransactionCommits() { /* 回滚时不广播 */ }
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -f backend/pom.xml -Dtest=BackgroundJobServiceTest,ExcelCollabOutboxServiceTest,AppThreadPoolConfigTest test
```

预期：FAIL，缺少任务队列、Outbox 和配置化有界执行器。

- [ ] **步骤 3：实现可靠任务和协同事件接口**

在 `BackgroundJobService` 中实现以下方法：

```java
BackgroundJob enqueue(NewBackgroundJobCommand command);
List<BackgroundJob> claimDueJobs(String workerId, int batchSize, Instant leaseUntil);
void complete(Long jobId, String workerId);
void failOrRetry(Long jobId, String workerId, JobFailure failure);
```

`claimDueJobs` 必须使用 `FOR UPDATE SKIP LOCKED`；`enqueue` 必须以 `idempotency_key` 唯一索引保证幂等。将文档入库、Excel 导出、财务导入从 JVM 内提交改为创建 `BackgroundJob`。在 `CollabService` 事务内落库 `excel_collab_outbox`，由 `ExcelCollabEventPublisher` 在提交后发送至 RabbitMQ STOMP Relay。配置 Redis 分布式限流与 RabbitMQ Broker Relay；本地 profile 可保留内存实现，但 `prod` profile 启动时必须拒绝 Simple Broker 和本地限流。

- [ ] **步骤 4：运行目标测试验证通过**

运行：

```powershell
mvn -f backend/pom.xml -Dtest=BackgroundJobServiceTest,ExcelCollabOutboxServiceTest,AppThreadPoolConfigTest test
```

预期：PASS。

- [ ] **步骤 5：运行并发集成测试**

运行：

```powershell
mvn -f backend/pom.xml -Dtest=BackgroundJobIntegrationTest,CollabServiceConcurrencyTest test
```

预期：两 Worker 竞争任务时只执行一次；两个 API 实例的协同事件均可收到；队列满时返回可识别错误而不由请求线程执行任务。

- [ ] **步骤 6：提交**

```powershell
git add backend/pom.xml backend/src/main/java backend/src/main/resources/db/migration backend/src/test
git commit -m "feat(platform): add durable jobs and distributed collaboration"
```

### 任务 10：建设财务导入校验与期间处理

**目的**：从“能上传表格”提升为“能可靠完成一次月度数据导入”。

**主要改动文件**：

- `backend/src/main/java/com/argus/rag/finance/` 下导入、账本、批次、报表服务
- 新增 `backend/src/main/java/com/argus/rag/finance/service/FinanceImportValidationService.java`
- 新增 `backend/src/main/java/com/argus/rag/finance/model/vo/ImportValidationResult.java`
- 新增 Flyway 迁移 `backend/src/main/resources/db/migration/V7__finance_import_validation_and_period_close.sql`
- `frontend/src/views/finance/` 下导入预览、错误清单、期间页面
- `docs/finance-import-template.md`

**产品规则**：

1. 只支持已发布模板：费用流水、预算明细、工资明细。每种模板有版本和必填字段。
2. 上传后先生成校验预览，不直接入账。校验包括列名、数据类型、日期范围、金额精度、必填值、重复业务键、科目/部门字典、借贷平衡（适用时）。
3. 用户确认后才创建导入批次；导入批次有 `DRAFT/VALIDATED/IMPORTED/REJECTED/REVERSED` 状态。
4. 用业务唯一键 + 文件哈希保证幂等；相同文件重复导入必须提示而非重复生成流水。
5. 期间关闭后禁止常规编辑和导入；只有指定财务管理员能执行“重新打开期间”，且必须填写原因并产生审计记录。
6. 财务数据只能由 `ROLE_FINANCE` 访问，系统管理员没有隐式豁免。

**先写测试**：

- 模板缺列、金额精度错误、日期越期、重复业务键均在预览阶段被标出。
- 相同文件二次确认不产生第二批数据。
- 已关闭期间不能导入或修改。
- 非财务用户无法访问导入预览、错误详情、期间操作。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=FinanceImportValidationServiceTest,FinancePeriodServiceTest test
```

**建议提交**：`feat(finance): add validated and idempotent imports`

### 任务 11：提供可复核的财务分析与异常提醒

**目的**：让财务模块产生业务价值，而不是只存放 Excel 文件。

**主要改动文件**：

- 新增 `backend/src/main/java/com/argus/rag/finance/service/FinanceAnalysisService.java`
- 新增 `backend/src/main/java/com/argus/rag/finance/controller/FinanceAnalysisController.java`
- 新增 `backend/src/main/java/com/argus/rag/finance/model/vo/FinanceMetricVO.java`
- `frontend/src/views/finance/` 下仪表盘与明细抽屉
- 新增 `docs/finance-metric-definitions.md`

**第一版指标**：

- 本期收入、支出、净额，与上期环比。
- 按部门、科目、项目的金额汇总与 Top N。
- 预算 vs 实际差异。
- 异常：金额超过阈值、同供应商短期重复付款、跨期间入账、缺失科目/部门。

**实现要求**：

1. 每个指标都必须可下钻到导入批次和原始行，不允许只显示 AI 结论。
2. 异常规则为可配置阈值，不将“异常”直接作为会计结论；页面显示规则、命中原因和原始记录。
3. AI 财务问答仅能在已经授权的汇总/明细上工作，回答中显示数据期间、筛选条件、查询时间和对应报表链接。
4. 不做自动记账、自动付款、自动报税。

**先写测试**：

- 汇总金额与测试数据的人工计算相符。
- 每条异常可回溯到规则和源数据。
- 非财务用户不能看到任何指标或异常计数。
- AI 问答不传入未授权明细。

**验收命令**：

```powershell
mvn -f backend/pom.xml -Dtest=FinanceAnalysisServiceTest,FinanceAnalysisControllerTest test
npm --prefix frontend run test
```

**建议提交**：`feat(finance): add auditable financial analysis`

### 任务 12：完善运维、安全配置与备份恢复

**目的**：让试点系统能被稳定运行，而非只能在开发机演示。

**主要改动文件**：

- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/main/java/com/argus/rag/common/config/AppThreadPoolConfig.java`
- `backend/src/main/java/com/argus/rag/common/ratelimit/RateLimitAspect.java`
- 新增 `docs/operations/deployment.md`
- 新增 `docs/operations/backup-restore.md`
- 新增 `docs/operations/security-baseline.md`
- 新增 `docs/operations/capacity-runbook.md`
- 新增 `scripts/health-check.ps1`

**实现要求**：

1. 区分 dev/test/prod 配置：生产环境关闭调试、默认关闭网页搜索和生成接口、强制密钥来自环境变量。
2. 增加健康检查：数据库、ES、向量模型、对象存储、异步任务积压；健康接口不泄露连接串或密钥。
3. 接入结构化日志并为请求、上传任务、RAG 问答、导出任务生成关联 ID。
4. 增加上传、问答、导出、WebSocket 消息、Agent 工具调用的 Redis 用户级限流和并发限制；不得依赖单机内存 Map。
5. 制定 PostgreSQL、对象存储、ES 索引的备份与恢复演练流程；明确恢复顺序与 RPO/RTO。
6. 生产部署使用两台 API、至少一台 Worker、Redis、RabbitMQ、PostgreSQL、ES 和对象存储；使用非 root 用户、TLS、受限 CORS 域名、强 Cookie 配置、最小数据库权限。
7. 暴露并告警以下指标：HTTP/SSE 活跃请求、WebSocket 连接数、每个 Executor 的活跃线程/队列/拒绝次数、Background Job 各状态数量与最久等待时间、Hikari 连接池使用率、ES/LLM 调用延迟与错误率、Outbox 未发送数量、缓存命中率。
8. 设置初始告警：任务最久等待超过 15 分钟、队列拒绝次数连续 5 分钟大于 0、连接池使用率超过 80%、协同 Outbox 积压超过 1,000、依赖错误率超过 5%。

**先写测试/检查**：

- 生产 profile 缺少必要密钥时启动失败，而非使用默认秘密。
- 健康检查在一个依赖不可用时返回降级状态且无敏感信息。
- 限流超过阈值后返回标准错误码，正常用户不受影响。
- 按恢复文档在空环境恢复一份脱敏备份并验证检索可用。
- 停止任意一个 API 实例后，存量 WebSocket 客户端在重连后仍可收到另一实例的协同增量。
- 模拟 LLM/ES 超时和 RabbitMQ 不可用，验证 circuit breaker 打开、请求快速失败、任务不丢失。

**验收命令**：

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend run type-check
npm --prefix frontend run build
```

**建议提交**：`feat(ops): add production safeguards and runbooks`

### 任务 13：试点验收、容量压测与质量门禁

**目的**：用真实但脱敏的业务任务判断产品是否可用。

**新增文件**：

- `docs/acceptance/pilot-acceptance.md`
- `docs/acceptance/rag-evaluation-report-template.md`
- `docs/acceptance/security-regression-checklist.md`
- `scripts/rag_eval/fixtures/`
- 新增 `scripts/load/k6-knowledge-read.js`
- 新增 `scripts/load/k6-rag-stream.js`
- 新增 `scripts/load/k6-excel-collab.js`
- 新增 `docs/acceptance/capacity-test-report-template.md`

**试点验收场景**：

1. 上传三份制度（含修订版本），普通员工提问并从正确版本引用。
2. 普通员工尝试猜测财务文档、工作簿、工作表、版本、导出任务 ID，全部被拒绝。
3. 两名财务人员同时编辑预算表，产生可查看冲突和准确审计记录。
4. 导入一份含 10 条错误的费用流水，预览准确定位；修正后只导入一次。
5. 关闭期间后尝试修改，系统拒绝；重新打开需要理由并留下审计。
6. 进行 XSS、私网 URL、压缩炸弹、超大批量协同消息回归。
7. 在两台 API 和一台 Worker 的预发布环境，执行 30 分钟的 100 虚拟用户混合压测：70% 知识库列表/预览/下载、20% 检索与问答、10% Excel 工作簿访问；同时维持 20 个 WebSocket 协作连接和 5 个后台上传/导入任务。
8. 在压测中滚动重启一台 API；验证正在编辑的用户重连后可继续编辑，任务不重复，未授权资源不因缓存或跨节点消息泄露。

**量化门槛**：

| 指标 | 第一阶段门槛 |
| --- | --- |
| 权限反向测试 | 100% 通过，无已知高危 IDOR |
| RAG 有依据回答率 | 脱敏评测集 >= 85% |
| 引用正确率 | 抽检 >= 90% |
| 无答案时拒答正确率 | >= 90% |
| 正常文档上传成功率 | >= 98% |
| 财务导入重复入库 | 0 |
| 100 虚拟用户 30 分钟 HTTP 错误率（不含限流） | < 1% |
| 知识库读接口 P95 | < 500 ms |
| 已命中检索（不含 LLM 生成）P95 | < 2 s |
| 首个流式 Token P95（使用稳定测试模型） | < 5 s |
| 跨 API Excel 协同事件 P95 | < 500 ms |
| Background Job 重复执行造成的重复数据 | 0 |
| 连接池/Executor 在稳态的持续饱和 | 0 次超过 80% 持续 5 分钟 |
| P1/P0 安全问题 | 0 个未关闭 |

**建议提交**：`test: add pilot acceptance and security regression suite`

## 7. 数据库迁移规则

1. 每个逻辑主题只新增一个有序 Flyway 文件，禁止修改已在线上执行的 `V1` 至 `V4`。
2. 对大表新增索引采用数据库支持的在线/并发方式；上线前在备份数据上评估时间。
3. 所有权限关联字段和查询字段必须有索引，例如 `excel_sheet(document_id, sheet_id)`、版本的 `(document_id, id)`、任务的 `(resource_type, resource_id, status)`。
4. 删除字段采用“先停止写入 -> 迁移数据 -> 双读校验 -> 后续版本删除”的两阶段方式。
5. 上线前备份数据库和对象存储；迁移必须可回滚或有补偿脚本。

## 8. API 与前端约定

### 统一错误响应

```json
{
  "code": "EXCEL_SHEET_NOT_IN_WORKBOOK",
  "message": "当前工作表不属于指定工作簿",
  "traceId": "..."
}
```

推荐错误码：

- `RESOURCE_FORBIDDEN`
- `FINANCE_ROLE_REQUIRED`
- `EXCEL_SHEET_NOT_IN_WORKBOOK`
- `RESOURCE_ARCHIVED`
- `INGESTION_FAILED`
- `FILE_LIMIT_EXCEEDED`
- `RETRIEVAL_NOT_GROUNDED`
- `CITATION_ACCESS_REVOKED`
- `PERIOD_CLOSED`
- `COLLAB_REVISION_CONFLICT`

前端不得根据英文 message 判断业务逻辑，只按 `code` 映射用户提示。

### 交互原则

- 删除、清空回收站、恢复版本、重新打开财务期间均需要明确二次确认，并展示影响范围。
- 上传页面展示文件大小、类型、目标知识库/工作簿、权限范围、处理状态与失败原因。
- 回答页面把“答案”“引用”“无依据提示”分区呈现；不得把模型输出伪装成系统事实。
- 财务页面永远显示数据期间、筛选条件、更新时间和可下钻入口。

## 9. 测试策略

### 后端

- 单元测试：权限服务、状态机、文件限制、引用校验、财务计算。
- MVC/集成测试：每个资源接口至少有 owner、reader、outsider、finance-user、admin 五类身份覆盖。
- 数据库集成测试：Flyway 从空库迁移；关键索引和约束存在。
- 安全回归测试：IDOR、越权 WebSocket、XSS、SSRF、上传超限、限流。

### 前端

- Vitest：Markdown 净化、引用卡片状态、上传状态、冲突处理、错误码映射。
- 类型检查和生产构建：每个里程碑必须通过。
- 不依赖截图验收；以 DOM、接口响应、状态变化和自动化断言作为证据。

### 最低持续集成门禁

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend run test
npm --prefix frontend run type-check
npm --prefix frontend run build
```

如果当前 CI 尚未配置，先把上述命令加入 GitHub Actions/GitLab CI 的一个基础流水线；测试失败不得合并。

## 10. 执行优先级与取舍

### 必须先做（不满足不可试点）

1. 任务 1 至任务 3：权限、完整性、XSS/SSRF/上传防护。
2. 任务 4：上传状态、失败重试、幂等。
3. 任务 5 至任务 6：检索权限与可信引用。
4. 任务 7：Excel 版本/导出/回收站权限闭环。

### 试点前应完成

1. 任务 8：协同冲突模型。
2. 任务 9：可靠任务队列、跨实例广播、分布式限流和有界多线程。
3. 任务 10：财务模板校验与期间关闭。
4. 任务 12：备份、监控、限流、部署文档。
5. 任务 13：100 虚拟用户容量压测与故障演练。

### 可在试点后迭代

1. 任务 11：更丰富的财务异常规则和 AI 分析。
2. 企业 SSO、LDAP、消息通知、完整审批流。
3. 多租户、外部协作、跨组织分享。
4. 多模型路由、复杂 Agent 工作流、互联网搜索。

## 11. 给执行 Agent 的工作指令

将以下文本作为 Claude Code 或 ZCode 的首条任务提示：

```text
请在 F:\\ChatGPT\\argus-loom 中执行 docs/superpowers/plans/2026-10-10-argus-loom-core-product-plan.md。

约束：
1. 先完成任务 1、2、3；随后优先完成任务 9 的任务队列、跨实例协同和线程隔离基础设施。任何 P1 权限或安全问题未修复前，不开发业务新功能。
2. 每个任务遵循“先写失败测试 -> 最小实现 -> 运行指定测试 -> 全量回归 -> 提交”的顺序。
3. 不接入 FastGPT，不拆微服务，不修改既有 Flyway 迁移文件；只新增迁移。
4. 不读取或提交 .env、密钥、用户本地配置；不回滚不属于本任务的已有修改。
5. 每完成一个任务提交一次，提交信息使用计划中建议的 Conventional Commit。
6. 如果实现与现有代码结构冲突，优先保持模块化单体与显式资源类型，记录取舍后再继续。
7. 每次提交前运行 backend Maven 测试、frontend 测试、type-check 和 build；若命令因既有环境问题失败，保留输出并说明，不伪称通过。
```

## 12. 预期目录演进

```text
backend/src/main/java/com/argus/rag/
  document/
    service/DocumentAccessService.java
    service/IngestionJobService.java
  job/
    service/BackgroundJobService.java
    service/BackgroundJobWorker.java
  excel/
    service/ExcelAccessService.java
    service/ExcelCollabOutboxService.java
  finance/
    service/FinanceImportValidationService.java
    service/FinanceAnalysisService.java
  qa/
    model/vo/CitationVO.java
  retrieval/
    model/RetrievalEvidence.java
  security/
    SafeRemoteUrlPolicy.java

backend/src/main/resources/db/migration/
  V5__ingestion_job_and_document_version.sql
  V6__qa_citation_snapshot.sql
  V7__finance_import_validation_and_period_close.sql
  V8__background_job_collab_outbox.sql

frontend/src/
  components/CitationCard.vue
  utils/safeMarkdown.ts
  utils/safeMarkdown.test.ts

docs/
  acceptance/
  finance-import-template.md
  finance-metric-definitions.md
  operations/
scripts/
  rag_eval/
  load/
```

## 13. 完成定义

本计划不是“代码写完”即结束。只有同时满足以下条件，才可以称 Argus Loom 已具备内部试点价值：

1. 关键资源接口、WebSocket、Agent 工具均通过越权反向测试。
2. 用户可以从上传到检索到引用核验完成闭环，失败任务可见且可恢复。
3. Excel 文件可安全协作、回退、导出、恢复，且审计记录可信。
4. 财务数据按模板校验后可幂等导入、可按期间分析、可追溯源数据。
5. 生产配置、备份恢复、限流、监控和试点手册齐备。
6. 试点评测与容量压测达到第 13 任务定义的量化门槛，并由真实业务用户完成验收。

