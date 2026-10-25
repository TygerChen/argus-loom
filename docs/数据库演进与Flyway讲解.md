# 数据库演进与 Flyway 讲解（面试背诵版）

> 依据：`backend/src/main/resources/db/migration/` 全部真实 SQL、`com.argus.rag` 下实体/Mapper、
> `ai-handoff/PROJECT_TRUTH.md`（2026-10-23 基线）、`docs/口径勘误表-面试数字校准.md`（2026-10-25）。
> 所有数字均出自代码，口径以勘误表为准。第一人称，可直接背。

---

## 0. 开场 30 秒（总览）

"Argus 的数据库是 PostgreSQL + pgvector + Elasticsearch 三件套，结构演进全部由 Flyway 管理，
目前 7 个迁移文件：V1 到 V8，其中 V6 不存在——Flyway 允许版本号不连续，缺号会直接跳过。
演进主线很清晰：V1 固化核心 RAG 底座，V2 收编 Excel 模块，V3-V5 按业务补财务角色、
文档权限门槛和导出过期，V7 加财务关账与导入幂等，V8 用两张表补上可靠任务队列和协同
Outbox。每条迁移只做一类事，全部向后兼容，从不回头改。"

版本速查表：

| 版本 | 文件 | 一句话 |
| --- | --- | --- |
| V1 | init_core_schema | 核心库全量幂等建表：用户/群组/文档/切片/向量/QA/助手 |
| V2 | excel_module_schema | Excel 模块 20+ 张表收编进 Flyway（含 chunk+delta、版本、协同、财务批次） |
| V3 | finance_role_payroll_report | 财务角色隔离标记 + 考勤/薪酬/报表 |
| V4 | document_required_role | 文档级角色门槛（检索层防存在性泄露） |
| V5 | excel_export_expire_and_job_meta | 导出产物 24 小时过期 |
| V6 | **不存在** | 缺号是当前事实，不补号、不改历史 |
| V7 | finance_period_and_import_idempotency | 财务期间关账 + 导入文件哈希幂等 |
| V8 | background_job_collab_outbox | 可靠任务队列表 + 协同事件 Outbox |

配置（`application.yml`）：`spring.flyway.enabled=true`，`baseline-on-migrate: true`，
`baseline-version: 0`，locations 为 `classpath:db/migration`；依赖 `flyway-core` +
`flyway-database-postgresql`。

---

## 1. Flyway 逐条讲解

### V1 —— 核心库全量固化

**做了什么**：一份 43KB 的幂等 DDL，`CREATE TABLE IF NOT EXISTS` 建了 19 张表：
users、user_refresh_tokens、groups、group_memberships、group_invitations、
group_join_requests、documents、document_upload_sessions / document_upload_chunks
（分片上传）、document_chunks（切片）、ingestion_jobs（观测）、vector_store（pgvector，
`VECTOR(512)` + HNSW `m=16, ef_construction=200`）、assistant_sessions / messages /
contexts（会话记忆）、llm_usage_records、qa_records / qa_record_citations、user_memories。
文件头写明"来源：repository sql/schema.sql"，且内部还有
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（assistant_sessions.archived、
qa_records.user_rating）——说明它不是项目第一天的样子，而是把一段已经演进过的
schema 一次性冻结成基线。

**为什么这时做**：项目先快速迭代出了可用 schema，引入 Flyway 时第一个决定就是
"以当前真实结构为 V1 基线"，而不是假装从头重放历史。`baseline-on-migrate: true`
配合 `baseline-version: 0`，保证已有数据的库不打翻重建。

**30 秒话术**："V1 是基线冻结：我把已经稳定运行的核心 schema 整体固化为第一个迁移，
全部写成幂等形式。这里有个取舍——Flyway 社区不推荐大 V1，但我们当时库已在
迭代期，重放式拆分历史成本高、收益低，所以选择一次性收编，之后每步变更严格
增量。V1 里连 pgvector 的 HNSW 索引参数都在，512 维对应本地 bge-small-zh 模型。"

### V2 —— Excel 模块收编

