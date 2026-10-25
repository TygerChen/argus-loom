# document · DocumentController.initUpload 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.initUpload :95"]
  m2["ApiResponse.success :24"]
  m3["DocumentUploadService.initUpload :127"]
  m4["DocumentUploadService.validateInitRequest :346"]
  m5["GroupMembershipService.requireGroupOwner :76"]
  m6["DocumentUploadService.createInstantUploadedDocument :562"]
  m7["UploadInitResponse.instant :40"]
  m8["UploadInitResponse.uploadSession :52"]
  m9["DocumentUploadService.buildUploadSession :480"]
  m10["DocumentUploadService.requireGroupId :378"]
  m11["CurrentUserService.requireBusinessUser :66"]
  m12["GroupMembershipService.requireGroupId :113"]
  m13["DocumentUploadService.persistAndFinalizeUploadedDocument :641"]
  m14["DocumentFileStore.defaultBucket :29"]
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
  m5 --> m11
  m5 --> m12
  m6 --> m13
  m9 --> m14
  m13 --> m15
  m15 --> m16
```
