# Codex 记忆索引中的 Argus Loom 片段

> 来源：本机 `C:\Users\yuanchentai\.codex\memories\MEMORY.md`
> 这是历史记忆摘录，不是当前事实源。

## Scope

Use for the `argus-loom` Java/Spring/Vue internal knowledge-base and finance-Excel
system, especially execution-ready planning and the verified document-ingestion
queue path.

## 用户项目方向

- 产品方向是“企业内部知识库 + 财务 Excel 协作与分析系统”：
  带引用的制度问答、财务工作簿协作、月度财务/经营分析。
- 先做模块化单体；优先级是显式资源类型、集中授权、有界线程池、持久任务、
  可审计和可评测。
- 实施顺序：M0 安全 -> M1 可靠上传 -> M2 可信检索/引用 -> M3 Excel 版本/审计/
  导出/协同 -> M4 财务导入/期间/分析 -> M5 运维/备份/评测/试点验收。
- 不接 FastGPT，不拆微服务，不把 Kubernetes 作为第一期前置条件。

## RAG 与队列经验

- 企业 RAG 优先混合检索 + RRF、Cross-Encoder 重排、查询改写、结构感知切片、
  证据拒答和离线评测。
- 文档 ETL 旧路径是 Spring Event + `@Async`；后来迁到 `background_job`。
- `UploadedDocumentFinalizer.finalizeUploaded` 在同一事务中写文档元数据并入队。
- 初始幂等键 `INGEST:{documentId}:0`；手动重试使用新 generation。
- 队列使用 `FOR UPDATE SKIP LOCKED`、租约、退避、`DEAD_LETTER`。
- ETL 必须是幂等的，因为队列语义是 at-least-once。
- Excel 导出仍走专用 `excelExportExecutor`，不进入通用 Worker。

## 重要失败教训

- 曾出现“教程/注释说队列已上线，但生产仍走旧事件异步”的事实漂移。
- 教训：对当前行为的声明必须核对生产调用方、状态更新、Mapper/SQL、测试和运行时入口。
- PostgreSQL `localhost:5432` 连接失败时，先检查 Windows 服务和数据目录，
  不要先归因于代码回归。
- 修改构造函数依赖后立即编译，避免 Spring Bean 图问题拖到全量测试才暴露。

## 历史线程

- Thread ID：`01a11038-fefe-7b10-a543-020158036657`
- 该线程后续通过多次任务完成启动修复、全量教学注释、教程体系、RAG 深挖和
  文档 ETL 队列接线。