**做了什么**：文件头写"来源：ExcelSchemaInitializer SQL 清单"。建了 20 张表：
excel_document / excel_sheet / excel_sheet_chunk（块表）、excel_operation_log、
excel_document_snapshot、excel_document_permission（ACL，`(document_id,user_id)` 唯一）、
excel_document_share、回收站字段（deleted_by/deleted_at）、版本三件套
（excel_document_version → version_sheet → version_chunk）、excel_cell_history、
excel_edit_stack（撤销栈）、excel_export_task、excel_audit_log、excel_collab_log、
excel_collab_room（session 唯一）、excel_system_config，以及两张财务表
finance_import_batch（`(book_key,batch_version)` 唯一）和 finance_ledger_record
（金额全部 `DECIMAL(30,6)`，附 raw_json/formula_text 原始痕迹）。
重点：`excel_sheet_chunk_delta` 在这里就诞生了，唯一键
`uk_excel_sheet_chunk_delta_key (sheet_id, chunk_index, cell_row, cell_col)`。

**为什么这时做**：Excel 模块此前靠代码启动时建表（ExcelSchemaInitializer），这有
两个问题——多实例并发建表竞态、结构变更没有版本记录。V2 把代码建表逻辑退役
（该类现已删除），统一进 Flyway。"代码建表"到"迁移建表"是项目数据库治理的分水岭。

**30 秒话术**："V2 是一次'收编'：Excel 模块原来由 ExcelSchemaInitializer 在启动时
建表，我把它整体迁入 Flyway 并删掉了那个类。之后所有环境——包括已有库——结构
变更都有版本可查。这一版把 chunk+delta 双表、版本三件套、协同日志一次固化，
财务的批次表也顺带进来了，因为它挂在 Excel 导入链路上。"

### V3 —— 财务角色与薪酬

**做了什么**：三件事。① `excel_document` 加 `finance_required BOOLEAN NOT NULL
DEFAULT FALSE`，并跟了一条 `UPDATE ... WHERE EXISTS (finance_import_batch)` 数据
回填——**全部迁移里唯一的 DML**，把存量财务导入文档打上标记；配套索引
`(finance_required, status)`。② 建 employee_attendance（`UNIQUE(user_id, work_date)`）
和 employee_payroll（`UNIQUE(user_id, payroll_month)`，14 项薪资科目全部
`DECIMAL(14,2)`）。③ 建 finance_report / finance_report_file（AI 财报快照 + 产物）。

**为什么这时做**：财务模块引入即带来"普通员工不可见财务 Excel、只能看自己薪资行"
的隔离需求。隔离逻辑放在查询层，但需要数据支撑位：finance_required 标记 +
唯一约束防脏数据（一人一月一条薪资、一天一条考勤）。

**30 秒话术**："V3 是业务驱动的典型：引入财务模块当天就需要隔离。加列带默认值
保证旧代码无感知，存量数据用一条 EXISTS 子查询回填，这是唯一带 DML 的迁移。
考勤和薪酬表用 (user, 日期/月份) 唯一约束把'重复导入'挡在数据库层。"

### V4 —— 文档角色门槛（最小的一条，最讲得清的一条）

**做了什么**：两个语句。`documents` 加 `required_role VARCHAR(16) NOT NULL DEFAULT
'USER'`；建联合索引 `idx_documents_group_role_status (group_id, required_role,
status, deleted)`。文件头注释直接写了设计意图：角色优先级 USER < FINANCE < ADMIN，
未达门槛的文档对用户"不存在"，与"知识库内无此信息"返回一致，杜绝通过
UNAUTHORIZED/NOT_FOUND 差异探测受限文档的存在性。

**为什么这时做**：RAG 检索层引入 `DocumentRoleGate`，需要检索主查询按
group_id + required_role + 可读状态过滤，索引就是为这条主查询量身定的。

**30 秒话术**："V4 只有两个语句，但它是安全设计的浓缩：一列 + 一个索引。文档级
角色门槛做在检索层而不是接口层，权限不足返回空集而不是 403——防存在性泄露。
fail-closed 表现为与'查无此文'同呈现。这列的过滤由 DocumentRoleGate 在查询规划
前和双路召回后各调一次，索引列序就是过滤列序。"

