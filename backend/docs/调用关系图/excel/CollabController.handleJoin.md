# excel · CollabController.handleJoin 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["CollabController.handleJoin :83"]
  m2["ExcelPermissionService.canView :88"]
  m3["CollabController.sendError :115"]
  m4["OnlineUserTracker.userJoin :46"]
  m5["CollabService.joinRoom :152"]
  m6["ExcelPermissionService.canEdit :92"]
  m7["ExcelPermissionService.permissionOf :69"]
  m8["OnlineUserTracker.broadcastUserList :89"]
  m9["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m2 --> m7
  m4 --> m8
  m7 --> m9
```
