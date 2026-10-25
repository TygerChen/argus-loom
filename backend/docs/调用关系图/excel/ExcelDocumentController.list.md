# excel · ExcelDocumentController.list 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelDocumentController.list :67"]
  m2["ExcelPermissionService.accessibleDocumentIds :170"]
  m3["ExcelDocumentService.listAccessibleByPage :97"]
  m4["ExcelPermissionService.permissionOf :69"]
  m5["ExcelPermissionService.canEdit :92"]
  m6["ExcelPermissionService.canManage :146"]
  m7["ApiResponse.success :24"]
  m8["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m2 --> m8
```
