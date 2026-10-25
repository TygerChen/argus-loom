# excel · ExcelVersionController.detail 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelVersionController.detail :49"]
  m2["ExcelPermissionService.requireView :130"]
  m3["ExcelVersionService.versionDetail :201"]
  m4["ApiResponse.success :24"]
  m5["ExcelPermissionService.canView :88"]
  m6["ExcelVersionService.versionMap :385"]
  m7["ExcelVersionService.listVersionSheets :345"]
  m8["ExcelPermissionService.permissionOf :69"]
  m9["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m2 --> m5
  m3 --> m6
  m3 --> m7
  m5 --> m8
  m8 --> m9
```
