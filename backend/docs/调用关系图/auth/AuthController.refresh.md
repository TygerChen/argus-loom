# auth · AuthController.refresh 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthController.refresh :77"]
  m2["AuthController.extractRefreshToken :98"]
  m3["AuthService.refresh :117"]
  m4["AuthCookieSupport.writeRefreshTokenCookie :29"]
  m5["ApiResponse.success :24"]
  m6["AuthTokensResponse.from :15"]
  m7["RefreshTokenService.findActiveToken :68"]
  m8["AuthService.ensureRefreshAllowed :279"]
  m9["RefreshTokenService.revokeTokenById :116"]
  m10["RefreshTokenService.revokeActiveTokens :91"]
  m11["RefreshTokenService.issueToken :50"]
  m12["AuthService.issueAccessToken :294"]
  m13["AuthService.toCurrentUser :159"]
  m14["AuthCookieSupport.buildCookie :41"]
  m15["RefreshTokenService.parseToken :122"]
  m16["RefreshTokenService.toRecord :140"]
  m17["RefreshTokenService.newTokenSecret :134"]
  m18["JwtAccessTokenService.issueToken :45"]
  m19["CurrentUserService.CurrentUser :100"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m3 --> m7
  m3 --> m8
  m3 --> m9
  m3 --> m10
  m3 --> m11
  m3 --> m12
  m3 --> m13
  m4 --> m14
  m7 --> m15
  m7 --> m16
  m11 --> m17
  m12 --> m18
  m13 --> m19
```
