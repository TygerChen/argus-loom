# qa · QaRecordController.listRecords 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["QaRecordController.listRecords :39"]
  m2["ApiResponse.success :24"]
  m3["QaRecordQueryService.list :45"]
  m4["QaRecordQueryService.normalizePageSize :103"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
```
