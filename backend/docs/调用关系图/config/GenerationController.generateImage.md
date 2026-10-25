# config · GenerationController.generateImage 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["GenerationController.generateImage :19"]
  m2["AgnesAIService.generateImage :28"]
  m3["ApiResponse.success :24"]

  m1 --> m2
  m2 --> m3
```
