# document · DocumentController.previewDocument 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.previewDocument :259"]
  m2["CurrentUserService.requireBusinessUser :66"]
  m3["ApiResponse.success :24"]
  m4["DocumentPreviewService.previewDocument :59"]
  m5["DocumentPreviewService.requireGroupId :144"]
  m6["GroupMembershipService.requireGroupReadable :66"]
  m7["DocumentPreviewService.loadDocument :130"]
  m8["DocumentRoleGate.canRead :103"]
  m9["DocumentRoleGate.currentRole :42"]
  m10["DocumentPreviewService.readRawContent :107"]
  m11["GroupMembershipService.requireGroupId :113"]
  m12["DocumentRoleGate.allowedRoleNames :88"]
  m13["UserContext.get :22"]
  m14["DocumentPreviewService.resolveBucket :123"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m4 --> m5
  m4 --> m6
  m4 --> m7
  m4 --> m8
  m4 --> m9
  m4 --> m10
  m6 --> m11
  m8 --> m12
  m9 --> m13
  m10 --> m14
```
