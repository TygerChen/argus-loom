# group · GroupManagementController.removeMember 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["GroupManagementController.removeMember :69"]
  m2["GroupManagementService.removeMember :140"]
  m3["ApiResponse.success :24"]
  m4["GroupManagementService.requirePositiveId :253"]
  m5["GroupMembershipService.requireGroupOwner :76"]
  m6["CurrentUserService.requireBusinessUser :66"]
  m7["GroupMembershipService.requireGroupId :113"]

  m1 --> m2
  m1 --> m3
  m2 --> m4
  m2 --> m5
  m5 --> m6
  m5 --> m7
```