### V5 —— 导出产物过期

**做了什么**：`excel_export_task` 加 `expire_at TIMESTAMP`（NULL=历史任务不限期），
索引 `(status, expire_at)` 支撑清扫扫描。注释写明语义：成功产物默认保留 24 小时，
超时被清扫为 EXPIRED 且下载被拒；失败任务重试=新建任务。

**为什么这时做**：导出闭环补齐——产物文件不能无限期占用对象存储和下载 URL，
需要"过期-清扫-拒下载"的完整生命周期，字段是这整套机制的锚点。

**30 秒话术**："V5 是典型的'小迁移大语义'：一列加一索引，支撑导出产物 24 小时
生命周期。索引列序 (status, expire_at) 就是清扫查询的形状：按状态扫到期行。
顺带一个名实观察：V5 文件名带 job_meta 但内容只有 expire_at，任务元数据最终
落在 V8 的 background_job——迁移以实际落地内容为准，不按文件名预支。"

### V6 —— 不存在

**事实**：migration 目录里没有 V6 文件。`ai-handoff/SOURCE_AND_DOC_INDEX.md` 明确
记录："`V6` 不存在是当前事实，不要擅自补号或修改历史。"

**怎么讲**："Flyway 按文件名里的版本号排序执行，不要求连续，缺号直接跳过，所以
V6 大概率是被规划后又合并/放弃了。纪律上我们从不补号——补一个 V6 等于在时间线
中间插入新语句，已执行环境会立刻执行它，而语义上它应该属于最新状态。缺号跳过正是 Flyway 官方支持的行为。"（若被追问
"你怎么知道"：答"仓库和交接文档都是这么记录的，我核对过 migration 目录的
文件清单，V1/V2/V3/V4/V5/V7/V8 七个文件一个不少"。）

### V7 —— 财务关账与导入幂等

**做了什么**：① 建 finance_period，`UNIQUE(period_month)`，字段覆盖关账/重开
双向审计（closed_by/closed_reason/closed_at + reopened_by/reopen_reason/reopened_at），
注释："CLOSED 状态下该月流水禁止导入与编辑，重开必须填写原因"。②
`finance_import_batch` 加 `file_hash VARCHAR(128)` + 单列索引，注释："同账本重复
文件导入会被拒绝"。

**为什么这时做**：财务上线后暴露两个真实风险——月末关账后仍有迟到的导入请求
改历史；同一个文件被重复拖入产生重复台账。关账是流程闸门，file_hash 是幂等闸门，
都落在数据库层而不是应用记忆。

**30 秒话术**："V7 是两个防呆：期间关账表用唯一月份约束做闸门，关账/重开都要留
审计字段，重开必须填原因；导入批次表加 file_hash，同账本同哈希直接拒。这和上传
秒传查重、background_job 幂等键是同一思路的三次复用——入口都要假设调用方重复敲门。"

### V8 —— 队列表与 Outbox（最"分布式"的一条）

**做了什么**：① `background_job`：15 个字段，`UNIQUE(idempotency_key)`（宽度
`VARCHAR(191)`，行业惯用的"utf8mb4 下 767 字节索引限制安全宽度"，留了兼容空间）+
`idx_background_job_claim (status, next_run_at)`；注释写明机制："SKIP LOCKED 认领、租约保护、1/5/15 分钟
退避、DEAD_LETTER 死信"。② `excel_collab_outbox`：事件表，"事务内落库 PENDING，
提交后由发布器广播并标记 SENT，失败可重发"，索引 `(status, id)` 认领 + 
document_id 查询。

**为什么这时做**：文档 ETL 原来靠事件监听异步执行（已删除的
DocumentIngestionRequestedEvent 链路），没有持久化队列就没有崩溃恢复和重试语义；
协同广播原来发后即忘，进程崩溃就丢事件。V8 把两条链路都改成"先落库"。

**30 秒话术**："V8 是用两张表补分布式语义：background_job 是 SKIP LOCKED 租约队列，
PostgreSQL 一张表顶一个轻量 MQ；outbox 解决'事务提交了但广播失败'的经典窗口。
两者共同的哲学是：先持久化，再传播。"

