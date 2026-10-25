# ingestion · ChunkService.buildChunkDocuments 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["ChunkService.buildChunkDocuments :128"]
  m2["ChunkService.normalizeChunkDocumentText :148"]
  m3["ChunkService.resolveChunkRange :159"]
  m4["ChunkService.buildChunk :210"]
  m5["ChunkService.fallbackRange :183"]
  m6["ChunkService.readMetadataInt :261"]
  m7["ChunkService.safeAdd :204"]
  m8["ChunkService.buildSummary :227"]
  m9["ChunkService.buildMetadataJson :235"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m3 --> m5
  m3 --> m6
  m3 --> m7
  m4 --> m8
  m4 --> m9
```
