# auth · AuthService.refresh 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthService.refresh :117"]
  m2["RefreshTokenService.findActiveToken :68"]
  m3["AuthService.ensureRefreshAllowed :279"]
  m4["RefreshTokenService.revokeTokenById :116"]
  m5["RefreshTokenService.revokeActiveTokens :91"]
  m6["RefreshTokenService.issueToken :50"]
  m7["AuthService.issueAccessToken :294"]
  m8["AuthService.toCurrentUser :159"]
  m9["RefreshTokenService.parseToken :122"]
  m10["RefreshTokenService.toRecord :140"]
  m11["RefreshTokenService.newTokenSecret :134"]
  m12["JwtAccessTokenService.issueToken :45"]
  m13["CurrentUserService.CurrentUser :100"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m1 --> m8
  m2 --> m9
  m2 --> m10
  m6 --> m11
  m7 --> m12
  m8 --> m13
```