---

## 2. 核心表设计故事

### 2.1 documents 与 document_chunks —— 冗余的正确用法

**documents**（V1）：元数据一行为主——group_id、uploader_user_id、file_name、
file_ext、content_type、file_size、file_hash（SHA-256）、storage_bucket /
storage_object_key（物理位置与元数据分离，文件在 MinIO）、status 状态机
UPLOADED→PROCESSING→READY|FAILED、deleted 软删、required_role 门槛（V4）、
failure_reason、preview_text。设计取舍：

- **软删**（deleted 标记）而不是物理删：文档被 QA 引用快照指向，物理删会悬空引用；
  删除时同步清理向量与 ES 索引，但元数据留档。
- **秒传查重用部分索引**：`idx_documents_group_hash (group_id, file_hash) WHERE
  status='READY' AND deleted=FALSE`——只在"有效文档"上查重，半成品和已删文档
  不占索引，这是 PostgreSQL 部分索引的教科书用法。

**document_chunks**（V1）：向量检索单元。chunk_index、chunk_text、chunk_summary、
char_start/char_end（回原文定位）、metadata_json。两个关键取舍：

- **冗余 group_id**（列注释明写"冗余，加速查询"）：检索 SQL 全部以
  `chunks.group_id = ? AND chunks.document_id = ?` 开头（见
  DocumentChunkMapper.xml 的 selectReadyActiveChunksByIds / selectQaReadyChunksByIds），
  索引 `idx_chunk_group_document (group_id, document_id, chunk_index)` 三列连击：
  等值定位文档 + 范围取邻居（前后各 1 个 chunk 的邻居扩展）。不冗余就得先查
  documents 拿 group_id 再查 chunks，多一跳。代价是文档不可能换群组——业务上
  成立，这是"冗余的前提是 invariant"的活例子。
- **不冗余 file_name 到 chunk 表**：文件重命名是真实会发生的操作，冗余 fileName
  到几十万行 chunk 意味着一次 rename 要更新全表。所以检索路径用 join documents
  取 file_name，真正需要固化文件名的是**引用快照**（qa_record_citations.file_name，
  见 2.5）。冗余与否，看"被冗余的值会不会变、变了要同步多少行"。

### 2.2 background_job —— 一张表的队列语义

四个字段组各支撑一个分布式问题（`BackgroundJobService` 注释是自带的面试答案）：

- **idempotency_key + 唯一索引 = 幂等入队**。enqueue 三分支：QUEUED/RUNNING 复用
  （防重复点击/事件重放）、SUCCEEDED 复用（文档没变不该重跑 ETL）、DEAD_LETTER
  条件复活——`UPDATE ... WHERE status='DEAD_LETTER'` 的状态守卫保证并发下只有
  一行生效，防双复活。上传链路幂等键格式 `INGEST:{documentId}:{retryGeneration}`，
  手动重试用新 generation（时间戳）开新任务行，不复用历史死信。
- **status + next_run_at + SKIP LOCKED = 原子领取**。selectDueJobs：
  `WHERE (status='QUEUED' AND next_run_at<=now) OR (status='RUNNING' AND
  lease_until<=now) ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED`——被别的 Worker
  锁住的行直接跳过，不阻塞；再逐条条件 UPDATE（QUEUED→RUNNING，或接管租约过期
  的 RUNNING）认领，影响行数=1 才算抢到。
- **lease_owner / lease_until = 崩溃自愈**。认领时写 5 分钟租约；complete 的
  UPDATE 带 `WHERE lease_owner = #{workerId}`，只有租约持有者能置 SUCCEEDED。
  Worker 挂了租约到期，任务回到"可领取"，别的实例自动接管。代价是超长任务可能
  被双执行，所以整体是 at-least-once，业务层必须幂等。
- **attempt_count / max_attempts = 有界重试**。claim SQL 里 `attempt_count =
  attempt_count + 1` 领取即预增。**口径提醒（勘误表第 1 条）**：退避数组声明
  {1,5,15} 分钟，但因预增，第一次失败时 attempts 已是 1，实际生效 **5/15 两档**，
  1 分钟档是不可达的死代码（off-by-one，一行可修）。耗尽进 DEAD_LETTER，
  next_run_at 置 NULL 不再调度，错误码/信息截断存档（64/512，与列宽一致）。

