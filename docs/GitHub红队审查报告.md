# GitHub 红队审查报告 — TygerChen/argus-loom

> 审查对象：`F:\ChatGPT\argus-loom-public`（= github.com/TygerChen/argus-loom，5 个提交，HEAD `2122de4`）
> 审查人视角：对手公司资深面试官，打开仓库 10 分钟，目标是让你当场难堪。
> 审查日期：2026-10-24。校准基准：`docs/口径勘误表-面试数字校准.md`（2026-10-25 核验版）。
> 全部结论均已对照公开仓源码逐条核实（文件名+行号可回查），无臆测项。

---

## 第一部分：10 分钟动线里发现的全部问题

动线模拟：
README 首屏（2 分钟）→ 提交历史（1 分钟）→ 代码抽查（4 分钟）→ 文档一致性（2 分钟）→ 截图细看（1 分钟）。

### P0 —— 会被当场问死

**P0-1 README 首屏就是死链：`docs/核心链路时序图.md` 不存在。**

- 位置：README 第 48 行"三条核心链路的逐步时序图见 [`docs/核心链路时序图.md`](...)"。
- 事实：公开仓 `docs/` 下只有 `acceptance/ operations/ screenshots/ tutorials/ 项目包架构详解.md`；
  全仓 `find -name "*时序*"` 零命中。
- 杀伤：面试官点开第一个"深挖链接"就是 404，首屏信任值清零，
  之后你说的每个数字他都要亲自验一遍。
- 修法：源仓补齐该文件后同步；或临时把链接改为 `ai-handoff/ARCHITECTURE.md`（存在且扎实）。
  改完用 `scripts/sync-to-github.ps1` 同步。

**P0-2 测试数字自相矛盾：README 1646 vs 自家事实文档 1634 vs 仓库实测 1635。**

- 位置：README 第 24/87 行"2026-10-24 本机验证 1646 tests"。
- 事实：同一仓库 `ai-handoff/PROJECT_TRUTH.md:27`、`START_HERE.md:41`、`MEMORY_EXPORT.md:113`
  全部写"1634 tests（2026-10-24 实测）"——**同一天两个"实测值"**。
- 实测：公开仓 `grep -r "@Test" backend/src/test | wc -l` = 1635；
  `@ParameterizedTest/@RepeatedTest/@TestFactory` 均为 0，无从解释差额。
- 杀伤：一次 Ctrl+Shift+F 就能让你在最想炫耀的指标上翻车。
- 修法：源仓跑一次 `mvn -o test`，统一改掉 README、PROJECT_TRUTH、START_HERE、
  MEMORY_EXPORT、根 AGENTS.md 五处（1614 也是旧值），数字必须带"日期+命令"口径。

**P0-3 教程 08 整篇还是被废弃的旧口径，和修好的 README 当面对咬。**

- 位置：`docs/tutorials/08-后台任务队列.md`。
- 事实：
  - 第 30-32 行："第一次煳了 1 分钟后重做，又煳等 5 分钟，再煳等 15 分钟"——旧退避序列；
  - 第 147 行：所贴代码注释原文"第 1/2/3 次失败后的等待时间"；
  - 第 160-161 行："有的任务永远成功不了（**文件本身损坏**）……死信 = 承认失败并交给人工"
    ——暗示坏文件进死信，而代码事实是"文档 FAILED + 任务 SUCCEEDED 并存、不进死信"；
  - 第 257-262 行（Q10）与第 274 行（口播卡）再次复述"1/5/15 分钟、三次耗尽进死信"。
- 杀伤：README 已改"执行层故障退避重试后死信"（提交 `2122de4`），教程却在唱反调——
  面试官结论："README 是面试前夜补的妆，教程才是素颜。"
