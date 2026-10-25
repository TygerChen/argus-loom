# 源码与文档索引

## 1. 第一优先级

| 文件 | 用途 |
| --- | --- |
| `AGENTS.md` | AI 代理必读规则 |
| `ai-handoff/START_HERE.md` | 交接入口 |
| `ai-handoff/PROJECT_TRUTH.md` | 当前事实 |
| `ai-handoff/ARCHITECTURE.md` | 架构地图 |
| `ai-handoff/DEVELOPMENT_GUIDE.md` | 开发和测试 |
| `ai-handoff/MEMORY_EXPORT.md` | 历史记忆 |
| `ai-handoff/DECISIONS_AND_CONSTRAINTS.md` | 决策与红线 |
| `ai-handoff/ROADMAP_AND_BACKLOG.md` | 演进路线 |

## 2. 学习教程

| 文件 | 主题 |
| --- | --- |
| `docs/tutorials/00-学习路线图.md` | 学习顺序 |
| `docs/tutorials/00-先看这里-术语人话词典.md` | 术语解释 |
| `docs/tutorials/01-上传Excel文件.md` | 上传、失败、幂等 |
| `docs/tutorials/02-登录与JWT认证.md` | 认证 |
| `docs/tutorials/03-权限系统.md` | 权限 |
| `docs/tutorials/04-RAG检索与引用.md` | RAG 深挖 |
| `docs/tutorials/05-Excel解析与协同编辑.md` | Excel 协同 |
| `docs/tutorials/06-版本导出与回收站.md` | 版本和导出 |
| `docs/tutorials/07-财务导入与期间关闭.md` | 财务 |
| `docs/tutorials/08-后台任务队列.md` | 队列、ETL |
| `docs/tutorials/09-运维与监控.md` | 运维 |
| `docs/tutorials/10-面试题总汇编.md` | 面试题 |
| `docs/tutorials/11-1小时面试实战手册.md` | 面试叙事 |
| `docs/tutorials/12-从零启动指南.md` | 启动 |
| `docs/tutorials/13-项目全局地图与请求旅程.md` | 请求链路 |
| `docs/tutorials/14-项目考点与基础八股对照清单.md` | Java 基础对照 |

## 3. 架构与源码陪读

| 文件 | 用途 |
| --- | --- |
| `docs/项目包架构详解.md` | 包结构详解 |
| `backend/docs/新手指南-读代码路线图.md` | 源码阅读顺序 |
| `backend/docs/核心链路-陪读-RAG与AI助手.md` | RAG/助手陪读 |
| `backend/docs/权限红线速记-新手向.md` | 权限规则 |
| `backend/docs/流程架构图/README.md` | 流程架构图入口 |
| `backend/docs/调用关系图/README.md` | 方法调用链索引 |
| `backend/docs/assessment-stage-progress.md` | 历史优化执行台账 |

## 4. 产品计划与验收

| 文件 | 用途 |
| --- | --- |
| `docs/superpowers/plans/2026-10-10-argus-loom-core-product-plan.md` | 500 人产品计划 |
| `docs/superpowers/plans/2026-10-10-enterprise-rag-fastgpt-integration.md` | RAG 集成研究 |
| `docs/acceptance/pilot-acceptance.md` | 试点验收 |
| `docs/acceptance/security-regression-checklist.md` | 安全回归 |
| `docs/acceptance/capacity-test-report-template.md` | 压测模板 |
| `docs/acceptance/rag-evaluation-report-template.md` | RAG 评测模板 |

计划和模板是目标，不代表已经全部执行。

## 5. 运维

| 文件 | 用途 |
| --- | --- |
| `docs/operations/deployment.md` | 部署 |
| `docs/operations/capacity-runbook.md` | 容量与告警 |
| `docs/operations/backup-restore.md` | 备份恢复 |
| `docs/operations/security-baseline.md` | 安全基线 |

## 6. 关键源码入口

| 链路 | 文件 |
| --- | --- |
| 应用启动 | `backend/src/main/java/com/argus/rag/ArgusRagApplication.java` |
| 登录 | `auth/controller/AuthController.java` |
| 安全配置 | `auth/security/SecurityConfig.java` |
| 文档上传 | `document/controller/DocumentController.java` |
| 上传完成/入队 | `document/service/UploadedDocumentFinalizer.java` |
| 后台 Worker | `job/worker/BackgroundJobWorker.java` 或同包对应位置 |
| ETL | `ingestion/**/EtlDocumentIngestionProcessor.java` |
| 切片 | `ingestion/**/StructureAwareChunkTransformer.java` |
| 本地 Embedding | `embedding/LocalEmbeddingConfig.java` |
| 混合检索 | `qa/rag/HybridChunkRetrievalService.java` |
| RRF | `qa/support/RrfFusion.java` |
| 角色门槛 | `document/security/DocumentRoleGate.java` |
| Rerank | `engine/reranking/RerankingConfiguration.java` |
| 问答入口 | `qa/controller/QaController.java` |
| 引用 | `qa/support/CitationAssembler.java` |
| 助手 | `assistant/agent/AssistantAgentFacade.java` |
| Excel 协同 | `excel/collab/CollabService.java` |
| Excel 权限 | `excel/service/DocumentAccessService.java` |
| 财务导入 | `excel/controller/FinanceImportController.java` |
| 财务分析 | `excel/finance/**` |
| 线程池 | `common/config/AppThreadPoolConfig.java` |

具体类路径可能因包结构调整而变化，使用 `rg --files` 确认。

## 7. 数据库

| 文件 | 用途 |
| --- | --- |
| `V1__init_core_schema.sql` | 用户、群组、文档、chunk、QA、助手 |
| `V2__excel_module_schema.sql` | Excel、版本、协同、导出 |
| `V3__finance_role_payroll_report.sql` | 财务角色、薪酬、报表 |
| `V4__document_required_role.sql` | 文档角色门槛 |
| `V5__excel_export_expire_and_job_meta.sql` | 导出过期与任务元数据 |
| `V7__finance_period_and_import_idempotency.sql` | 财务期间与导入幂等 |
| `V8__background_job_collab_outbox.sql` | 后台任务与协同 Outbox |

`V6` 不存在是当前事实，不要擅自补号或修改历史。

## 8. 脚本

| 路径 | 用途 |
| --- | --- |
| `scripts/start-all.ps1` | 一键启动 |
| `scripts/restart-argus-backend.ps1` | 重启后端 |
| `scripts/full_smoke_test.py` | 全链路冒烟 |
| `scripts/health-check.ps1` | 健康检查 |
| `scripts/rag_eval/run_eval.py` | RAG 评测 |
| `scripts/load/k6-*.js` | 容量测试 |

脚本可能依赖本地路径、运行环境和已有数据。先读脚本，再执行。

## 9. 历史资料

以下内容用于理解演进历史，不是当前事实：

- `.zcode/plans/`
- `docs/architecture/` 旧 Draw.io
- 根 `MEMORY.md` 的部分描述
- 历史 rollout 摘要
- 教程中的“演进方向”章节