运行参数（PROJECT_TRUTH）：Worker `@Scheduled` fixedDelay 15 秒（可配
`argus.job.poll-interval-ms`），每轮最多 10 条，租约 5 分钟，maxAttempts=3。
**另一个必背口径（勘误表第 2 条）**：业务性失败（坏 PDF）被 ETL 内层 Spring Retry
（2s/4s/8s×3）+ @Recover 拦下吞掉，终态是"文档 FAILED + 任务 SUCCEEDED 并存"，
死信只兜执行层异常（Worker 崩溃、MinIO 抖动）；实测坏 PDF 的 dead_letter 指标
全程 0。Worker 目前硬编码只认 INGEST_DOCUMENT，未知类型转死信
UNKNOWN_JOB_TYPE；Excel 导出仍走专用 excelExportExecutor，不在此队列。

### 2.3 excel_sheet_chunk / excel_sheet_chunk_delta —— LSM 思想的两张表

**物理设计**（V2）：chunk 表一行存一块 `celldata_json`——按
`ExcelParserService.CHUNK_SIZE = 1000` 行切块（解析切块和在线编辑定位
`chunkIndex = row / CHUNK_SIZE` 用同一常量，注释警告"改了会牵连存量数据"），
单块 JSON 几百 KB 以内可按需加载；行号范围 row_start/row_end；冗余 document_id
"方便按文档删除"（注释原文）。delta 表一行存一个单元格的最新值，唯一键
`(sheet_id, chunk_index, cell_row, cell_col)` ——**唯一索引同时是约束、是 upsert
定位键、也是读路径的最左前缀**。

**读写协议**（ExcelSheetService / SheetChunkMerger）：

- 写：编辑从不重写块 JSON，只 upsert 一条 delta（同格只保留最新值，靠唯一键）；
  写后立刻失效 RedisChunkCacheService 缓存；每次写完 O(1) 计数检查，增量满
  **500 条**触发压实。
- 读：块 JSON + 该块 delta 交给 SheetChunkMerger.merge 纯函数，以 `"r_c"` 为键
  叠加，`"{}"` / null 表示删格。
- 压实 compactSheetDeltas：事务内把全部 delta 合并回块基数、删光 delta、失效缓存
  ——"改基数 + 删增量"要么都成要么都不成。这就是 LSM-Tree / Redis AOF 重写的
  思想：顺序追加 + 周期性压实，用一次重写换之后的干净读。

**为什么这么拆**：单格编辑高频、整块重写低频。每次编辑重写 1000 行 JSON 是严重
写放大且放大并发冲突面；只追加 delta 写入成本接近零，代价是读要合并、增量表
会涨——500 条阈值就是这个代价的上界。

**纪律（AGENTS.md 红线）**：改这两张表写路径必须同步处理 RedisChunkCacheService
缓存失效。注意 RedisChunkCacheService 是**进程内有界 LRU + TTL 缓存**，项目没有
真实 Redis 依赖，别在面试里说错。

### 2.4 user_refresh_tokens —— 服务端可吊销的支撑

**字段设计**（V1）：token_id（UUID 去横线，可查）、token_hash（**BCrypt 哈希，
库里永远没有明文 token**）、expires_at、revoked_at（NULL=未吊销）。
索引两个：token_id 单列（刷新请求进来先按 id 定位）；`(user_id, revoked_at,
expires_at)` 三列（查"用户的有效 token"）。

**吊销怎么落地**（UserRefreshTokenMapper）：原子条件更新
`update user_refresh_tokens set revoked_at=#{revokedAt} where id=#{id} and
revoked_at is null`——返回影响行数，0 行即检测到并发重放。刷新轮换：旧 token
吊销 + 发新 token 一步完成；登出/检测到重放时把该用户 token 全部吊销。