- 修法：按勘误表 🔴1/🔴2 重写五处：退避改"失败 → 5 分钟 → 15 分钟 → 死信
  （1 分钟档不可达是已知 off-by-one）"；"文件损坏"例改为"执行层故障才进死信，
  业务性坏文件走 @Recover → 文档 FAILED + 任务 SUCCEEDED 并存"。
  顺带 `grep -rn "1/5/15" docs/tutorials/` 全量复扫。

**P0-4 修了 README，却没修 README 指向的代码注释——半拉子修正比不修更显眼。**

- 位置：`job/service/BackgroundJobService.java:48-49`（"第 1/2/3 次失败后的等待时间"）、
  `:192`（"按 1/5/15 分钟退避重排"）；`job/service/BackgroundJobWorker.java:40`（"按 1/5/15 分钟退避重试"）。
- 事实：`resources/mappers/job/BackgroundJobMapper.xml:26` claim SQL
  `attempt_count = attempt_count + 1` 领取即预增，叠加 Service `:212`
  `backoffIndex = Math.min(attempts, 2)`，**数组第 0 位（1 分钟）永不可达，真实序列 5/15**。
- 杀伤：面试官验证你 README 修正是否诚实的第一站就是这两个文件，
  注释还在宣称错误序列 = 修正只做了表面。
- 修法（推荐 a）：a) 修 off-by-one：`Math.min(Math.max(attempts - 1, 0), len - 1)`
  + 断言单测锁 1/5/15，注释保持不变；b) 不改代码，则三处注释改为
  "声明 1/5/15，可达 5/15（claim 预增 attempt_count，待修）"。教程 08 与 PROJECT_TRUTH 同步。

**P0-5 提交历史一把梭：416 个主源文件 + 224 个测试文件挤在一个 feat 提交里。**

- 事实：`1902288 feat: enterprise RAG...`（10-24 21:13）与 `55ac89b`、`64b7f50` 同一分钟连发；
  47 分钟后补截图（`63e050c`），次日补 README 勘误（`2122de4`）。
  全历史 5 个提交，零增量演进。
- 递刀实锤：`PROJECT_TRUTH.md` 头部写"基线提交：`2d7ed06`"——公开仓根本没有这个提交；
  `ai-handoff/HANDOFF_MANIFEST.json` 写明 `root: "F:\\ChatGPT\\argus-loom"`、
  `baseline_commit: 2d7ed06`、`worktree_has_uncommitted_user_changes: true`；
  `ai-handoff/ZCODE_START_PROMPTS.md` 内含本机路径 `F:\ChatGPT\argus-loom` 的内部提示词。
- 杀伤：面试官 30 秒可证明"内部仓洗白重推快照"，然后问：
  "真实开发过程呢？这代码到底谁写的？"
- 修法：历史不可伪造也不该伪造——治理痕迹：公开仓删除或脱敏 HANDOFF_MANIFEST
  （去掉 root/baseline/uncommitted 三字段）、PROJECT_TRUTH 基线行改公开仓 HEAD、
  ZCODE_START_PROMPTS 移出公开 sync 名单。话术预案见陷阱题 Q1。

**P0-6 README 快速开始第一条命令在别人电脑上必挂。**

- 位置：README 第 68-71 行让访客跑 `.\scripts\start-all.ps1`。
- 事实：脚本第 5-7 行默认参数硬编码本机绝对路径：
  `F:\DevelopmentTools\elasticsearch\elasticsearch-8.9.2`、
  `F:\DevelopmentTools\minio\minio.exe`、`F:\DevelopmentTools\minio-data`。
- 杀伤：clone 后按 README 操作，ES/MinIO 一步就炸，"可现场演示"卖点反噬。
- 修法：默认值改环境变量/相对路径（`$env:ARGUS_ES_HOME`、`.\tools\minio\minio.exe`），
  README 加前置说明或教程 12 给显式传参示例。

### P1 —— 扣印象分

**P1-1 "15 篇新手教程"实际 13 篇，编号断档缺 10、11。**

