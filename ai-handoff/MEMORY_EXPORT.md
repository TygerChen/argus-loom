# 项目记忆导出

本文件把与 Argus Loom 相关的历史项目记忆、用户偏好和已验证经验整理成可交接形式。
记忆不是事实源；执行前必须回到源码、测试和 `PROJECT_TRUTH.md` 核对。

## 1. 项目来源与目标

- 用户希望把 Argus Loom 做成真实可用于内部试点的“企业知识库 + 财务 Excel
  协作与分析系统”。
- 目标用户约 500 名内部注册用户，需要处理多线程、后台任务、权限和恢复。
- 选择继续打磨自己的 Java/Vue 项目，而不是直接套用 FastGPT 等大型平台。
- 用户约半年 Java Web 经验，要求代码架构合理、可读、可维护、注释足够，
  并能把项目当作自己真正理解的项目去面试。
- 用户会使用 ZCode 等 AI 编码代理继续开发，因此计划必须包含确切路径、接口、
  迁移、测试、验证命令、里程碑和提交边界。

## 2. 用户明确偏好

- 不要只给概念。要讲“为什么这样设计、别的方案为什么不行、失败怎么办”。
- 教程先用人话和比喻，再给专业术语与项目锚点。
- 必须贴真实代码片段，并保证数字与当前代码一致。
- 项目没实现的能力要诚实标成“演进方向”，不能教用户面试时撒谎。
- 用户体验优先：失败后要给可恢复动作、状态和原因，不能只用“拒绝”收尾。
- 验证真实行为时，以实际接口响应、状态变化、SQL 和测试为准，不靠猜。
- 对面试材料，需要能抗追问：正常路径、失败路径、幂等、超时、并发、权限、
  观测、回滚、成本和替代方案都要讲清。

## 3. 已验证的关键决策历史

### 文档 ETL 接队列

历史问题：教程写着 `background_job`，但生产仍通过 Spring Event + `@Async` 执行。

修复后：

- `UploadedDocumentFinalizer.finalizeUploaded` 在同一事务中入队
  `INGEST_DOCUMENT`。
- 幂等键为 `INGEST:{documentId}:{retryGeneration}`。
- 手动重试使用新 generation。
- 死信任务同键再次入队时可条件复活。
- 旧的 Event/Listener 链路已删除。
- Worker 用 `SKIP LOCKED`、租约、退避和死信保证可恢复。

教训：不要相信已有注释、类名或教程，必须追踪生产调用方和状态更新。

### Excel 导出

Excel 导出没有迁入通用队列，仍使用专用的、有界的 `excelExportExecutor`。
Worker 不领取 Excel 导出任务。

### Redis 真相

项目没有 Redis 依赖。`RedisChunkCacheService` 是进程内有界 LRU + TTL 缓存。
旧架构图中的 Redis 节点是历史或演进示意。

### RAG 深度

已实现：

- 查询规划四策略
- pgvector + ES 双路召回
- RRF 融合
- 权限过滤
- 邻居窗口
- 证据评估与拒答
- 引用落库

未实现：

- 真实 Cross-Encoder reranker
- HyDE
- 父子分块 / late chunking
- GraphRAG / 多跳图检索
- 语义缓存
- embedding 领域微调

这些可以回答为“演进方案”，但不能说成现网能力。

## 4. 旧记忆纠偏

| 旧描述 | 当前正确事实 |
| --- | --- |
| 项目目录不是 Git 仓库 | 当前是 Git 仓库，分支 `master` |
| ETL 使用 Spring Event + `@Async` | 已改为同事务入队 `background_job` |
| 项目使用 Redis | 没有 Redis 中间件，只有本地 LRU/TTL 缓存 |
| 测试总数 1616 | 2026-10-24 实测 1634 |
| RAG 有重排 | 接口存在，默认 NoOp |
| 架构图有 Redis | 旧图，不代表当前部署 |
| 任务 1-13 计划即已完成 | 计划描述目标，实际完成度必须逐项用代码/验收证明 |

## 5. 常见失败与处理经验

- IDEA 报 `TypeTag :: UNKNOWN`：使用 JDK 21，检查 Lombok/编译器。
- PostgreSQL 密码认证失败：检查 `.env` 的 `POSTGRES_PASSWORD` 和 profile。
- 全量测试连不上 5432：先检查 Windows 服务 `postgresql-x64-16` 和端口，
  不要先怀疑代码。
- PostgreSQL 数据目录缺系统目录（如 `pg_notify`）会导致服务无法启动；
  修复系统目录前先备份。
- 修改构造函数依赖后立刻编译，不要等到全量测试才发现 Spring Bean 图断裂。
- Maven Wrapper 下载可能超时；本机可改用系统 Maven。
- 前端 SSE 失败时检查 Vite 代理是否缓冲。
- Excel 直接写 chunk/delta 表后忘记清缓存，会读到旧 celldata。

## 6. 历史验证证据

- 基线提交：`2d7ed06`
- 相关提交：
  - `be9dc75`：文档 ETL 接入 `background_job`
  - `2d7ed06`：教程和 Worker 作用域同步
  - `6360c66`：RAG 第三梯队深挖
  - `0300246`：包架构文档
- 本次交接验证：
  - 后端 1634 tests 全绿（2026-10-24）
  - 前端 14 tests 全绿
  - 前端构建成功

## 7. 更早的项目记忆来源

- 工作区根目录 `MEMORY.md`
- `docs/MEMORY-知识库问答.md`
- `.zcode/plans/`
- Codex 记忆索引中的 Argus Loom task group
- 历史线程 ID：`01a11038-fefe-7b10-a543-020158036657`

以上内容已作为“历史来源”放进包内；冲突时以当前代码和本交接包为准。

## 8. 给后续 AI 的提醒

- 记忆可以节省探索时间，但不能代替验证。
- 用户非常重视“真实可讲”，不要编造性能数字、准确率或线上经验。
- 若需要声称“生产可用”，必须有压测、安全回归、备份恢复和业务验收证据。
