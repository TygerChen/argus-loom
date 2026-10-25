# 容量与告警 Runbook

## 指标抓取

- Prometheus 抓 /actuator/prometheus。
- 关键指标：argus.job.queued / running / dead_letter、argus.job.oldest_wait_seconds、
  llm.dialogue（P99/错误率）、hikaricp_connections_active、executor 线程池指标。
- 健康降级：/actuator/health 中 queue 分量（死信任务、Outbox 积压）。

## 初始告警阈值（方案任务 12 第 8 点）

| 告警 | 条件 | 处置 |
| --- | --- | --- |
| 任务积压 | argus.job.oldest_wait_seconds > 900 持续 5 分钟 | 看 Worker 日志；重启 Worker；检查依赖（ES/LLM） |
| 死信出现 | argus.job.dead_letter > 0 | 查 background_job.last_error_*，修复后人工重置为 QUEUED |
| 连接池 | hikaricp active / max > 0.8 持续 5 分钟 | 找慢 SQL；考虑加大池或加只读副本 |
| Outbox 积压 | queue 健康分量 DOWN（> 1000 PENDING） | 检查 WebSocket 广播；必要时清理 SENT 历史行 |
| LLM/ES 错误率 | llm.dialogue error 占比 > 5% 持续 5 分钟 | 切换降级路径；联系模型/ES 运维 |

## 容量参考（500 用户）

- 2 台 API（4C8G）足够；Worker 单独 1 台（2C4G）。
- 月度财务流水数万行内，内存聚合分析够用；超过 50 万行时把
  FinanceAnalysisService.loadRecords 的过滤条件下推 SQL。
- 任务队列 15 秒轮询、租约 5 分钟：单 Worker 每小时可处理数百个 ETL 任务；
  积压持续增长时先加 Worker 实例（SKIP LOCKED 天然支持多实例）。

## 中间件切换条件（当前未引入 RabbitMQ/Redis）

满足任一条再评估引入，避免过早增加运维负担：
1. 单 Worker 轮询吞吐成为瓶颈（argus.job.oldest_wait_seconds 长期 > 5 分钟）。
2. 协同 Outbox 广播延迟（500ms 轮询）影响多人编辑体验。
3. 单机内存限流不够精细，需要全局限流（Redis 滑动窗口）。
