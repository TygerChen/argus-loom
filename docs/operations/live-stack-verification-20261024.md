# 真实技术栈启动与验证记录（2026-10-24）

> 全程在本机真实启动并验证：PostgreSQL 16（系统服务）→ Elasticsearch 8.9.2 →
> MinIO → 本地 LLM 网关（11435）→ 后端（10001，local profile）→ 前端（8081）。
> 本文是 P0-3"真实基线"与 P0-4"演练前置"的证据记录；所有数字为当日实测。

## 1. 启动结果

| 组件 | 状态 | 备注 |
| --- | --- | --- |
| PostgreSQL 16 | ✅ 已运行（系统服务，5432） | Flyway V1–V8 校验通过 |
| Elasticsearch 8.9.2 | ✅ cluster `argus-local`（yellow=单节点正常） | 已有既有索引与数据 |
| MinIO | ✅ 9000/9001 | 健康探针 200 |
| LLM 网关（chat-only-gateway.py，11435） | ✅ 修复后 0.5s 正常返回 | 见 §2 两个环境故障 |
| 后端（mvn -o spring-boot:run，JDK21+系统 Maven） | ✅ ~15s 启动 `/actuator/health` UP | `run-backend.bat` 原 JAVA_HOME 失效已修 |
| 前端（Vite dev，8081） | ✅ HTTP 200 | |

## 2. 启动过程中发现并修复的环境故障

1. **`scripts/run-backend.bat` JAVA_HOME 失效**（指向不存在的 corretto 路径）→
   后端起不来；已改指 `F:\DevelopmentTools\Java\jdk-21`。
2. **LLM 网关 500/问答 180s 超时**：Windows 用户级环境变量 `AGNES_API_KEY`
   持有失效令牌（`sk-OEZ***`），顶掉脚本内有效默认 key（`sk-wtn***`）。
   unset 后网关恢复。凭据处置见 `credential-hygiene-audit.md` F7。
3. 佐证审计 F6：dev yml 默认数据库口令**已被本地 PG 拒绝（28P01）**，真实口令
   仅在 `.env`——Gitee 暴露的是旧口令。

## 3. 全链路冒烟（scripts/full_smoke_test.py）

**修复脚本后终版：126 项检查 126 PASS / 0 FAIL（exit=0）**，日志
`logs/smoke-run-20261024-c.log`。首轮 114/120 的 6 个失败已全部定性并修复脚本：

| 原失败项 | 定性 | 处置 |
| --- | --- | --- |
| finance 5 项 403/None | **脚本过期**：普通 USER token 打 `/api/finance/**` 被 URL 级 `ROLE_FINANCE` 门禁正确 fail-closed 拒绝 | 脚本增加管理员 `PATCH /api/admin/users/{id}/role {"systemRole":"FINANCE"}` 授角色 + 重登（注意 account 段已改密，需用 NEW_PASSWORD），财务段全部改用新 token → 11 项全过 |
| excel cancel export 409 | **良性竞态**：小工作簿导出在 cancel 到达前已完成，服务正确拒绝取消已完成任务 | 脚本接受 200 或 409/EXCEL_EXPORT_STATE 两种结果 |

冒烟覆盖且通过的链路：注册/登录/JWT 刷新轮换/登出、群组、文档直传+分片上传、
**ETL 入库 READY**、**QA 同步问答（answered=True）+ SSE 流式（21 tokens，事件
citations/evidence-overview/record/token）**、问答记录/评分/列表、Excel 上传/协同/
版本/导出/分享/ACL/转让/回收站、管理员运维与指标接口、**财务导入/批次/激活/汇总全链**。

## 3b. 浏览器端走查（Playwright，真实用户流）

登录 → 管理工作台（应用自身健康面板确认 LLM/PGvector/ES/MinIO/PG 全部在线）→
退出 → evaluser 登录 → 群组列表/详情 → AI 助手"知识库检索"模式提问
"去北京出差住酒店一晚最多能报销多少钱？" → 流式回答"**500元**（一线城市）" +
**Evidence Chain 2 条引用**（公司差旅报销制度.md 86.5% 首命中、员工考勤与请假制度.md
44.2%），会话持久化。Console 错误仅为登录前的 401/403 探测噪音。
顺带发现前端小 bug：管理员工作台"LLM 成功率"显示 **10000.0%**（百分比被重复 ×100，
P3 修复项）。另注：`/app/qa` 路由已重定向至 AI 助手页，知识库问答入口在助手页
"知识库检索"标签下。

