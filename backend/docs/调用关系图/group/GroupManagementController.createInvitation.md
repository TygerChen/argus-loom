# group · GroupManagementController.createInvitation 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["GroupManagementController.createInvitation :48"]
  m2["ApiResponse.success :24"]
  m3["GroupManagementService.createInvitation :70"]
  m4["GroupManagementService.requirePositiveId :253"]
  m5["GroupMembershipService.requireGroupOwner :76"]
  m6["GroupManagementService.rejectMissingUser :187"]
  m7["GroupManagementService.rejectDuplicateInvitationTarget :171"]
  m8["CurrentUserService.requireBusinessUser :66"]
  m9["GroupMembershipService.requireGroupId :113"]
  m10["GroupManagementService.rejectExistingMembership :181"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m3 --> m6
  m3 --> m7
  m5 --> m8
  m5 --> m9
  m7 --> m10
```
