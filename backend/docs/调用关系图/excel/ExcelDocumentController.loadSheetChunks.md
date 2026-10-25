# excel · ExcelDocumentController.loadSheetChunks 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelDocumentController.loadSheetChunks :138"]
  m2["ExcelPermissionService.requireView :130"]
  m3["ExcelSheetService.loadChunksByIndexRange :179"]
  m4["JsonCompat.parseArray :32"]
  m5["ApiResponse.success :24"]
  m6["ExcelPermissionService.canView :88"]
  m7["ExcelSheetService.mergeDeltasInto :141"]
  m8["ExcelPermissionService.permissionOf :69"]
  m9["ExcelSheetService.loadDeltasBySheet :113"]
  m10["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m2 --> m6
  m3 --> m7
  m6 --> m8
  m7 --> m9
  m8 --> m10
```
