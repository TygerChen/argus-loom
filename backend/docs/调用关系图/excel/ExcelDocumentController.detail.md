# excel · ExcelDocumentController.detail 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelDocumentController.detail :90"]
  m2["ExcelPermissionService.requireView :130"]
  m3["ExcelSheetService.listSheetsByDocumentId :48"]
  m4["ExcelDocumentController.parseObjectOrEmpty :216"]
  m5["ExcelDocumentController.parseArrayOrEmpty :220"]
  m6["ApiResponse.success :24"]
  m7["ExcelPermissionService.permissionOf :69"]
  m8["ExcelPermissionService.canEdit :92"]
  m9["ExcelPermissionService.canManage :146"]
  m10["ExcelPermissionService.canView :88"]
  m11["JsonCompat.parseObject :21"]
  m12["JsonCompat.parseArray :32"]
  m13["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m1 --> m8
  m1 --> m9
  m2 --> m10
  m4 --> m11
  m5 --> m12
  m7 --> m13
```
