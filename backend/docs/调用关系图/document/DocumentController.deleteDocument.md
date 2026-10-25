# document · DocumentController.deleteDocument 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.deleteDocument :198"]
  m2["CurrentUserService.requireBusinessUser :66"]
  m3["DocumentDeleteService.softDeleteDocument :43"]
  m4["ApiResponse.success :24"]
  m5["DocumentDeleteService.requireGroupId :88"]
  m6["GroupMembershipService.requireGroupOwner :76"]
  m7["VectorIngestionService.deleteDocumentVectors :92"]
  m8["ElasticsearchChunkIndexService.deleteDocumentChunks :171"]
  m9["GroupMembershipService.requireGroupId :113"]
  m10["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m11["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m12["ElasticsearchChunkIndexService.indexExists :402"]
  m13["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m3 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m6 --> m9
  m8 --> m10
  m8 --> m11
  m10 --> m12
  m10 --> m13
```
