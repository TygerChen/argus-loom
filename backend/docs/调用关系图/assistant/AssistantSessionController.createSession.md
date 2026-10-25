# assistant · AssistantSessionController.createSession 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AssistantSessionController.createSession :59"]
  m2["ApiResponse.success :24"]
  m3["AssistantSessionService.createSession :54"]
  m4["CurrentUserService.requireBusinessUser :66"]
  m5["AssistantSessionService.buildNewSession :235"]
  m6["AssistantSessionService.toDetailVO :254"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m3 --> m6
```
