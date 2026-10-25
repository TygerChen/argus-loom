# assistant · AssistantAgentFacade.handleStreamingOutput 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AssistantAgentFacade.handleStreamingOutput :289"]
  m2["AssistantAgentFacade.normalizeStreamingDelta :321"]

  m1 --> m2
```
