# document · DocumentController.completeUpload 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.completeUpload :146"]
  m2["ApiResponse.success :24"]
  m3["DocumentUploadService.completeUpload :260"]
  m4["DocumentUploadService.requireOwnedActiveSession :405"]
  m5["DocumentUploadService.ensureAllChunksPresent :460"]
  m6["DocumentFileStore.finalObjectKey :39"]
  m7["DocumentFileStore.compose :61"]
  m8["DocumentUploadService.finalizeUploadedDocument :584"]
  m9["DocumentFileStore.deleteQuietly :66"]
  m10["GroupMembershipService.requireGroupOwner :76"]
  m11["DocumentUploadService.persistAndFinalizeUploadedDocument :641"]
  m12["DocumentUploadService.requireGroupId :378"]
  m13["CurrentUserService.requireBusinessUser :66"]
  m14["GroupMembershipService.requireGroupId :113"]
  m15["UploadedDocumentFinalizer.finalizeUploaded :49"]
  m16["UploadedDocumentFinalizer.buildDocument :61"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m3 --> m9
  m4 --> m10
  m8 --> m11
  m8 --> m12
  m10 --> m13
  m10 --> m14
  m11 --> m15
  m15 --> m16
```
