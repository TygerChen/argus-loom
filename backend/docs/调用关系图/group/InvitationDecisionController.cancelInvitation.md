# group · InvitationDecisionController.cancelInvitation 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["InvitationDecisionController.cancelInvitation :41"]
  m2["GroupManagementService.cancelInvitation :117"]
  m3["ApiResponse.success :24"]
  m4["GroupManagementService.loadInvitation :193"]
  m5["GroupMembershipService.requireGroupOwner :76"]
  m6["GroupManagementService.requirePending :214"]
  m7["GroupManagementService.updateInvitationStatus :220"]
  m8["GroupManagementService.requirePositiveId :253"]
  m9["CurrentUserService.requireBusinessUser :66"]
  m10["GroupMembershipService.requireGroupId :113"]

  m1 --> m2
  m1 --> m3
  m2 --> m4
  m2 --> m5
  m2 --> m6
  m2 --> m7
  m4 --> m8
  m5 --> m9
  m5 --> m10
```
