# auth · AuthController.register 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AuthController.register :67"]
  m2["AuthService.register :97"]
  m3["ApiResponse.success :24"]
  m4["AuthService.validateRegisterCommand :170"]
  m5["AuthService.ensureUniqueIdentity :238"]
  m6["AuthService.normalizeUsername :214"]
  m7["AuthService.normalizeRequiredValue :226"]
  m8["PasswordPolicyValidator.validate :23"]

  m1 --> m2
  m1 --> m3
  m2 --> m4
  m2 --> m5
  m4 --> m6
  m4 --> m7
  m4 --> m8
```
