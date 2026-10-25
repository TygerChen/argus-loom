# 后续演进路线与任务池

本文件不是“已经完成”的声明，而是基于 2026-10-23 当前代码状态给出的优先级建议。
真正开工前，ZCode 必须先核实相关调用方、测试和运行条件。

## P0：对外试点前必须先处理

### P0-1 凭据与配置卫生

问题：

- `.env` 属于本地敏感文件。
- `application-dev.yml` 中存在仓库级默认密码值。
- 需审计 Git 历史是否包含真实凭据。

状态（2026-10-24）：审计完成，见 `docs/operations/credential-hygiene-audit.md`。
dev yml 仓库级默认口令已确认随 origin/master 推送 Gitee（自初始提交即入库），
**轮换等用户手工执行**（涉及本地 Postgres，AI 不代改）。已落地：
`ProductionConfigGuard` prod fail-fast（提交 fe18149）、`.gitignore` 补
`backend/upload/`；`application-local.yml` 确认从未入库。

剩余行动：

1. 用户按审计文档第 2 节执行口令/JWT 轮换。
2. dev yml 默认值移除（改无默认占位，依赖 .env）。
3. Git 历史清理需单独评审，不能直接 rewrite。

验收：

- 仓库扫描无真实密码。
- 生产 profile 缺密钥时启动失败并给出明确提示。
- 本地按 `.env.example` 可以启动。

### P0-2 多实例状态审计

重点核实：

- `RateLimitAspect` 是否使用本地计数。
- `RedisChunkCacheService` 的跨实例一致性。
- `OnlineUserTracker` 是否只在单 JVM。
- `ExcelDocumentLockService` 是否只在单实例有效。
- STOMP simple broker 是否支持跨实例广播。
- Outbox 是否能覆盖跨 API 实例协作。

状态（2026-10-24）：只读审计与证据矩阵已完成，见
`docs/operations/multi-instance-audit.md`（14 项逐条核实到 file:line；关键新发现：
Outbox 重放对非保存实例存在真实消息丢失窗口，API 双实例部署前必须处理）。
引入 Redis / Broker Relay / 分布式锁的改造尚未开始。

剩余验收：

- 任意多实例部署前，每一项本地状态都有明确结论。
- 滚动重启和双实例测试通过。

### P0-3 RAG 基线评测

现有材料：

- `scripts/rag_eval/`
- `scripts/rag_eval/testset.json`
- 压测与评测报告模板

状态（2026-10-24）：**首轮真实基线已落盘**（真实启动栈 + 40 题，
`scripts/rag_eval/eval_result.json` / `report.md`，运行记录见
`docs/operations/live-stack-verification-20261024.md`）：HitRate@5=100%、MRR=100%、
要点覆盖 97.2%、正例准确率 94.4%、拒答准确率 100%、综合 95.0%。口径为固定评测
语料的域内基线，不可外推到任意语料。

剩余行动：

1. 首 Token 延迟与逐题失败归因（本轮 2 个未达标正例样本见 report.md）。
2. 接真实 reranker（P1-1）时以本基线做对照（延迟 + 准确率边际）。
3. 定期重跑形成趋势，不写估算值。

验收：

- 可重复生成一份带环境信息的 RAG 评测报告。
- 指标与本文件、面试材料和项目管理看板一致。

### P0-4 备份恢复与故障演练

按 `docs/operations/backup-restore.md` 和 `docs/operations/capacity-runbook.md`
执行：

- PostgreSQL 恢复
- MinIO 恢复
- ES 重建
- Worker 崩溃接管
- 死信复活
- 导出任务恢复

验收：

- 有真实演练记录、耗时和问题清单。
- RPO/RTO 达标或重新定义门槛。

## P1：产品价值与可信度

### P1-1 接入真实 Reranker

状态（2026-10-24）：**已落地并在 local profile 启用**。BAAI/bge-reranker-base
经本地网关 `/rerank`（fastembed TextCrossEncoder）接入，Java 侧
`RemoteHttpRerankingService`（provider=remote 才装配，默认仍 NoOp）。实测：
50 对候选 3.47s（CPU）、端到端 QA 5.7s、超时/故障/响应异常三重回退保序。
评测：精选 40 题集与 NoOp 指标持平（基线已饱和，无提升空间属预期）；压测
170 题集对比见 `scripts/rag_eval/eval-stress-rerank-on-20261024.log` 与归档的
`*baseline-noop*` 文件。全量回归 1642/0/0。

原要求（已全部满足）：模型选择、内存/延迟预算、超时与降级；重排失败回退 RRF 原序；
离线对比 HitRate/MRR/延迟；模型异常不变成问答失败（服务内回退 + 管线 try-catch 双保险）。

### P1-2 引用权限生命周期

目标：权限撤销后，历史引用不能继续泄露正文。

检查：

- `QaRecordQueryService` 的引用重鉴权。
- 引用正文、片段、下载链接和预览接口。
- 资源不存在与无权限的错误语义。

### P1-3 前端首屏与 Univer 包体优化

当前构建结果中 `vendor-univer` 约 11.4 MB。

行动：

- 检查 Univer 是否只在 Excel 路由加载。
- 配置 manualChunks。
- 评估按需 preset。
- 对比优化前后 gzip、首屏和交互时间。

### P1-4 可观测性闭环

完善：

- 队列深度、最老任务等待时间、死信数。
- 上传成功率、ETL 各阶段耗时。
- 检索 P50/P95、各通道命中率、RRF 命中率。
- LLM 首 Token、总耗时、错误率、token 与费用。
- Excel Outbox 延迟和积压。

验收：有仪表盘、告警阈值和 runbook，而不是只有指标代码。

### P1-5 安全回归自动化

覆盖：

- 文档/工作簿/sheet/version/export 的 IDOR。
- WebSocket 订阅越权。
- 财务角色绕过。
- XSS、SSRF、ZIP bomb、超大批量协同消息。
- 日志敏感信息泄漏。

## P2：RAG 和业务深化

### RAG

- 查询路由：事实问答、汇总、比较、跨文档、财务结构化问题分流。
- Query rewrite 的离线评估。
- 父子分块：小块召回、大块生成。
- 语义缓存：只缓存可复用、权限无关或按权限分区的结果。
- HyDE：用于短问题、同义表达和召回不足场景，先做实验。
- GraphRAG：只在存在多跳关系、实体关系和跨文档汇总需求时引入。
- Embedding 微调：先建立足够大的标注集和基线，再评估收益。
- 多路召回自适应：不是每个问题都固定召 50+50。

### 知识库体验

- 上传后即时状态页与进度。
- 失败原因可操作化：重新上传、重试、联系管理员。
- 文档版本与引用版本绑定。
- 过期文档/冲突文档提示。

### Excel 与财务

- 导入预览和错误定位。
- 大表查询下推数据库，避免全量内存聚合。
- 更细粒度审计和恢复点。
- 财务异常解释与可追溯计算过程。

## P3：技术债与可维护性

- 拆分过大的 god class，但必须先锁行为。
- 收敛 Controller 直接返回 Entity 的问题，先做契约评审。
- 统一错误码与 HTTP 状态语义。
- 逐步移除历史 `/sql` 辅助脚本与 Flyway 并存造成的认知负担。

## ZCode 接任务的推荐顺序

1. 只读审计并给出证据表。
2. 写失败测试。
3. 小步实现。
4. 聚焦测试。
5. 全量测试。
6. 更新交接事实。
7. 给出风险、回滚和未验证项。

## 不要一次做完

每个任务应控制在可审查、可测试、可回滚的范围。优先完成一个闭环，而不是同时改权限、
队列、缓存、模型和前端。
