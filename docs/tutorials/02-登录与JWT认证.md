# 02 · 登录与 JWT 认证 —— "你是谁"这件事为什么这么难

> 代码入口：`backend/src/main/java/com/argus/rag/auth/` 包
> 核心文件：`security/JwtAccessTokenService.java`、`security/RefreshTokenService.java`、`service/AuthService.java`

## 用户的故事

小王早上登录了系统，正常用了一整天。第二天来直接就能用——不用重新输密码。
中午他去吃饭，电脑没锁，同事老李用他的浏览器打开系统，什么都能看。
三天后小王的密码在别的网站泄露了，攻击者拿着他的账号登录了 Argus——
**而我们的系统必须能识别出"这次登录不是小王"，把他踢下线。**

登录功能看起来只有"验证密码、发个凭证"两步，但它要回答的问题贯穿整个系统生命周期：
怎么证明你是你？凭证丢了怎么办？凭证被偷了怎么办？怎么让用户"不用重复登录"又不牺牲安全？

## 人话版：先搞懂"手环"和"票根"

把登录想象成进游乐园（词典里的比喻，这里展开讲）：

- 你在售票窗口验明正身（输密码），工作人员给你戴上一只**手环**
  （Access Token，学名 JWT）。之后玩每个项目，扫一下手环就放行，
  不用每次都翻出票根。手环上印着"你是谁、什么角色、几点失效"，
  还有防伪签名——自己拿笔改无效。
- 手环**只有 30 分钟寿命**。过期了怎么办？你存物柜里有一张**票根**
  （Refresh Token，放在浏览器里 JS 读不到的安全 Cookie 中），凭票根
  找窗口换一只新手环，**全程不用重新买票（重新输密码）**，一共能换 14 天。
- 票根**用一次就作废**，换手环的同时给你一张新票根。如果有人拿着
  你已经用过的旧票根来换——说明票根被偷了，管理员直接把你所有柜子和手环
  全部锁死，逼所有人重新买票。这就是后面要讲的"重放检测"。

为什么要这么麻烦、分两个凭证？因为它们分工不同：
**手环（Access Token）图快**——每次调接口都带，服务端不用查库就能验真伪；
**票根（Refresh Token）图稳**——数量少、落在数据库里，随时可以宣布作废。
一个管效率，一个管安全，缺一不可。下面看设计权衡和真实代码。

## 设计方案：双 Token 机制

| Token | 生命周期 | 存哪里 | 用途 |
| --- | --- | --- | --- |
| **Access Token（JWT）** | 30 分钟 | 内存（前端 JS 变量） | 每次 API 调用都带上，证明"我是谁" |
| **Refresh Token** | 14 天 | HttpOnly Cookie | Access 过期后用它"续命"，不用重新输密码 |

为什么是两个？单一方案都有硬伤：

| 方案 | 问题 |
| --- | --- |
| 只有 Session（服务端存状态） | 集群部署要共享 Session（粘性路由或集中存储）；移动端/第三方调用不友好 |
| 只有长效 JWT | **无法主动作废**——踢人/改密/泄露后，Token 到期前一直有效，这是灾难 |
| 短效 JWT + Refresh（我们的方案） | Access 短命（偷了也只用 30 分钟）；Refresh 落库可吊销（能踢人） |

核心取舍一句话：**JWT 让服务端无状态（好扩展），Refresh Token 落库把"作废能力"请回来**。

## Access Token（JWT）的关键代码

JWT 的结构是三段 Base64：`头部.载荷.签名`。载荷里放用户 ID、角色、过期时间，
签名保证内容没被篡改。**JWT 是"签名"不是"加密"——任何人都能解开看内容，只是不能改**。
所以载荷里绝对不能放密码、手机号这类敏感信息。

```java
// JwtAccessTokenService：短有效期（30 分钟）由配置注入
// access-token-expire-minutes: 30   （application-prod.yml）
```

## Refresh Token 的存储与轮换

Refresh Token 是系统的"命门"，它的安全设计比 Access 严格得多：

```java
/**
 * Refresh token 格式为 {@code tokenId.secret}，tokenId 明文字段索引查找记录，
 * secret 部分 bcrypt 哈希存储。签发和吊销均操作 user_refresh_tokens 表。
 */
```

三个要点：
1. **数据库只存 bcrypt 哈希**——数据库被拖库，攻击者也拿不到可用 Token（和密码同理）；
2. **HttpOnly Cookie**——前端 JavaScript 读不到，XSS 攻击偷不走；
3. **落库 + 可吊销**——`revokeActiveTokens(userId)` 一行代码让某人所有设备下线。

## Q&A：真实世界与面试追问

### Q1：Access Token 被偷了怎么办？

**不处理会怎样**：攻击者拿到 Token 后可以一直用，直到 30 分钟后过期——伤害被限制在 30 分钟内。

**我们的解法**：短有效期本身就是主要防线。30 分钟窗口 + 敏感操作（改密、导出财务）
再次校验身份，把泄露损失压到可接受。**没有绝对防偷，只有控制损失半径**——
面试时说这句比背十个方案更加分。

