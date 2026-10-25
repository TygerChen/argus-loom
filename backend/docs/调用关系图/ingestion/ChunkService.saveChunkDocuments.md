# ingestion · ChunkService.saveChunkDocuments 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ChunkService.saveChunkDocuments :69"]
  m2["ChunkService.validateIdentifiers :121"]
  m3["ChunkService.buildChunkDocuments :128"]
  m4["ChunkService.persistChunkBatches :84"]
  m5["ChunkService.backfillChunkIds :96"]
  m6["ChunkService.normalizeChunkDocumentText :148"]
  m7["ChunkService.resolveChunkRange :159"]
  m8["ChunkService.buildChunk :210"]
  m9["ChunkService.fallbackRange :183"]
  m10["ChunkService.readMetadataInt :261"]
  m11["ChunkService.safeAdd :204"]
  m12["ChunkService.buildSummary :227"]
  m13["ChunkService.buildMetadataJson :235"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m7 --> m9
  m7 --> m10
  m7 --> m11
  m8 --> m12
  m8 --> m13
```
