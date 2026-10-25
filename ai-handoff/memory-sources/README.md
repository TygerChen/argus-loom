# 记忆原件说明

本目录保存交接时的历史记忆来源，便于后续 AI 理解项目演进。

- `MEMORY.md`：项目级旧速查记忆。
- `MEMORY-知识库问答.md`：RAG 专项旧记忆。
- `CODEX_MEMORY_EXCERPT.md`：从本机 Codex 记忆索引中提取的 Argus Loom 相关段落。

这些文件可能包含已被修正的旧事实。当前事实以：

1. 当前源码和测试
2. `ai-handoff/PROJECT_TRUTH.md`

为准。

已知旧事实：

- 根 `MEMORY.md` 说“目录不是 Git 仓库”，但当前是 Git 仓库。
- 旧 ETL 事件链已被 `background_job` 替代。
- 旧测试总数 1616 不是 2026-10-23 当前实测值 1614。
- 旧架构图和部分描述提到 Redis，当前没有真实 Redis 依赖。
