# auth · AuthService.register 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthService.register :97"]
  m2["AuthService.validateRegisterCommand :170"]
  m3["AuthService.ensureUniqueIdentity :238"]
  m4["AuthService.normalizeUsername :214"]
  m5["AuthService.normalizeRequiredValue :226"]
  m6["PasswordPolicyValidator.validate :23"]

  m1 --> m2
  m1 --> m3
  m2 --> m4
  m2 --> m5
  m2 --> m6
```
