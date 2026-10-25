# document · DocumentController.uploadChunk 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.uploadChunk :113"]
  m2["DocumentUploadService.uploadChunk :183"]
  m3["ApiResponse.success :24"]
  m4["DocumentUploadService.requireOwnedActiveSession :405"]
  m5["DocumentUploadService.requireChunk :434"]
  m6["DocumentFileStore.chunkKey :34"]
  m7["FileHasher.sha256Hex :25"]
  m8["DocumentFileStore.putChunk :51"]
  m9["DocumentFileStore.deleteQuietly :66"]
  m10["GroupMembershipService.requireGroupOwner :76"]
  m11["CurrentUserService.requireBusinessUser :66"]
  m12["GroupMembershipService.requireGroupId :113"]

  m1 --> m2
  m1 --> m3
  m2 --> m4
  m2 --> m5
  m2 --> m6
  m2 --> m7
  m2 --> m8
  m2 --> m9
  m4 --> m10
  m10 --> m11
  m10 --> m12
```
