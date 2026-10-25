# Argus Loom AI 交接入口

> 交接生成时间：2026-10-23（Asia/Shanghai）
> 仓库：`F:\ChatGPT\argus-loom`
> 分支：`master`
> 当前基线提交：`2d7ed06`
> 工作区状态：包含用户未提交文档、脚本和 `MEMORY.md` 修改

这份交接包的目标是让一个新的 AI 编码代理在不通读全部历史会话的情况下，
快速理解项目、避免踩红线，并能继续制定和实施扩展方案。

## 60 秒理解项目

Argus Loom 是一个面向企业内部的知识库与财务 Excel 系统：

1. 用户上传 PDF、Word、Markdown、TXT。
2. 后台任务把文件解析、清洗、切片、向量化并写入 pgvector 和 Elasticsearch。
3. 用户提问时，系统做查询规划、双路召回、RRF 融合、权限过滤、邻居扩展、
   证据评估，再调用大模型生成带引用的回答。
4. 另一个 AI 助手通过 Spring AI Alibaba ReactAgent 调用知识库、结构化数据和
   网络搜索工具。
5. 在线 Excel 使用 UniverJS + WebSocket/STOMP，后端用 chunk + delta 持久化，
   支持版本、审计、导出、回收站。
6. 财务模块支持台账导入、期间关闭、异常分析、薪酬考勤和报表。

## 先读顺序

| 顺序 | 文件 | 目的 |
| --- | --- | --- |
| 1 | `ai-handoff/PROJECT_TRUTH.md` | 当前事实、数字、已知偏差 |
| 2 | `ai-handoff/ARCHITECTURE.md` | 包结构、数据流、请求旅程 |
| 3 | `ai-handoff/DEVELOPMENT_GUIDE.md` | 环境、启动、测试、排错 |
| 4 | `ai-handoff/MEMORY_EXPORT.md` | 历史决策、用户偏好、旧记忆纠偏 |
| 5 | `ai-handoff/DECISIONS_AND_CONSTRAINTS.md` | 架构决策与不可破坏的约束 |
| 6 | `ai-handoff/ROADMAP_AND_BACKLOG.md` | 当前风险与建议演进顺序 |
| 7 | `ai-handoff/SOURCE_AND_DOC_INDEX.md` | 全项目文档和源码索引 |
| 8 | `ai-handoff/ZCODE_START_PROMPTS.md` | 可直接粘贴给 ZCode 的任务提示词 |

## 当前已验证状态

- 后端：`mvn -o test` 共 `1634` tests，`0` failures，`0` errors（2026-10-24 实测）。
- 前端：`npm test` 共 `14` tests，全部通过。
- 前端：`npm run build` 成功。
- 构建告警：Univer vendor chunk 约 11.4 MB，尚未做 manualChunks/懒加载优化。

历史记忆中曾记录 `1616`、交接初版记录 `1614`，均为各自时点的实测值。当前实测
`1634`（2026-10-24，含权限修复与队列/配置守卫新增测试）。后续任何测试数量都应重新运行后记录。

## 绝对不要先做的事

1. 不要凭旧图认为系统已有 Redis、RabbitMQ 或真实 reranker。
2. 不要绕过 `DocumentRoleGate`、Excel ACL 或财务角色校验“先跑通再说”。
3. 不要修改已执行的 Flyway `V1`-`V8` 迁移。
4. 不要把 Excel 导出擅自迁到通用 `background_job` 队列。
5. 不要读取或打包 `.env`、`backend/upload/`、日志和真实财务数据。
6. 不要在没有追踪生产调用方的情况下声称某条链路“已经生效”。

## ZCode 第一次进入项目的响应协议

让 ZCode 先做“只读冷启动”，不要立刻写代码。它应输出：

1. 项目定位和三段核心链路摘要。
2. 与当前任务相关的模块、入口类、服务、Mapper、表和测试。
3. 已确认事实与待验证假设，分开列出。
4. 实施计划、测试计划、风险与回滚方式。
5. 等用户确认后再修改代码。

可直接使用：

```text
你现在接手 F:\ChatGPT\argus-loom。
先阅读 AGENTS.md、ai-handoff/START_HERE.md、PROJECT_TRUTH.md、ARCHITECTURE.md、
DEVELOPMENT_GUIDE.md、MEMORY_EXPORT.md、DECISIONS_AND_CONSTRAINTS.md。
本轮只做只读分析，不修改代码。请先核实当前源码和测试，再输出：
1) 项目核心链路；2) 与本次任务相关的真实调用方和状态流；
3) 已确认事实与待验证假设；4) 分阶段实施方案；5) 测试与回滚计划。
不要相信旧文档中的测试数字、Redis、真实 reranker 或 ETL 事件链描述，
必须以当前代码和测试为准。
```

## 一句话交接原则

先确认真实调用方，再改代码；先锁权限和失败路径，再谈功能；所有“已完成”都必须有
代码、测试或运行证据。