- README 第 78 行宣称 15 篇；`docs/tutorials/` 实际 13 个文件（两个 00 + 01-09 + 12 + 13）。
- 教程 12 开头还自述"教程 00-11 讲的是……"。
- 修法：README 改"13 篇"或补齐 10/11；教程 12 改"前面的教程"。

**P1-2 教程 12 让新人复制 `.env.example`，公开仓根本没有这个文件。**

- 教程 12 第三章："复制根目录的 `.env.example` 为 `.env`"——全仓零命中，新人动线第三步断裂。
- （`backend/mvnw.cmd` 存在，没问题。）
- 修法：源仓根目录补 `.env.example`（仅键名+占位值，绝无真实密钥），随 sync 发布。

**P1-3 README "约 500 名内部用户" vs 截图"平台用户 59"。**

- 截图 `01-admin-overview.png` 显示"平台用户 59"、模型 `agnes-3.0-flash`、当日费用 ¥0.3506；
  截图 02 显示该模型 788 次调用。
- 懂行追问："500 是设计容量还是现状？agnes-ai.cn 是什么供应商？"
  （demo 数据、无路径无账号名，这点做得好；但两个口径并存就是自埋雷。）
- 修法：README 改"按 500 用户规模设计（当前试点 60 人量级）"。

**P1-4 检索是"三通道"，README 只写双路，You.com 通道凭空消失。**

- `qa/rag/HybridChunkRetrievalService.java` 类注释自述"向量 + 关键词 + **You.com 网络搜索三个通道**"；
  `engine/youcom/YoucomSearchService.java` 未配 key 时静默返回空列表（`youcom.search.api-key:` 默认空）。
- README 亮点表与 mermaid 架构图均只画 pgvector+ES 双路——亮点漏报 + 口径不自洽双重扣分。
- 修法：亮点行补"（可选第三通道：You.com 网络搜索，未配 key 自动旁路）"，
  架构图加虚线节点；或类注释统一"双主通道 + 可选 web 通道"。

**P1-5 PROJECT_TRUTH 自称"与代码同步维护"，却没同步已被确认的 off-by-one。**

- `PROJECT_TRUTH.md:127`"队列级退避 `{1, 5, 15}` 分钟"无不可达说明
  （第 132 行业务层互斥倒是讲清了）。
- 文档第 1 节还写"本文件也不保证永久正确"——态度可嘉，但会被反将：
  "你最高优先级的事实文档都不同步，凭什么让我信 README？"
- 修法：该行改为"声明 `{1,5,15}`，可达序列 `5/15`（claim 预增 attempt_count 的 off-by-one）"。

**P1-6 Worker 注释宣称"看不懂的任务进死信"，但看不懂的 payload 恰被静默置 SUCCEEDED。**

- `BackgroundJobWorker.java:40-42` 注释："未知任务类型也走死信而不是默默忽略。"
- 代码 `:78-110`：`readGroupId` 解析失败返回 null → `if (documentId != null && groupId != null)`
  跳过执行 → `jobService.complete()` → **任务 SUCCEEDED，什么也没干**。
- 注释与实现存在张力：被问出来才解释是扣分，主动讲是加分（勘误表 🟢1 已定性）。
- 修法：`readGroupId` 失败改 `failOrRetry(BAD_PAYLOAD)` + 坏 payload 单测；
  不改代码就改注释，别让注释吹实现没有的牛。

**P1-7 内部过程文件整包发布，"注释汉化运动"的施工日志都在。**

- `backend/docs/中文注释周计划.md`（"把注释统一成中文、让新人（你）能读懂"，
  2026-09-21 扫描 391 个文件统计注释语言）+ 配套 `check-english-comments.py` 扫描器
  + `assessment-stage-progress.md`、`refactor-plan.md`、`调用关系图/` 等。
- 不是泄密，但把"注释按周计划批量补"摆上台面，配合单提交历史，
  "代码 AI 写、注释后补"的印象坐实。
