# 容量压测报告模板

```markdown
# 容量压测报告（YYYY-MM-DD）

## 环境
- 拓扑: 2 API（4C8G）+ 1 Worker + PostgreSQL/ES/MinIO
- 数据量: 文档 <N> / 流水 <N> 行 / 并发 WebSocket <N>

## 负载
- k6 场景: knowledge-read / rag-stream / excel-collab
- 虚拟用户: 100，时长: 30 分钟

## 结果
| 指标 | 本次 | 门槛 | 结论 |
| --- | --- | --- | --- |
| HTTP 错误率（不含限流） | % | < 1% | |
| 知识库读 P95 | ms | < 500 | |
| 检索 P95（不含 LLM） | s | < 2 | |
| 首 Token P95 | s | < 5 | |
| 协同事件 P95 | ms | < 500 | |
| 任务重复执行 | 个 | 0 | |
| 连接池/Executor 饱和 | 次 | 0 | |

## 故障演练
| 演练 | 结果 |
| --- | --- |
| 滚动重启一台 API | 编辑用户重连可继续；任务不重复 |

## 瓶颈与后续扩容建议
```
