# excel · ExcelVersionController.preview 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelVersionController.preview :59"]
  m2["ExcelPermissionService.requireView :130"]
  m3["ExcelVersionService.previewVersion :221"]
  m4["ApiResponse.success :24"]
  m5["ExcelPermissionService.canView :88"]
  m6["ExcelVersionService.versionMap :385"]
  m7["ExcelVersionService.listVersionSheets :345"]
  m8["ExcelVersionService.parseObject :406"]
  m9["ExcelVersionService.parseArray :416"]
  m10["ExcelVersionService.listVersionChunks :351"]
  m11["ExcelPermissionService.permissionOf :69"]
  m12["ExcelPermissionService.currentUser :40"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m2 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m3 --> m9
  m3 --> m10
  m5 --> m11
  m11 --> m12
```