- 修法：过程性文件移出公开 sync 名单（保留 error-codes-guide 等成品），
  或在 README"AI 协作"一节一句话主动交代，把被动曝光变主动坦白。

**P1-8 `RedisChunkCacheService.getSheetChunks` 反序列化失败返回空列表，不回源。**

- `excel/service/RedisChunkCacheService.java:100-105`：JSON 解析异常 catch 后
  `return new ArrayList<>()`——调用方约定"null=未命中→回源 DB"，
  损坏条目却返回"命中但 0 条"，且 hitCount 已在解析前加一。
- 后果：条目损坏期间该 Sheet 永远查不到数据，直到 TTL 120s 过期。
  边界瑕疵还恰在 README"诚实的命名"FAQ 引流的类里。
- 修法：catch 分支失效该键 + 返回 null；计数改在解析成功后；补"损坏条目应回源"单测。

### P2 —— 锦上添花

- **P2-1** `frontend/README.md` 还是 Vite 脚手架原文（"This template should help you..."）。删掉或写三行真实说明。
- **P2-2** `start-all.ps1` 变量名 `$EmbeddingPort=11435` / `$embeddingDeadline` 实际等的是 chat 网关
  （embedding 已 JVM 内化，脚本第 74 行注释自己说了）；教程 12 还叫它"本地向量化服务"。统一改 `GatewayPort`。
- **P2-3** 提交邮箱 `tygerchen@qq.com`——无伤大雅，求职场景建议换中性邮箱。
- **P2-4** README 亮点表"fail-fast（EnvironmentPostProcessor）"值得直接点名 `ProductionConfigGuard`——
  该类实现质量高（有专测、有 spring.factories 注册属实），现在却没给面试官入口。
- **P2-5** 教程通篇"面试官会问""面试高频题"口吻：对中文团队是教学资产，
  对 GitHub 访客是"求职道具"既视感。可保留（诚实），但 README 里不必再强调。
- **P2-6** `HybridChunkRetrievalService` 6 个望远镜式构造函数（测试路径与生产路径混在一个类），
  被指着问"为什么 6 个"时要么承认演化债、要么用 builder/测试工厂收口。
- **P2-7** 截图 03 文档名是 demo 命名无泄露，但"会议纪要 2025-11-07"比其他名字更像真实文件，
  统一成风格化 demo 名更稳（强迫症面试官友好）。

---

## 第二部分：10 个"指着代码问"的陷阱题 + 标准答案

每题：面试官动作 → 标准答案（已对照公开仓代码核实）→ 反杀点。

**Q1「你这仓库就一个 feat 提交，`2d7ed06` 是什么？这代码真是你写的吗？」**
（指着 `HANDOFF_MANIFEST.json` / `PROJECT_TRUTH.md` 头部）

- 答："公开仓是内部开发仓脱敏后的快照，内部仓有完整增量历史但带未净化的本地路径与过程产物，
  所以重放为单提交发布；`2d7ed06` 是内部基线，同步时漏脱敏，已列修复。
  代码归属我不靠历史证明——您可以随便指一个类，我从调用链讲到 SQL，
  比如这个 `failOrRetry` 的退避索引就有个我能当场推导的 off-by-one。"
- 反杀点：立刻主动切到 Q2，把最险的问题变成主场。

**Q2「`RETRY_BACKOFF_MINUTES = {1, 5, 15}`，失败后到底几分钟重试？」**
（指着 `BackgroundJobService.java:49` + `BackgroundJobMapper.xml:26` + `:212`）

- 答："声明 1/5/15，**实际可达序列是 5 分钟、15 分钟，第三次失败进死信**。
  推导：claim SQL 领取时 `attempt_count = attempt_count + 1`，第一次执行失败时 attempts 已是 1；
  `backoffIndex = Math.min(attempts, 2)` 取下标 1 → 5 分钟；数组第 0 位（1 分钟）是不可达死代码。
  一行可修（索引改 `attempts - 1`）加断言单测锁住。README 已按真实语义表述。"

