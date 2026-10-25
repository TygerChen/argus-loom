# ingestion · DocumentIngestionAsyncService.cleanupProcessingArtifacts 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentIngestionAsyncService.cleanupProcessingArtifacts :157"]
  m2["VectorIngestionService.deleteDocumentVectors :92"]
  m3["ElasticsearchChunkIndexService.deleteDocumentChunks :171"]
  m4["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m5["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m6["ElasticsearchChunkIndexService.indexExists :402"]
  m7["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m4 --> m6
  m4 --> m7
```
