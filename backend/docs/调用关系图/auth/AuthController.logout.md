# auth · AuthController.logout 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthController.logout :86"]
  m2["AuthService.logout :148"]
  m3["AuthController.extractRefreshToken :98"]
  m4["AuthCookieSupport.clearRefreshTokenCookie :37"]
  m5["ApiResponse.success :24"]
  m6["RefreshTokenService.revokeToken :104"]
  m7["AuthCookieSupport.buildCookie :41"]
  m8["RefreshTokenService.findActiveToken :68"]
  m9["RefreshTokenService.revokeTokenById :116"]
  m10["RefreshTokenService.parseToken :122"]
  m11["RefreshTokenService.toRecord :140"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m2 --> m6
  m4 --> m7
  m6 --> m8
  m6 --> m9
  m8 --> m10
  m8 --> m11
```
