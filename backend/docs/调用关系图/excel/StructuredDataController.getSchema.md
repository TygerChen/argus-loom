# excel · StructuredDataController.getSchema 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯注释说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略以减噪。

```mermaid
flowchart TD
  m1["StructuredDataController.getSchema :59"]
  m2["StructuredDataController.findSheet :116"]
  m3["ExcelSheetService.listChunksBySheetId :61"]
  m4["ApiResponse.success :24"]
  m5["TableSchemaService.extractSchema :45"]
  m6["ExcelSheetService.listRawChunksBySheetId :107"]
  m7["RedisChunkCacheService.putSheetChunks :109"]
  m8["ExcelSheetService.mergeDeltasInto :141"]
  m9["TableSchemaService.mergeAllCells :129"]
  m10["TableSchemaService.buildCellMatrix :150"]
  m11["TableSchemaService.buildColumnSchema :161"]
  m12["RedisChunkCacheService.write :179"]
  m13["RedisChunkCacheService.cacheKey :83"]
  m14["ExcelSheetService.loadDeltasBySheet :113"]
  m15["JsonCompat.parseArray :32"]
  m16["TableSchemaService.extractValueAsString :246"]
  m17["TableSchemaService.inferColumnType :205"]
  m18["RedisChunkCacheService.purgeExpired :185"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m5 --> m9
  m5 --> m10
  m5 --> m11
  m7 --> m12
  m7 --> m13
  m8 --> m14
  m9 --> m15
  m11 --> m16
  m11 --> m17
  m12 --> m18
```