**Q3「上传一个 1KB 损坏 PDF，终态是什么？进死信了吗？」**
（指着 `DocumentIngestionAsyncService.java:121-149`）

- 答："不进死信。坏 PDF 在 ETL 内先被业务层 Spring Retry 拦下——
  `@Retryable(maxAttempts=3, backoff=@Backoff(delay=2000, multiplier=2.0))` 即 2s/4s/8s——
  耗尽后 `@Recover` 接管：清理半成品、文档置 FAILED、方法**正常返回**；
  Worker 看到正常结束把任务置 SUCCEEDED。终态 = 文档 FAILED + 任务 SUCCEEDED 并存，
  `argus.job.dead_letter` 全程 0。死信真正兜执行层异常（worker 崩溃、MinIO 抖动）。
  这是两层重试互斥问题，我故障演练抓出来的。"

**Q4「注释说'看不懂的任务进死信'，那 payload 里 groupId 解析失败会怎样？」**
（指着 `BackgroundJobWorker.java:78-110`）

- 答："诚实说：被静默置 SUCCEEDED——`readGroupId` catch 后返回 null，
  `if (documentId != null && groupId != null)` 跳过执行直接 complete。
  这是已知边界瑕疵：看不懂的 payload 应 `failOrRetry(BAD_PAYLOAD)` 进死信等人，而非装作成功。
  只有 jobType 未知才走 `UNKNOWN_JOB_TYPE` 转死信。
  Worker 也是硬编码 switch 只认 `INGEST_DOCUMENT`，实体注释里的
  `EXCEL_EXPORT/FINANCE_IMPORT` 是预留位——Excel 导出走专用
  `excelExportExecutor`（`AppThreadPoolConfig.java:45`），不进队列。"

**Q5「`estimateTokens`——240 token 的切片，真是 token 吗？」**
（指着 `StructureAwareChunkTransformer.java` 的 `estimateTokens` 一行实现）

- 答："是字符数近似：实现就是 `Math.max(1, text.length())`。
  中文 2:1 折算的意图体现在硬切步长（×2），但没贯穿 estimateTokens——参数名与实现不自洽，
  我复核确认过。口径：目标 240/上限 320/重叠 32，单位近似字符。
  是否够用由评测集裁决（40 例 95%、170 例 HitRate@5 97.6%），不吹精确分词。"

**Q6「Flyway 迁移 V1-V8，V6 呢？删过迁移？」**
（指着 `backend/src/main/resources/db/migration/`）

- 答："V6 从未存在：它只出现在 `backend/docs/optimization-assessment-report.md` 的规划表里
  （并发一致性专项），该变更最终未以 V6 落地，直接进了 V7（财务期间与导入幂等）
  和 V8（background_job + collab_outbox）。`ai-handoff/SOURCE_AND_DOC_INDEX.md:111`
  明文：'`V6` 不存在是当前事实，不要擅自补号或修改历史'——已执行迁移不可改是项目红线。
  Flyway 按 checksum 校验，跳号无害。"

**Q7「类名叫 RedisChunkCacheService，你根本没 Redis？多实例怎么办？」**
（指着 `excel/service/RedisChunkCacheService.java`）

- 答："对，无真实 Redis 依赖——进程内有界缓存：LinkedHashMap accessOrder +
  removeEldestEntry 实现 LRU（512 条）、TTL 120s、全写路径显式失效
  （单元格写入/压缩/替换工作簿/回滚/删除都调 invalidate）、hit/miss 观测、方法级 synchronized。
  类名是历史命名，README FAQ 明确交代'诚实的命名和诚实的架构比堆中间件更重要'。
  数据安全靠 PostgreSQL 主存储，未命中回源；多实例时本类内换 Redis 实现，读路径不变。
  要挑刺的话：反序列化失败我返回了空列表而不是回源 null——已列修复。"