**为什么这张表存在**：纯 JWT 无状态、不可吊销，泄露只能等过期。把 refresh token
做成数据库行，就拿到了"服务端说它死它就死"的能力；access token 仍短期无状态，
兼顾性能与可控性。**诚实边界（勘误表第 7 条，主动讲）**：refresh 重放兜底吊销
随事务回滚存在极端窗口；DISABLED 账号报错与"不存在"可区分。能自己讲出瑕疵
是加分项。

### 2.5 qa_records / qa_record_citations —— 快照而不是外键

**qa_records**（V1）：一次问答一行——question/answer/answered/reason_code/
evidence_level/citation_count + token 用量（prompt/completion/total/is_estimated）
+ latency/model/endpoint/success/error_message，V1 内补的 user_rating
（THUMBS_UP/DOWN）。索引五个，覆盖四个查询维度：`(user_id, created_at DESC)`、
`(group_id, created_at DESC)`、`(group_id, user_id, created_at DESC)`、
`(answered, created_at DESC)`。

**qa_record_citations**（V1）：引用快照。字段是 document_id / chunk_id /
chunk_index / start/end_chunk_index / **file_name** / score 三件套
（vector/keyword/hybrid）/ **snippet**，`ON DELETE CASCADE` 挂在 qa_records 下。

**核心取舍——引用为什么不只存 ID**：回答发生那一刻的证据（文件名、切片原文、
得分）必须固化。文档会改名、chunk 会被删掉重切（ETL 幂等就是"先删后插"），
如果只存外键，历史回答的引用会悬空或变义。file_name/snippet 是**时间点快照**，
与 2.1 里"chunk 表不冗余 fileName"正好互补：热路径不冗余可变值，审计路径冗余
不可变快照。`start/end_chunk_index` 记录合并证据片段的原始范围，供溯源核对。

### 2.6 flyway_schema_history —— Flyway 自己怎么记账

面试官问"Flyway 怎么知道执行到哪了"，答：Flyway 在目标库里自动建
`flyway_schema_history` 表，每个迁移一行：installed_rank（执行顺序）、version、
description、type、script（文件名）、**checksum（脚本内容的校验和）**、
installed_by、installed_at、execution_time、success。启动时做两件事：

1. 对比文件清单和已记录行，找出 pending 迁移按版本号升序执行，成功则记一行
   success=true；
2. 对已执行过的脚本重算 checksum，**与历史记录不一致就 validation error，应用
   拒绝启动**——这就是"迁移只向前、不可改"的物理保证，不靠自觉，靠启动失败。

细节加分：PostgreSQL DDL 可事务化，脚本中途失败整体回滚，但 history 里可能留下
一条 success=false 的失败行，需要人工处理（`flyway repair`）后才能重试；
`baseline-on-migrate: true` 让 Flyway 首次接管已有库时先写 baseline 行而不是从
零重放，这就是我们 V1 能"冻结现状"的机制基础。

---

## 3. "迁移只向前"纪律

**为什么 V1-V8 不可改**：任何已执行环境（同事本地、测试、生产）的
flyway_schema_history 都记着旧 checksum；改一行 SQL，所有这些环境下次启动直接
validation error。团队纪律写成 AGENTS.md 硬规则："不修改已经执行过的 Flyway 迁移；
数据库变更新增 V9 及后续版本。"

**向后兼容写法（加列不删列）**：

- 加列一律 `ADD COLUMN IF NOT EXISTS ... NOT NULL DEFAULT ...`（V3 的
  finance_required、V4 的 required_role、V5 的 expire_at、V7 的 file_hash 全是）。
  PG 11+ 带常量默认值不加列不重写表，秒级完成；旧代码不感知新列，回滚旧版本
  代码照样跑。
- 不删列、不改列名和类型。要"删"一列，路径是：加新列 → 双写 → 迁数据 → 切读 →
  停写旧列 → （很久以后）出专门的清理迁移。本项目尚未走到需要删列的一步。
- 涉及存量数据的，迁移内带幂等回填（V3 的 EXISTS UPDATE），重放安全。
- V1/V2 的 `IF NOT EXISTS` 幂等风格是历史包的一次性特权，V3 之后的新迁移不再
  需要，但条件更新/唯一索引这些幂等锚点一直延续。

