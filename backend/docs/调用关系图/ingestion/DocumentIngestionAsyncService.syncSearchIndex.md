# ingestion · DocumentIngestionAsyncService.syncSearchIndex 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentIngestionAsyncService.syncSearchIndex :182"]
  m2["ElasticsearchChunkIndexService.indexReadyChunks :154"]
  m3["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m4["ElasticsearchChunkIndexService.sendBulkRequest :320"]
  m5["ElasticsearchChunkIndexService.indexExists :402"]
  m6["ElasticsearchChunkIndexService.createIndex :432"]
  m7["ElasticsearchChunkIndexService.sendJsonRequest :624"]

  m1 --> m2
  m2 --> m3
  m2 --> m4
  m3 --> m5
  m3 --> m6
  m6 --> m7
```
