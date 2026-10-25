# excel · CollabController.handleEdit 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["CollabController.handleEdit :49"]
  m2["ExcelPermissionService.canEdit :92"]
  m3["CollabController.sendError :115"]
  m4["CollabService.handleEdit :72"]
  m5["ExcelPermissionService.permissionOf :69"]
  m6["ExcelSheetService.loadCellSnapshot :191"]
  m7["CollabService.toJSON :198"]
  m8["SnapshotService.touch :29"]
  m9["CollabService.persistEdit :186"]
  m10["ExcelPermissionService.currentUser :40"]
  m11["ExcelSheetService.intValue :643"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m2 --> m5
  m4 --> m6
  m4 --> m7
  m4 --> m8
  m4 --> m9
  m5 --> m10
  m6 --> m11
```
