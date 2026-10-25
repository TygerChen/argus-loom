# document · DocumentController.uploadDocument 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.uploadDocument :163"]
  m2["CurrentUserService.requireBusinessUser :66"]
  m3["ApiResponse.success :24"]
  m4["DocumentUploadService.uploadDocument :530"]
  m5["DocumentUploadService.requireGroupId :378"]
  m6["GroupMembershipService.requireGroupOwner :76"]
  m7["DocumentUploadService.requireValidFile :601"]
  m8["DocumentUploadService.extractDirectFileName :611"]
  m9["DocumentFileStore.defaultBucket :29"]
  m10["DocumentFileStore.directObjectKey :45"]
  m11["DocumentUploadService.calculateSha256 :616"]
  m12["DocumentUploadService.uploadDirectFile :624"]
  m13["DocumentUploadService.persistAndFinalizeUploadedDocument :641"]
  m14["DocumentUploadService.compensateExternalIndexes :637"]
  m15["DocumentFileStore.deleteQuietly :66"]
  m16["GroupMembershipService.requireGroupId :113"]
  m17["FileHasher.sha256Hex :25"]
  m18["DocumentFileStore.putDirect :56"]
  m19["UploadedDocumentFinalizer.finalizeUploaded :49"]
  m20["UploadedDocumentFinalizer.compensateExternalIndexes :86"]
  m21["UploadedDocumentFinalizer.buildDocument :61"]
  m22["VectorIngestionService.deleteDocumentVectors :92"]
  m23["ElasticsearchChunkIndexService.deleteDocumentChunks :171"]
  m24["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m25["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m26["ElasticsearchChunkIndexService.indexExists :402"]
  m27["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m4 --> m5
  m4 --> m6
  m4 --> m7
  m4 --> m8
  m4 --> m9
  m4 --> m10
  m4 --> m11
  m4 --> m12
  m4 --> m13
  m4 --> m14
  m4 --> m15
  m6 --> m16
  m11 --> m17
  m12 --> m18
  m13 --> m19
  m14 --> m20
  m19 --> m21
  m20 --> m22
  m20 --> m23
  m23 --> m24
  m23 --> m25
  m24 --> m26
  m24 --> m27
```
