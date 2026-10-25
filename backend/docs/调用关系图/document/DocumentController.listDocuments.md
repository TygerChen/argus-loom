# document · DocumentController.listDocuments 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["DocumentController.listDocuments :180"]
  m2["ApiResponse.success :24"]
  m3["DocumentQueryService.listDocuments :41"]
  m4["DocumentQueryService.normalizeQuery :78"]
  m5["CurrentUserService.requireBusinessUser :66"]
  m6["DocumentRoleGate.canRead :103"]
  m7["DocumentQueryService.requireGroupId :123"]
  m8["GroupMembershipService.requireGroupReadable :66"]
  m9["DocumentQueryService.normalizeGroupRelation :106"]
  m10["DocumentQueryService.normalizeStatus :115"]
  m11["DocumentRoleGate.allowedRoleNames :88"]
  m12["GroupMembershipService.requireGroupId :113"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m3 --> m6
  m4 --> m7
  m4 --> m8
  m4 --> m9
  m4 --> m10
  m6 --> m11
  m8 --> m12
```