**追问：能不能让 Access Token 一被偷就失效？** 能，但代价是每次请求都查数据库
（黑名单/版本号校验），JWT 的无状态优势就没了。我们选择"短命 + 关键操作再校验"，
而不是"每次校验"——**安全设计是在'损失'和'成本'之间做选择，不是无脑堆防线**。

### Q2：Refresh Token 被偷了怎么办？（Token 重放攻击）

这是本模块最精彩的设计。攻击者偷到 Refresh Token，抢先去换新 Access Token——
真正的用户随后也来刷新。系统怎么发现"有两个人在用同一个 Token"？

**答案：Refresh Token 一次性使用 + 原子吊销检测**。每次刷新成功，旧 Token 立即作废、
发一个新的。数据库更新用了条件 SQL：

```java
/** 原子吊销：仅当 revoked_at 为 null 时才更新，返回受影响行数用于检测并发重放 */
@Update("update user_refresh_tokens set revoked_at = #{revokedAt} where id = #{id} and revoked_at is null")
```

两个请求并发刷新同一个 Token：数据库行级锁保证只有一个 UPDATE 返回 1（成功），
另一个返回 0——**返回 0 就意味着有人在你之前用过这个 Token，这是明确的攻击信号**：

```java
throw new BusinessException(ErrorCodes.AUTH_TOKEN_REPLAYED, "refresh token 已被使用，请重新登录");
```

生产代码里收到重放信号会吊销该用户的**全部** Token（攻击者和真用户一起下线），
强迫重新登录——损失一次便利，换整个账号安全。

**面试 30 秒版**：Refresh Token 一次性使用，数据库原子更新（`revoked_at is null` 条件）
检测并发重用；检测到重放视为 Token 泄露，吊销全家桶。这叫 **Refresh Token Rotation**，
是 OAuth2 最佳实践之一。

### Q3：密码在数据库里怎么存？

```java
/**
 * 密码哈希接口，默认使用 BCrypt 实现。
 */
public interface PasswordHasher {
    String hash(String rawPassword);
}
```

**BCrypt** 而不是 MD5/SHA-256，原因有三：自带随机盐（相同密码存出来也不同，
防彩虹表）；故意慢（单次计算约 100ms，暴力破解成本暴增；正常登录无感）；
可调成本因子（硬件变快了就调大）。**MD5 存密码在任何正经公司都是事故**。

**追问：为什么不给密码加"加密"而不是哈希？** 加密是可逆的，密钥泄露=全量密码泄露；
哈希不可逆，验证时把用户输入再哈希一遍比对即可。**密码永远只需要"验证"，不需要"读回"**。

### Q4：用户说"我这台电脑别让我天天登录"——记住我怎么做？

本质是延长 Refresh Token 有效期（14 天），而不是延长 Access Token。
Access 依然 30 分钟——"记住我"记住的是**免输密码**，不是**免验证**。
Cookie 配置 `Secure + HttpOnly`（生产 `refresh-cookie-secure: true`），
防传输窃取和脚本读取。

**追问：用户点"退出登录"实际发生了什么？** 前端丢掉 Access Token（内存变量，没了就没了）
+ 后端吊销 Refresh Token（`revokeToken`）。**退出必须同时做两件事**，
只做前者的话 Cookie 里的 Refresh Token 还能"复活"会话。

### Q5：管理员改了某用户密码/封禁了账号，怎么让他立刻下线？

```java
/** 吊销该用户全部有效 Token */
public void revokeActiveTokens(Long userId)
```

封禁 → 吊销所有 Refresh Token → 其 Access Token 最多再活 30 分钟 → 自动登出。
如果业务要求"立刻"，那就要在网关加 Token 黑名单或降低 Access 有效期——
又是一次"损失半径 vs 成本"的选择。我们选择接受 30 分钟窗口（500 人内部系统可接受）。

### Q6：JWT 放 Cookie 还是 localStorage？（经典争论题）

| 位置 | XSS 能偷吗 | CSRF 风险 | 结论 |
| --- | --- | --- | --- |
| localStorage | 能 | 无 | XSS 高危环境不能用 |
| HttpOnly Cookie | 不能 | 有（要防） | 我们的选择 |

我们 Access Token 存内存（XSS 难持久偷），Refresh Token 存 HttpOnly Cookie（CSRF 靠
SameSite + 来源校验防）。**没有完美位置，只有组合防御**。

## 动手实验

1. 用 DevTools 的 Application 面板找到 Refresh Cookie，确认它标了 HttpOnly，
   且在 Console 里 `document.cookie` 读不到它。
2. 登录后手动把数据库里你的 refresh token 的 `revoked_at` 填上时间，
   等_access 过期后观察前端自动跳登录页。
3. 读 `AuthService.java` 第 131 行附近的重放检测逻辑，画出"攻击者先刷新"和"真用户先刷新"
   两种时序下各自发生什么。
