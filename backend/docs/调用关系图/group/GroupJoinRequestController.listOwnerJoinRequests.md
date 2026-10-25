# group · GroupJoinRequestController.listOwnerJoinRequests 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["GroupJoinRequestController.listOwnerJoinRequests :47"]
  m2["ApiResponse.success :24"]
  m3["GroupJoinRequestService.listOwnerJoinRequests :71"]
  m4["GroupJoinRequestService.requirePositiveId :172"]
  m5["GroupMembershipService.requireGroupOwner :76"]
  m6["CurrentUserService.requireBusinessUser :66"]
  m7["GroupMembershipService.requireGroupId :113"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m5 --> m6
  m5 --> m7
```
