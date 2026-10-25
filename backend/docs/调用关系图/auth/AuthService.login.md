# auth · AuthService.login 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthService.login :76"]
  m2["AuthService.validateLoginCommand :187"]
  m3["AuthService.loadUserForLogin :248"]
  m4["AuthService.ensureUserCanLogin :269"]
  m5["RefreshTokenService.revokeActiveTokens :91"]
  m6["RefreshTokenService.issueToken :50"]
  m7["AuthService.updateSuccessfulLogin :286"]
  m8["AuthService.issueAccessToken :294"]
  m9["AuthService.toCurrentUser :159"]
  m10["AuthService.normalizeLoginId :202"]
  m11["AuthService.ensureUniqueLoginMatch :258"]
  m12["RefreshTokenService.newTokenSecret :134"]
  m13["RefreshTokenService.toRecord :140"]
  m14["JwtAccessTokenService.issueToken :45"]
  m15["CurrentUserService.CurrentUser :100"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m1 --> m8
  m1 --> m9
  m2 --> m10
  m3 --> m11
  m6 --> m12
  m6 --> m13
  m8 --> m14
  m9 --> m15
```