## 4. RAG 评测基线（P0-3，首次真实落盘）

`python scripts/rag_eval/run_eval.py`（复用群组 13，40 题，每题间隔 3.5s）：

| 指标 | 值 |
| --- | --- |
| HitRate@5 | **100.0%** |
| MRR | **100.0%** |
| 平均要点覆盖率 | 97.2%（阈值 60%） |
| 正例回答准确率 | 94.4%（36 正例中 34 达标） |
| **负例拒答准确率** | **100.0%**（4/4 正确拒答，不编造） |
| 综合准确率 | **95.0%** |

结果已写入 `scripts/rag_eval/eval_result.json` 与 `report.md`（2026-10-24 版）。
注意口径：该基线基于固定评测语料与测试集（组内域内问答），100% 命中说明检索在
该语料上接近上限，**不能外推为任意语料表现**；后续接真实 reranker（P1-1）时以此
为对照组，重点观察延迟与正例准确率的边际变化。

## 4b. P0-4 演练（当日可安全执行的部分）

**坏文档 → 队列 → 手动重试闭环**（真实 API，独立临时用户+群组，doc 179/180）：

| 步骤 | 实测结果 |
| --- | --- |
| 上传 1KB 损坏 PDF（`%PDF-1.4` + 乱码） | 上传 200 秒回，`documents.status=PROCESSING` |
| ETL 失败（Spring Retry 2s/4s/8s 耗尽 → `@Recover`） | 文档置 **FAILED**，`failureReason="PDF 文档解析失败"`（用户可读，ADR-009 ✓） |
| `argus.job.dead_letter` 指标 | **全程 0.0** —— `recover` 吞异常正常返回，任务被 Worker 记为 SUCCEEDED，业务性失败**不进死信** |
| `POST /api/documents/{id}/retry-ingestion` | 200 接受（端点只放行 FAILED 文档）→ 状态复位 PROCESSING → 再次失败回到 FAILED，循环可重复 |

**架构口径修正（重要）**：此前文档（含本文档初版与 PROJECT_TRUTH）把队列退避与
Spring Retry 描述为"两层叠加"。实测证明二者**互斥**：业务性失败全部终结于
"文档 FAILED + 任务 SUCCEEDED"，队列的 1/5/15 分钟退避只保护未受 `@Retryable`
包裹的系统级异常（如领取/状态更新环节的 DB 故障）。由此推论一个**健壮性缺口**：
ETL 中的瞬态故障（如 MinIO 读抖动）也会被 `@Recover` 一次性判死为文档 FAILED，
队列的重试能力对它不可达。是否让 `recover` 重抛以启用队列级重试（代价：FAILED
状态可能回翻 READY 的状态抖动），留作设计决策，不在本轮擅自更改。

**未执行项与缺口**：
- Worker 崩溃租约接管演练：需杀进程后等 5 分钟租约过期，且租约接管语义已被
  `BackgroundJobServiceTest`（expiredLeaseIsClaimable 等 10 用例）单元层覆盖，
  当日未做实机演练。
- 死信（DEAD_LETTER）实机观察：业务失败不进死信（见上），死信语义由
  `BackgroundJobServiceTest`（failOrRetryDeadLettersAfterMaxAttempts、
  deadLetterJobIsRevivedOnEnqueue）单元覆盖。
- **ES 重建演练：无产品入口**——全库无 reindex/重建端点，ES 数据只能靠逐文档重跑
  ETL 恢复。建议列为 P1-4：增加管理员重建索引端点（从 document_chunks 回灌）。
- k6 压测：**本机未安装 k6**，`scripts/load/k6-*.js` 未执行。

## 5. 遗留与未验证

- 未做：ES 重建、MinIO 恢复、Worker 崩溃接管、死信复活等破坏性演练（P0-4，
  需单独窗口）；压测（k6 脚本未跑）。
- 冒烟脚本的 finance/cancel 问题已在当日修复并复跑至 126/126（见 §3）。
- 前端 P3 bug（管理员工作台 LLM 成功率显示 10000.0%，百分比双乘）**已修复**：
  后端 SQL 返回 0-100 百分数，`AdminDashboard.vue` 去掉多余 `*100`，
  浏览器复检显示 100.0%（npm test 14/14、build 成功）。
- **用户待办**：Agnes 控制台轮换 key（见 credential-hygiene-audit.md F10），
  轮换后更新 `.env`；本轮启动的服务为会话级后台进程，重启后按序重启（无自启）。