**回滚策略：代码回滚，库不回滚**。数据库永远只向前；应用回滚到旧版本代码
之所以能跑，正是因为迁移是向后兼容的（新列有默认值、旧列没动、没有破坏性
语句）。不使用 Flyway undo——undo 脚本无法被校验为正向迁移的精确逆操作，
社区版也基本不支持，靠它做回滚是假安全感。真出了坏迁移，处置是：修数据 +
新迁移补偿，而不是改历史。

**加一个字段的完整流程（背这个）**：确认没有已执行迁移可改 → 新建
`V9__xxx.sql` 写 `ADD COLUMN IF NOT EXISTS` + 索引 + COMMENT → 实体类加字段
（MyBatis-Plus 驼峰映射）→ 先写失败测试（Mapper 读写）→ 聚焦测试 → 全量回归
（当前 1648 全绿，2026-10-25 surefire 实测）→ 文档同步（PROJECT_TRUTH 的表
清单）→ 提交后任何环境启动即自动应用。

---

## 4. 索引设计审查（三个真实例子）

**例 1：`idx_background_job_claim (status, next_run_at)` —— 为轮询查询定形**
（V8）
`selectDueJobs` 的 WHERE 是 `status='QUEUED' AND next_run_at<=now OR (status='RUNNING'
AND lease_until<=now)`，ORDER BY id LIMIT n。最左列 status 等值过滤把候选集缩到
排队/续租两类，第二列 next_run_at 做范围扫到期行；SKIP LOCKED 在这些候选行上
加锁。Worker 每 15 秒一轮，这个索引决定轮询成本不随历史任务总量（SUCCEEDED
占绝对多数）增长。追问点：status 低基数（4 个值）单独建索引没用，但作为等值
前缀 + 范围第二列是标准形状。

**例 2：`uk_excel_sheet_chunk_delta_key (sheet_id, chunk_index, cell_row,
cell_col)` —— 唯一索引一鱼三吃**（V2）
① 约束：同一格的并发编辑靠它挡重复行；② upsert 定位：写入前按全键查存在则
update（MyBatis-Plus 先查后写，唯一键兜底）；③ 读路径最左前缀：读块/压实时按
`sheet_id + chunk_index` 前缀拉该块全部增量（压实时的 selectCount 按 sheet_id
单列计数，另有 `idx_excel_sheet_chunk_delta_sheet` 单列索引服务）。四列顺序
不可换——粒度从粗到细，换序后"取一块全部增量"就不再是前缀命中。

**例 3：`idx_documents_group_role_status (group_id, required_role, status,
deleted)` —— 索引列序即权限过滤序**（V4）
DocumentRoleGate 预过滤的查询形状：group_id 等值 + required_role IN (该角色
可穿透的门槛集合，单一权威实现 `allowedRoleNames`，USER<FINANCE<ADMIN) +
status='READY' + deleted=false。四列全部命中：两个等值前缀 + 两个低基数等值
追加。V4 迁移注释直说"检索主查询将按 group_id + required_role + 可读状态过滤，
建立联合索引"——先写查询，再定索引，列序跟着过滤顺序走，这是我能背出来的
设计因果而不是马后炮。

顺带一句文档侧检索的 `idx_chunk_group_document (group_id, document_id,
chunk_index)`（V1）：等值定位文档后第三列 chunk_index 直接支持邻居扩展的
范围扫（选中 topK=5 后前后各取 1 个 chunk）。

---

## 5. 高危追问 Top5

**Q1："为什么不用 JPA 的 ddl-auto 自动建表？"**
"三档 ddl-auto 里 none 之外都不适用于多人多环境：update 只加不减、不做数据迁移、
同一份代码在不同库可能长出不同结构；create 显然是玩具。我要的是：结构变更有
版本、可审计、可重放到新环境、被篡改时启动即失败——这正是 Flyway 的四件事。
另外技术栈是 MyBatis-Plus 不是 JPA，DDL 更没有理由散在注解里。PGvector 的
`CREATE EXTENSION vector` 和 HNSW 参数这种东西，注解建表根本表达不了。"

