# ingestion · DocumentIngestionAsyncService.recover 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentIngestionAsyncService.recover :122"]
  m2["DocumentIngestionAsyncService.cleanupProcessingArtifacts :157"]
  m3["DocumentIngestionAsyncService.markDocumentStatus :205"]
  m4["DocumentIngestionAsyncService.truncateFailureReason :225"]
  m5["VectorIngestionService.deleteDocumentVectors :92"]
  m6["ElasticsearchChunkIndexService.deleteDocumentChunks :171"]
  m7["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m8["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m9["ElasticsearchChunkIndexService.indexExists :402"]
  m10["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m2 --> m5
  m2 --> m6
  m6 --> m7
  m6 --> m8
  m7 --> m9
  m7 --> m10
```
