# excel · ExcelVersionController.create 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelVersionController.create :69"]
  m2["ExcelPermissionService.requireEdit :134"]
  m3["ApiResponse.success :24"]
  m4["ExcelVersionService.manualSnapshot :149"]
  m5["ExcelPermissionService.canEdit :92"]
  m6["ExcelDocumentLockService.withLock :17"]
  m7["ExcelPermissionService.permissionOf :69"]
  m8["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m2 --> m5
  m4 --> m6
  m5 --> m7
  m7 --> m8
```
