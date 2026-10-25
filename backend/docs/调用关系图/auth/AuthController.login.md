# auth · AuthController.login 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthController.login :58"]
  m2["AuthService.login :76"]
  m3["AuthCookieSupport.writeRefreshTokenCookie :29"]
  m4["ApiResponse.success :24"]
  m5["AuthTokensResponse.from :15"]
  m6["AuthService.validateLoginCommand :187"]
  m7["AuthService.loadUserForLogin :248"]
  m8["AuthService.ensureUserCanLogin :269"]
  m9["RefreshTokenService.revokeActiveTokens :91"]
  m10["RefreshTokenService.issueToken :50"]
  m11["AuthService.updateSuccessfulLogin :286"]
  m12["AuthService.issueAccessToken :294"]
  m13["AuthService.toCurrentUser :159"]
  m14["AuthCookieSupport.buildCookie :41"]
  m15["AuthService.normalizeLoginId :202"]
  m16["AuthService.ensureUniqueLoginMatch :258"]
  m17["RefreshTokenService.newTokenSecret :134"]
  m18["RefreshTokenService.toRecord :140"]
  m19["JwtAccessTokenService.issueToken :45"]
  m20["CurrentUserService.CurrentUser :100"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m2 --> m6
  m2 --> m7
  m2 --> m8
  m2 --> m9
  m2 --> m10
  m2 --> m11
  m2 --> m12
  m2 --> m13
  m3 --> m14
  m6 --> m15
  m7 --> m16
  m10 --> m17
  m10 --> m18
  m12 --> m19
  m13 --> m20
```