**Q8「`effectiveAllowedDocumentIds`：roleGate 为 null 就返回 null'不限制'——这也敢叫 fail-closed？」**
（指着 `HybridChunkRetrievalService.java:478-484` + `DocumentRoleGate.java:64-78`）

- 答："分两层：生产装配由 `@Autowired` 主构造器注入真实门，null 只存在于历史/单元测试的
  望远镜构造器路径——这是我承认的演化债，正确收口是删遗留构造器加断言。
  权限语义在门内 fail-closed：`allowedDocumentIds` 对非法 groupId 返回 `Set.of()`
  （空集 → 检索零证据）；USER/FINANCE/ADMIN 穿透在 `allowedRoleNames` 单一权威定义；
  越权检索拿到空证据而非 403——防枚举探测，刻意设计。"

**Q9「ES 索引发生在 ETL 的第几步？」**
（指着 `DocumentIngestionAsyncService.syncSearchIndex`）

- 答："不在 `EtlDocumentIngestionProcessor` 六步
  （读文档→MinIO 读+解析→清洗→预览落库→切片落库→写向量）之内。
  ES 索引在外层 `syncSearchIndex`：等 chunk 全部落库拿到主键后统一交给
  `ElasticsearchChunkIndexService.indexReadyChunks`——先后关系显式化。
  幂等三重保障同链路：开头 `cleanupProcessingArtifacts` 先清旧产物、chunk 先删后插、
  向量用 documentId+chunkIndex 确定性 UUID。"

**Q10「服务重启，昨晚还在 PROCESSING 的文档怎么办？有超时时间窗吗？」**
（指着 `document/service/StaleProcessingDocumentRecoveryRunner.java`）

- 答："启动完成后（ApplicationRunner）把**所有**遗留 PROCESSING 文档一次性置 FAILED，
  失败原因'服务中断未完成，请重试'——不设时间窗、不重新入队，靠用户在
  `POST /api/documents/{id}/retry-ingestion` 手动重试（仅 FAILED 可重试，
  全新幂等键 + 新 generation，不触发死信复活）。
  若队列里同文档任务还活着，Worker 租约（5 分钟）到期被接管跑成功，
  文档会 FAILED→READY 跳变，前端容忍这个中间态。
  曾有注释声称 processing-timeout-minutes 时间窗，从未生效，已修正。自动重入队在改进清单上。"

**备选追问弹药（面试官换靶时）**：

- 拒答阈值在 `qa/rag/EvidenceAssessor.java`：topScore≥0.85 且双源/向量证据 → 强证据；
  topScore<0.30 或单文档且均分<0.40 → 拒答，**根本不调模型**。
- SSE 不是 SseEmitter：`QaController /stream-ask` 用 `StreamingResponseBody` + 自研 `SseEventWriter`，
  注释写明线程上下文传递与"错误也走 SSE 事件"两要点；前端用 fetch ReadableStream 消费。
- 队列指标四个 Gauge：`argus.job.queued/running/dead_letter/oldest_wait_seconds`，
  30 秒刷新（`JobQueueMetrics.java:35-46`）。
- 检索默认参数：channelTopK=50 / recallK=50 / maxGlobalDocuments=50 / maxChunksPerDocument=0
  （`RetrievalOptions.java` defaults）——README"各 topK50"属实。

---

## 第三部分：修复清单（源仓改 → `scripts/sync-to-github.ps1` 同步）

按优先级排序，均在源仓 `F:\ChatGPT\argus-loom` 操作后同步：

