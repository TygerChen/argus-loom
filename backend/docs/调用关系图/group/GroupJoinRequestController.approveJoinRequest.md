# group · GroupJoinRequestController.approveJoinRequest 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["GroupJoinRequestController.approveJoinRequest :56"]
  m2["GroupJoinRequestService.approveJoinRequest :79"]
  m3["ApiResponse.success :24"]
  m4["GroupJoinRequestService.requireOwnerAndLoadUser :107"]
  m5["GroupJoinRequestService.loadJoinRequest :127"]
  m6["GroupJoinRequestService.requireSameGroup :147"]
  m7["GroupJoinRequestService.requirePending :154"]
  m8["GroupJoinRequestService.rejectExistingMembership :141"]
  m9["GroupJoinRequestService.updateStatus :160"]
  m10["GroupJoinRequestService.requirePositiveId :172"]
  m11["GroupMembershipService.requireGroupOwner :76"]
  m12["CurrentUserService.requireBusinessUser :66"]
  m13["GroupMembershipService.requireGroupId :113"]

  m1 --> m2
  m1 --> m3
  m2 --> m4
  m2 --> m5
  m2 --> m6
  m2 --> m7
  m2 --> m8
  m2 --> m9
  m4 --> m10
  m4 --> m11
  m11 --> m12
  m11 --> m13
```
