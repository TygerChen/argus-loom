# excel · StructuredDataController.queryData 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["StructuredDataController.queryData :109"]
  m2["StructuredDataController.findSheet :116"]
  m3["ExcelSheetService.listChunksBySheetId :61"]
  m4["TableIngestionService.queryStructuredData :99"]
  m5["ApiResponse.success :24"]
  m6["ExcelSheetService.listRawChunksBySheetId :107"]
  m7["RedisChunkCacheService.putSheetChunks :109"]
  m8["ExcelSheetService.mergeDeltasInto :141"]
  m9["TableSchemaService.extractSchema :45"]
  m10["TableIngestionService.resolveColumnIndex :145"]
  m11["CellDataTable.fromChunks :45"]
  m12["CellDataTable.valueAsText :103"]
  m13["ColumnFilterStrategy.from :56"]
  m14["TableIngestionService.buildRowResult :177"]
  m15["RedisChunkCacheService.write :179"]
  m16["RedisChunkCacheService.cacheKey :83"]
  m17["ExcelSheetService.loadDeltasBySheet :113"]
  m18["TableSchemaService.mergeAllCells :129"]
  m19["TableSchemaService.buildCellMatrix :150"]
  m20["TableSchemaService.buildColumnSchema :161"]
  m21["JsonCompat.parseArray :32"]
  m22["CellDataTable.merge :65"]
  m23["CellDataTable.CellDataTable :33"]
  m24["RedisChunkCacheService.purgeExpired :185"]
  m25["TableSchemaService.extractValueAsString :246"]
  m26["TableSchemaService.inferColumnType :205"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m4 --> m9
  m4 --> m10
  m4 --> m11
  m4 --> m12
  m4 --> m13
  m4 --> m14
  m7 --> m15
  m7 --> m16
  m8 --> m17
  m9 --> m18
  m9 --> m19
  m9 --> m20
  m11 --> m21
  m11 --> m22
  m11 --> m23
  m15 --> m24
  m20 --> m25
  m20 --> m26
```