| # | 文件（源仓） | 改法 | 级别 |
| --- | --- | --- | --- |
| 1 | `docs/核心链路时序图.md` | 补齐文件（若源仓已有，检查 sync 白名单为何漏掉）；或 README L48 改链 `ai-handoff/ARCHITECTURE.md` | P0 |
| 2 | `README.md` + `ai-handoff/PROJECT_TRUTH.md` + `START_HERE.md` + `MEMORY_EXPORT.md` + 根 `AGENTS.md` | 重跑 `mvn -o test`，五文件统一测试数字（含日期+命令），清除 1614/1634/1646 矛盾值 | P0 |
| 3 | `docs/tutorials/08-后台任务队列.md` | 按勘误表 🔴1/🔴2 重写 L30-32、L147、L160-161、L257-262、L274；再 `grep -rn "1/5/15" docs/tutorials/` 全扫 | P0 |
| 4 | `backend/.../job/service/BackgroundJobService.java`、`BackgroundJobWorker.java` | 首选修 off-by-one（`attempts - 1` + 断言单测）；否则三处注释改"可达 5/15 + 原因" | P0 |
| 5 | `ai-handoff/HANDOFF_MANIFEST.json`、`PROJECT_TRUTH.md` 头部、`ai-handoff/ZCODE_START_PROMPTS.md` | 公开版去掉本机路径/`2d7ed06`/uncommitted 字段；ZCODE_START_PROMPTS 移出 sync 名单 | P0 |
| 6 | `scripts/start-all.ps1` | 默认参数改环境变量/相对路径；README 快速开始加前置说明 | P0 |
| 7 | `README.md` L78 + 教程 12 L4 | "15 篇"改"13 篇"（或补 10/11）；"教程 00-11"改"前面的教程" | P1 |
| 8 | 根目录新增 `.env.example` | 仅键名+占位值（AGNES_API_KEY= 等），教程 12 引用才成立 | P1 |
| 9 | `README.md` 亮点表 | "约 500 名内部用户"改"按 500 用户设计（试点 60 人量级）"；补 You.com 第三通道一句；fail-fast 点名 `ProductionConfigGuard` | P1 |
| 10 | `ai-handoff/PROJECT_TRUTH.md` L127 | 退避行补 off-by-one 事实 | P1 |
| 11 | `backend/.../job/service/BackgroundJobWorker.java` | 坏 payload 改 `failOrRetry(BAD_PAYLOAD)` + 单测（至少修注释） | P1 |
| 12 | `backend/docs/`（周计划、进度表、扫描器、refactor-plan） | 移出公开 sync 名单，源仓保留 | P1 |
| 13 | `backend/.../excel/service/RedisChunkCacheService.java` | 反序列化失败：失效该键+返回 null（回源），修 hit 计数时机，补损坏条目单测 | P1 |
| 14 | `frontend/README.md` | 删模板 README 或写三行真实说明 | P2 |
| 15 | `scripts/start-all.ps1` + 教程 12 | `$EmbeddingPort`→`$GatewayPort`，术语统一"chat 网关" | P2 |
| 16 | 口径备忘（不改码） | 前端单飞刷新在 `frontend/src/stores/auth.ts`（bootstrapTask 共享 Promise），勿说成"axios 401 拦截器重放" | 备忘 |

同步后自检命令（公开仓执行，四条全过才算修完）：

```powershell
# 旧口径残留：应只剩代码常量本体与"off-by-one/不可达"说明
grep -rn "1/5/15" docs/ README.md ai-handoff/ | grep -v "off-by-one|不可达|RETRY_BACKOFF"
# 旧测试数字：应为零
grep -rn "1614|1634" --include="*.md" .
# 幽灵提交号：应为零
grep -rn "2d7ed06" --include="*.md" --include="*.json" .
# README 所有相对链接逐个点开
```

---

## 第四部分：真实加分项清单（面试官会点头的瞬间）

红队视角下仍愿意承认的硬货——面试时主动引流到这些点：

