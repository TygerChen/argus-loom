# 多实例部署状态审计（P0-2 证据矩阵）

> 审计时间：2026-10-24（只读核实，基线提交 `2d7ed06` 之后、`892982b` 前后）
> 结论先行：**当前系统按单实例设计并验证。任何双 API 实例部署前，下表"双实例表现"
> 列为红/黄的项必须逐项处理**。唯一为多实例设计过的组件是 background_job Worker。

## 1. 证据矩阵

| # | 组件 | 实现机制（file:line） | 单实例 | 双实例表现 | 演进方向 |
| --- | --- | --- | --- | --- | --- |
| 1 | 接口限流 `RateLimitAspect` | 进程内 `ConcurrentHashMap` 滑动窗口（`common/ratelimit/RateLimitAspect.java:29-32`，javadoc 自述建议换 Redis） | ✅ 正确 | 🔴 每实例独立计数，实际放行量 = 配置值 × 实例数 | Redis 滑动窗口或网关层限流 |
| 2 | Excel chunk 缓存 `RedisChunkCacheService` | 进程内 LRU（512 条）+ TTL 120s（`excel/service/RedisChunkCacheService.java:35-43`，类名历史遗留，非 Redis） | ✅ 正确 | 🟡 写实例失效缓存，其他实例最长 120s 读到旧 celldata（靠 TTL 自愈） | Redis / Pub-Sub 失效广播 |
| 3 | 在线用户 `OnlineUserTracker` | 两个进程内 Map + STOMP 断连事件清理（`excel/collab/OnlineUserTracker.java:27-30,80-87`）；DB 表 `excel_collab_room` 只存房间占用，不构成跨实例在线名单 | ✅ 正确 | 🟡 每实例只看到连到自己的用户；"在线列表"跨实例不全 | Broker relay 或 Redis 在线集合 |
| 4 | 文档写锁 `ExcelDocumentLockService` | JVM 内 `ConcurrentHashMap<Long, ReentrantLock>`（`excel/service/ExcelDocumentLockService.java:13-28`） | ✅ 正确 | 🔒🔒 两个实例可同时持"锁"；数据正确性靠 revision 冲突检查兜底（`CollabService.java:110-123`），但锁的互斥语义失效 | DB 咨询锁 / Redis 锁 |
| 5 | STOMP 广播 | `enableSimpleBroker("/topic")`（`excel/config/WebSocketConfig.java:56`）——纯内存 broker，各实例只认识自己的会话 | ✅ 正确 | 🔴 直发只能到达保存实例上的订阅者 | RabbitMQ STOMP broker relay 或 Redis pub/sub 桥 |
| 6 | Outbox 重放 `ExcelCollabEventPublisher` | 每 500ms 普通 `SELECT ... WHERE status='PENDING' LIMIT 50`（`mappers/excel/ExcelCollabOutboxMapper.xml:6-12`，**无 SKIP LOCKED 认领**），广播后无条件标 SENT（`ExcelCollabEventPublisher.java:44-53`） | ✅ 正确（直发+重放双路径，客户端按 requestId 去重） | 🔴 **存在真实丢失窗口**：实例 A 标 SENT 提交后，实例 B 的下一次轮询再也看不到该行，而 B 上的订阅者只能靠 B 本地广播——该事件对 B 的用户**永久丢失**（不只是重复投递） | 先解决 #5；Outbox 语义需改为"每实例本地投递成功后再标已投递"（如实例收件箱表） |
| 7 | 导出悬挂清扫 `sweepStaleTasks` | 5 分钟一轮，跳过**本进程** `runningFutures` 中的任务（`ExcelExportService.java:262-279`） | ✅ 正确 | 🟡 实例 B 不认识实例 A 正在跑的任务；超过 30 分钟的长导出可能被 B 误标失败 | 清扫按 `lease/owner` 字段排除他实例任务 |
| 8 | 导出过期清扫 `sweepExpiredTasks` | 按 `expire_at` 状态扫描置 EXPIRED（`ExcelExportService.java:379-405`） | ✅ | 🟢 幂等状态清扫，重复执行无害 | 无需处理 |
| 9 | 定时快照 `SnapshotService` | `lastEditTimes` 进程内 Map（`SnapshotService.java:21`），每实例只快照**经自己**编辑过的活跃文档 | ✅ | 🟢 覆盖由编辑实例保证；同文档双实例编辑会各快照一次（重复但无害，有保留裁剪） | 可接受；跨实例"活跃"判定如需精确再演进 |
| 10 | 后台任务 Worker | `FOR UPDATE SKIP LOCKED` 领取 + 租约接管 + 条件 UPDATE 认领（`BackgroundJobMapper.xml:12-30`、`BackgroundJobService.claimDueJobs`） | ✅ | 🟢 **为多实例设计**，可直接水平扩 Worker | 无需处理（保持 at-least-once + 幂等约定） |
| 11 | 队列指标 `JobQueueMetrics` | 每 30s 刷新进程内 Gauge（`JobQueueMetrics.java:46`） | ✅ | 🟢 各实例各自上报，Prometheus 按实例抓取即可 | 无需处理 |
| 12 | 分片上传 / SHA-256 秒传 | 会话与去重状态全在 PostgreSQL（`document_upload_sessions` 等） | ✅ | 🟢 DB 共享，天然跨实例 | 无需处理 |
| 13 | 认证 | JWT 无状态 + refresh token 轮换在 DB（`user_refresh_tokens`） | ✅ | 🟢 任意实例可验签与轮换 | 无需处理 |
| 14 | 存储/检索后端 | MinIO / ES / PostgreSQL 均为独立共享服务 | ✅ | 🟢 天然共享 | 无需处理 |

## 2. 结论与部署建议

- **可以现在做的水平扩展**：只扩 Worker 实例（跑同一镜像、只开 `@Scheduled` 轮询所需的
  最小依赖）是安全的——队列领取协议就是为此设计。
- **不能直接做的**：API 实例 ≥ 2。会先踩 #6（协同消息跨实例丢失）、#5（直发不可达）、
  #1（限流放大）、#4（锁失效）；#2/#3/#7 表现为体验问题而非数据错误。
- **最小改造路径**（供评审，非现状）：
  1. 引入 Redis：覆盖 #1 限流、#2 缓存、#3 在线状态、#4 锁；
  2. STOMP broker relay（RabbitMQ）或 Redis pub/sub 桥覆盖 #5；
  3. #6 随 #5 的方案重新设计（outbox → 广播确认）；
  4. #7 给导出任务加实例归属标记。
- 每一项落地前按 ROADMAP P0-2 要求先做双实例滚动测试，不凭本表直接上线。

## 3. 审计方法

逐项追踪生产调用方与实现源码（非类名推断）：`RateLimitAspect` 全量读源码；
Outbox 语义核实到 Mapper SQL（确认无认领语义）；`@Scheduled` 全量清单
`grep -rn "@Scheduled"` 共 6 处，逐一归类。与 `ai-handoff/DECISIONS_AND_CONSTRAINTS.md`
ADR-006 的"引入 Redis 前必须逐项审计本地状态"对应。
