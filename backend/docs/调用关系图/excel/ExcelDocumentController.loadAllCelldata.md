# excel · ExcelDocumentController.loadAllCelldata 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ExcelDocumentController.loadAllCelldata :126"]
  m2["ExcelPermissionService.requireView :130"]
  m3["ExcelSheetService.loadMergedCelldata :220"]
  m4["ApiResponse.success :24"]
  m5["ExcelPermissionService.canView :88"]
  m6["ExcelSheetService.listChunksBySheetId :61"]
  m7["JsonCompat.parseArray :32"]
  m8["ExcelPermissionService.permissionOf :69"]
  m9["ExcelSheetService.listRawChunksBySheetId :107"]
  m10["RedisChunkCacheService.putSheetChunks :109"]
  m11["ExcelSheetService.mergeDeltasInto :141"]
  m12["ExcelPermissionService.currentUser :40"]
  m13["RedisChunkCacheService.write :179"]
  m14["RedisChunkCacheService.cacheKey :83"]
  m15["ExcelSheetService.loadDeltasBySheet :113"]
  m16["RedisChunkCacheService.purgeExpired :185"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m2 --> m5
  m3 --> m6
  m3 --> m7
  m5 --> m8
  m6 --> m9
  m6 --> m10
  m6 --> m11
  m8 --> m12
  m10 --> m13
  m10 --> m14
  m11 --> m15
  m13 --> m16
```