1. **README 的"AI 协作 + 四层人工防护"自白**（含"两个 IDOR 越权由人工审查发现并以 TDD 修复"）——
   2026 年语境里，坦白 AI 参与并有可描述防护机制，比假装纯手写可信得多。
2. **`ProductionConfigGuard`**：EnvironmentPostProcessor + `META-INF/spring.factories` 注册属实、
   只对 prod 生效、示例值黑名单（changeme/minioadmin 等 9 个）、JWT HS256 ≥32 字符前置校验、
   `resolveQuietly` 把占位符异常转为一次性全量汇报、有专测。教科书级 fail-fast。
3. **`RemoteHttpRerankingService` 的"降级是第一设计约束"**：自身永不抛出
   （非 200/长度不符/索引越界/重复索引全兜底为保序分数）、管线侧第二道 try-catch 双保险、
   默认 NoOp 且注释写明"上线前必须先跑离线对比评测"——不吹模型只讲可用性，成熟。
4. **`BackgroundJobService` 机制密度**：SKIP LOCKED 原子领取 + 条件 UPDATE 抢占 + 5 分钟租约 +
   死信复活（条件 UPDATE 只认 DEAD_LETTER 防并发双复活）+ 注释写透 MyBatis-Plus
   `updateById` 忽略 null 字段所以要显式 UpdateWrapper 的坑——四道分布式经典题一个类全答。
5. **评测闭环仓内可复现**：`scripts/rag_eval/`（build_corpus/run_eval/testset.json/eval_result.json/report.md）
   与 README 数字逐项吻合：40 例 overall 0.95 / refusal 1.0（eval_result.json summary 原文），
   170 例 HitRate@5 97.6%（163/170）、MRR 95.1%、综合 94.1%。敢放原始 JSON，全仓最硬背书。
6. **权限体系**：`DocumentRoleGate` 角色穿透单一权威（USER→{USER}；FINANCE→{USER,FINANCE}；ADMIN→全量）、
   越权检索返回空证据防枚举、三层独立校验（文档门/Excel ACL/财务角色）——README 宣称与代码一致。
7. **`PROJECT_TRUTH.md` 第 1 节"事实优先级"**：敢写"本文件也不保证永久正确，关键结论应重查
   调用方、SQL 和测试"——稀缺的工程谦逊信号（前提是 P1-5 修完，否则此句反噬）。
8. **诚实的架构 FAQ**："为什么不用 RabbitMQ / 为什么单体 / Redis 在哪里"三问答案与代码一致
   （队列行与业务行同事务、SKIP LOCKED、进程内缓存），没有中间件名词堆砌。
9. **`RedisChunkCacheService` 的 P1-05 修复自述**：注释坦白"修复前是无界 ConcurrentHashMap
   且生产无调用方的假缓存"——把缺陷史写进类注释，比藏着掖着高级。
10. **SSE 实现细节注释**（QaController）：线程上下文传递、错误也走 SSE 事件不断流——
    说明真踩过 StreamingResponseBody 的坑；追问 SseEmitter 差异也能答。
11. **迁移纪律**：V8 建表 SQL 自带设计注释 + `uk_background_job_idempotency` 唯一索引 +
    `idx_background_job_claim(status, next_run_at)` 认领索引齐活；V6 缺号有明文口径不许补。
12. **git 卫生合格**：全历史逐提交扫描，无 .env、无 upload/、无硬编码密钥；.gitignore 覆盖完整——
    P0 级泄密一项都没有，这在候选人仓库里已属少数。

---

### 结语（给求职者的一句话）

这个仓库的代码质量撑得起"高级工程师"叙事，
死穴全部集中在**"数字与文档不自洽"**：
README / 教程 / 事实文档三张嘴各说各话；
修正只修了门面没修里子（教程 08 与代码注释）；
内部交接件连本机路径一起端了出去。
面试官 10 分钟能挑的刺，九成是你自己 10 分钟就能 grep 掉的。
先跑第三部分清单的 1-6 项，再投简历。