**Q2："chunk 表数据量大了怎么办？"**
"分表分职责看。document_chunks 是文本切片（目标 240/上限 320，实为字符数近似
——勘误表口径，estimateTokens 没做 2:1 折算），一篇文档几十到几百行，检索永远
带 group_id/document_id 等值条件，B-tree 足够；向量检索不查这张表，走
vector_store 的 HNSW。真到量级瓶颈的演进序：先确认检索是否命中索引 →
按 group_id 做声明式分区（PG 原生支持，查询无感）→ 冷群组归档。excel_sheet_chunk
是 JSON 大块行，膨胀控制在 delta 侧：编辑只追加 delta，500 条阈值压实回基数，
块表行数在导入时就固定了（总行数/1000），不随编辑增长。"

**Q3："让你加一个字段，走什么流程？"**
"一句话版：新迁移 V9 加列（IF NOT EXISTS + 默认值）+ 索引 + COMMENT → 实体加
字段 → 失败测试先行 → 聚焦测试 → 全量回归 → 文档同步。绝不改已执行的 V1-V8，
因为 checksum 校验会让所有已执行环境启动失败。向后兼容由'加列带默认值、不删列'
保证，旧代码回滚仍可运行。"（展开即第 3 节完整流程。）

**Q4："为什么用一张表做队列，不上 RabbitMQ/Kafka？"**
"量级和运维现实：文档 ETL 的吞吐是每分钟个位数，队列语义要求却很完整——幂等
入队、多 Worker 抢占、崩溃自愈、有界重试、死信可见。这些用一张 PG 表加
FOR UPDATE SKIP LOCKED + 租约 + 唯一幂等键全部拿到了，零新增基础设施；引入 MQ
换來的是另一套运维面和消息/库双写的分布式事务问题。升级触发点我也明确：吞吐
逼近轮询能力（15 秒一轮×10 条）、或需要多订阅者 fan-out 时再上 MQ，届时
background_job 的接口语义可以平滑映射。**诚实边界**：Outbox 的 selectPending
没有认领语义，多实例重放存在真实消息丢失窗口，PROJECT_TRUTH 记录了——当前
只有 Worker 可安全水平扩展。"

**Q5："一个迁移执行到一半失败会怎样？"**
"PostgreSQL 里迁移在事务内执行，DDL 可回滚，所以半个迁移不会留下半套表；但
flyway_schema_history 可能留下一条 success=false 的行，Flyway 之后会拒绝继续，
需要人工清掉失败行（flyway repair）再触发重试。这也是迁移必须幂等（IF NOT
EXISTS、条件 UPDATE）的另一个原因：任何环境重放都安全。同时它解释了为什么
fail 要有界——maxAttempts 耗尽进 DEAD_LETTER 而不是无限重试，让失败可见、
可控、可人工介入。"

---

## 6. 数字最终口径（随勘误表对齐）

| 项 | 口径 |
| --- | --- |
| 迁移数 | V1、V2、V3、V4、V5、V7、V8 共 7 个文件；V6 不存在（不补号） |
| 队列状态机 | QUEUED→RUNNING→SUCCEEDED\|DEAD_LETTER（background_job）；文档另有 UPLOADED→PROCESSING→READY\|FAILED；ingestion_jobs 是观测表，三套独立 |
| 退避 | 实际 5 分钟→15 分钟→死信；1 分钟档因 claim 预增 attempt_count 不可达（off-by-one，可修） |
| 坏文件终态 | 文档 FAILED + 任务 SUCCEEDED 并存；死信只兜执行层异常 |
| 租约 / 轮询 | 租约 5 分钟；fixedDelay 15 秒、每轮 10 条 |
| Excel 块 | 1000 行/块（ExcelParserService.CHUNK_SIZE=1000）；delta 压实阈值 500 条/表 |
| 向量 | bge-small-zh 512 维，pgvector HNSW cosine（m=16, ef_construction=200） |
| 文档切片 | 目标 240 / 上限 320 / 重叠 32（yml 覆盖值；实为字符数近似） |
| 测试基线 | 1648 全绿（2026-10-25 surefire 实测；引用前应重跑确认） |
| 缓存 | RedisChunkCacheService 是进程内 LRU+TTL，无真实 Redis 依赖 |
