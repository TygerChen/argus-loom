# auth · AuthService.logout 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthService.logout :148"]
  m2["RefreshTokenService.revokeToken :104"]
  m3["RefreshTokenService.findActiveToken :68"]
  m4["RefreshTokenService.revokeTokenById :116"]
  m5["RefreshTokenService.parseToken :122"]
  m6["RefreshTokenService.toRecord :140"]

  m1 --> m2
  m2 --> m3
  m2 --> m4
  m3 --> m5
  m3 --> m6
```
