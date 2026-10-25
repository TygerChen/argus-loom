# 代码级面试题库 · 03 Excel 协同包

> 覆盖范围：`com.argus.rag.excel` 的 collab（CollabController/CollabService/OnlineUserTracker/StompSessionAuth）、
> config（WebSocketConfig/ExcelSubscribeInterceptor）、service（ExcelSheetService/SheetChunkMerger/
> RedisChunkCacheService/ExcelDocumentLockService/ExcelCollabOutboxService/ExcelCollabEventPublisher/
> ExcelVersionService/SnapshotService/ExcelExportService/ExcelPermissionService/ExcelSheetAccessService/
> ShareLinkService）、controller（ExcelDocumentController/ExcelExportController/ExcelVersionController/
> StructuredDataController/FinanceImportController）、audit（OperationLogService）。
> 事实基准：当前源码 + `PROJECT_TRUTH.md`（Java 21 / Spring Boot 3.5 / MyBatis-Plus 3.5.15 /
> PostgreSQL / 无真实 Redis）。关键常量均按代码核对：`CHUNK_SIZE=1000`、缓存 512 条/120s TTL、
> 导出池 2/4/队列 100/CallerRuns、压实阈值 500 条增量、Outbox 清扫 500ms/批 50。
> 所有方法名、字段名、SQL 均可直接说出口；不确定处已标注"待验证"或诚实边界。

---

## 一、协同编辑主链路（CollabController + CollabService）

**Q1.【问题】一次 STOMP 单元格编辑，从 `/app/excel/edit` 到其他人看到新值，完整经过哪些步骤？**

- **考点**：实时链路端到端调用链、分层职责。
- **答案要点**：前端 STOMP 发到 `/app/excel/edit`（应用前缀 `/app` 在 `WebSocketConfig.configureMessageBroker` 配置），`CollabController.handleEdit` 先用 `StompSessionAuth.userId(headerAccessor)` 从 session attributes 取身份，再 `permissionService.canEdit(msg.documentId(), user)` 校验文档编辑权，然后委托 `collabService.handleEdit` 做 LWW 判定、revision 校验、操作日志、持久化、Outbox 落库；接受则 `messagingTemplate.convertAndSend("/topic/excel/" + documentId, outcome.broadcast())` 广播，拒绝则 `convertAndSendToUser(sessionId, "/queue/error", payload)` 只回执发送者。控制器只做路由与推送，冲突仲裁和持久化全在 CollabService。
- **可能追问**：为什么拒绝只回发送者不广播？——拒绝意味着数据没变，广播出去反而要让别人回滚；错误负载带 `serverRevision`，冲突时客户端据此刷新重试。

**Q2.【问题】为什么身份判定用 session attributes 里的用户，而不信消息负载里的 `userId`？**

- **考点**：不信任客户端输入、身份伪造防线。
- **答案要点**：`CollabMessage` 是客户端发的 record，`userId`/`displayName` 字段可被任意伪造。`CollabService.handleEdit` 第一行就把消息重建：`msg = new CollabMessage(..., editUserId, editDisplayName, ...)`，用 STOMP 会话鉴权（CONNECT 帧解析 JWT）得到的身份覆盖负载身份；操作日志写入后还有一句"双保险"——`if (entry != null && !editUserId.equals(entry.getUserId()))` 强制改写回会话身份。审计归属只能认服务端认证的身份。
- **可能追问**：CONNECT 帧的身份就一定可信吗？——可信，因为 CONNECT 拦截器里 `jwtAccessTokenService.parse(token)` 验签后才写入 session attributes，伪造者拿不到有效 JWT 就建不了会话。

**Q3.【问题】LWW（Last-Write-Wins）具体怎么实现的？为什么比较用 `<=` 而不是 `<`？**

- **考点**：冲突解决算法落地细节。
- **答案要点**：`CollabService` 持有 `ConcurrentHashMap<String, Long> lastEditTimestamps`，键是 `documentId_sheetId_r_c`，值是该格最后一次被接受的编辑时间戳。新编辑进来时 `lastTs = lastEditTimestamps.get(cellKey)`，若 `lastTs != null && msg.timestamp() <= lastTs` 就拒绝，返回 `LWW_REJECTED`"您的编辑已被另一个用户的更新覆盖"；否则 `put` 新时间戳。用 `<=` 意味着时间戳相同的编辑只有先到者胜——严格单调，避免同毫秒两条编辑都通过造成顺序不定。时间戳取自 `CollabMessage.timestamp`（客户端发送时刻的毫秒值）。
- **可能追问**：时间戳是客户端给的，可信吗？——这是个真实弱点：快钟客户端总能赢，恶意客户端可以传超大时间戳长期独占某格。字段里预留了 `serverTimestamp`（服务端接收时刻）但当前 LWW 比较用的仍是客户端时间戳，改进方向是用服务端接收时间做比较键。这是我能主动指出的边界。

**Q4.【问题】为什么选 LWW 而不是 OT 或 CRDT？多实例部署怎么办？**

- **考点**：协同算法选型 + 诚实边界（类注释原文）。
- **答案要点**：OT（操作变换）要在服务端对并发操作做变换矩阵，实现和排查成本都高；CRDT 自动合并但要把整个数据模型换成 CRDT 库（如 yjs 生态），架构改造最大。表格编辑的场景是"单格文本覆盖"，冲突粒度天然是单元格，LWW 一条时间戳比较就够了，500 人规模同格并发本就罕见。多实例边界我直接背类注释：`lastEditTimestamps` 是进程内内存，重启后从 DB 重新加载，多实例下各实例时间戳不互通、仲裁失效；演进三选一——①时间戳挪 Redis（INCR 或 Lua 原子比较，改动最小）；②版本号向量时钟（能识别"并发"而不只是"谁新"，成本高）；③接 CRDT 库（改造最大）。原则是"按真实压力演进，不为简历凑架构"。
- **可能追问**：重启丢失时间戳会怎样？——重启后缓存为空，LWW 退化为"后到者胜"，但仍有 revision 乐观锁兜底，不会静默丢编辑。

**Q5.【问题】LWW 之外为什么还要一层 revision 乐观锁？两层各防什么？**

- **考点**：多层并发控制的设计意图。
- **答案要点**：LWW 防的是"同一格的乱序覆盖"（格粒度），revision 防的是"整文档级别的过期写"（文档粒度）。`handleEdit` 里若 `msg.baseRevision() != null`，先 `documentService.optionalById` 取 `doc.getVersion()`，`!msg.baseRevision().equals(currentRevision)` 就返回 `HandleEditOutcome.conflict(...)`，拒绝原因 `REVISION_CONFLICT` 并携带 `serverRevision` 供客户端刷新。`baseRevision == null` 视为旧客户端跳过校验（向后兼容），LWW 仍兜底。顺序是：先 revision 校验（便宜，挡掉大多数过期写），再 LWW（格级精判）。
- **可能追问**：HTTP 保存路径也有这层吗？——没有，这是诚实边界：`ExcelDocumentController.batchUpdateCells` 只 `requireEdit`，不校验版本号；乐观锁目前只在 STOMP 协同路径生效，HTTP 批量保存靠文档写锁串行化。

**Q6.【问题】`bumpVersion` 代码里 `update.setVersion(exist.getVersion())` 看起来没加一，版本号到底怎么推进的？**

- **考点**：MyBatis-Plus `@Version` 乐观锁插件机制（容易露馅的细节）。
- **答案要点**：`ExcelDocument.version` 字段标了 `@Version`，且 `MybatisPlusConfig` 注册了 `OptimisticLockerInnerInterceptor`。`ExcelDocumentService.bumpVersion` 读出当前 version 原样塞进 update 实体后 `updateById`，插件会把 SQL 改写为 `SET version = 旧值+1 WHERE version = 旧值`——"加一"是插件干的。并发两次 bump：只有一个 UPDATE 匹配到行，另一个 0 行更新，但 `bumpVersion` 随后 `selectById` 重读一次返回库里的最新值，天然自愈。推进失败只 warn 不回滚编辑（"revision 推进失败（编辑已保存）"）。
- **可能追问**：如果把 `@Version` 注解去掉这段代码会怎样？——set 多少就写多少，版本号永远不变，所有带 baseRevision 的编辑全部冲突。

**Q7.【问题】协同光标（别人的光标在我屏幕上动）是怎么实现的？它和单元格编辑走的是同一条持久化路径吗？**

- **考点**：临时事件与持久事件的分流设计。
- **答案要点**：光标事件复用 `/app/excel/edit` 入口但 `msg.type()` 为 `CURSOR`，`CollabService.handleEdit` 开头就分流：`if ("CURSOR".equals(msg.type()))` 直接组装广播 map 返回 `HandleEditOutcome.accepted(broadcast)`——不查 revision、不进 LWW 缓存、不写操作日志、不 `snapshotService.touch`、不 bumpVersion、不落 Outbox。广播里带 `senderSessionId`，前端据此忽略自己的回播并渲染别人光标。注释原文：光标"只在内存中转发，不持久化、不产生审计日志、不推进版本"。
- **可能追问**：光标消息频繁，会不会打爆 broker？——频率由前端节流控制（服务端不限制，待验证具体节流参数在前端 utils 里）；服务端保证的是它零持久化成本。

**Q8.【问题】编辑持久化失败、日志失败、Outbox 失败，三种失败各自怎么处理？为什么策略不同？**

- **考点**：失败路径分级、降级设计。
- **答案要点**：①持久化失败（`persistEdit` 抛异常或 SQL 0 行）：回滚 LWW 时间戳 `lastEditTimestamps.remove(cellKey)` 允许重试，返回 `SAVE_FAILED` 拒绝——数据没落库绝不能广播；②操作日志/协同日志失败：catch 后只 `log.error/warn`，主流程继续——日志是审计辅助，不能挡编辑；③Outbox 落库失败：注释原文"降级为直接广播"，仍返回 accepted——至少同实例用户能实时看到，丢失的只是跨实例重放兜底。原则：按"数据正确性 > 可观测性 > 可达性"排优先级。
- **可能追问**：时间戳回滚后有个窗口，期间别的编辑会不会被误拒？——会，put 与 remove 之间新来的更旧时间戳编辑会被拒一次，但客户端重试即可，代价可接受；要消除就得把 put 挪到持久化成功之后。

**Q9.【问题】广播为什么同时有"实时直发"和"Outbox 重放"两条路？重复了怎么办？**

- **考点**：Outbox 模式的意图、幂等消费。
- **答案要点**：直发（CollabController 的 `convertAndSend`）兜底同实例的低延迟；Outbox（`ExcelCollabOutboxService.record` 落一行 PENDING，`ExcelCollabEventPublisher` 每 500ms `@Scheduled` 清扫 `selectPending(50)` 逐条广播后置 SENT）兜底"保存成功但广播丢失"。重复是设计内行为：同一编辑既被直发又被重放，客户端按 `broadcast` 里的 `requestId` 去重（前端约定）。单条重放失败保持 PENDING 等下轮，不影响同批其他事件。
- **可能追问**：Outbox 有没有已知缺陷？——有，PROJECT_TRUTH 明确记录：`selectPending` 无认领语义，多实例时每个实例都会重放全部 PENDING 事件，对"编辑保存在 A 实例、STOMP 连接在 B 实例"的用户存在真实消息丢失窗口；单实例没问题，多实例前必须给 selectPending 加 `FOR UPDATE SKIP LOCKED` 认领。

**Q10.【问题】在线用户列表怎么维护？用户直接关浏览器没发 leave 怎么办？**

- **考点**：会话生命周期、断线清理。
- **答案要点**：`OnlineUserTracker` 两张 map：`onlineUsers`（documentId → userId → UserInfo record）和 `sessionToDocUser`（sessionId → (documentId, userId)）反向索引。`handleJoin` 校验 `canView` 后 `userJoin` 并广播 `USER_LIST` 到 `/topic/excel/{id}/users`。用户直接关浏览器时 `@EventListener onSessionDisconnect(SessionDisconnectEvent)` 被触发，从反向 map 找到 documentId+userId 后 `userLeave` 清理并广播新列表。数据库侧 `CollabService.joinRoom` 按 session_id upsert `excel_collab_room` 行（含 `canEdit` 标志），`leaveRoom` 删行。
- **可能追问**：多实例下在线名单一致吗？——不一致，tracker 也是进程内的；B 实例用户看不到 A 实例用户。这也是多实例改造清单里的一项（PROJECT_TRUTH 风险 1）。

---

## 二、STOMP 通道与鉴权（WebSocketConfig / ExcelSubscribeInterceptor / StompSessionAuth）

**Q11.【问题】WebSocket 没有走 HTTP 的 JWT 过滤器，那 STOMP 连接怎么鉴权？**

- **考点**：STOMP CONNECT 帧认证、Spring Messaging 拦截链。
- **答案要点**：前端在 STOMP CONNECT 帧的 `Authorization` 头里带 JWT。`WebSocketConfig` 的内部类 `WebSocketAuthChannelInterceptor` 实现 `ChannelInterceptor.preSend`，`StompCommand.CONNECT` 时取 `accessor.getNativeHeader("Authorization")`，剥掉 `Bearer ` 前缀后 `jwtAccessTokenService.parse(token)` 验签，把 `claims.userId()/displayName()/systemRole()` 写进 `accessor.getSessionAttributes()`（键名常量在 `StompSessionAuth`：`userId`/`displayName`/`systemRole`）。之后所有消息处理线程从 session attributes 还原身份，不再碰 SecurityContext。
- **可能追问**：为什么不在 HTTP 握手（HandshakeInterceptor）做？——两者都可行；CONNECT 帧拦截的好处是 STOMP 层统一处理，且能拿到 `StompHeaderAccessor` 的原生头，与 SockJS 传输方式解耦。

**Q12.【问题】CONNECT 时 JWT 无效会怎样？没带头呢？两种情况待遇一样吗？**

- **考点**：认证失败分支的差别。
- **答案要点**：带了但解析失败 → catch 后抛 `MessageDeliveryException("WebSocket 认证失败: ...")` 直接拒绝 CONNECT，注释写明"不浪费服务器资源"。完全没带 `Authorization` 头 → 不抛异常，消息继续走，但 session attributes 里没有 userId——后续每个 `@MessageMapping` 方法第一句 `StompSessionAuth.userId(...)` 返回 null 直接 return，订阅拦截器也会拒绝 Excel 频道。即"显式无效"拒绝连接，"匿名"放行连接但什么都做不了，两条路径都 fail-closed。
- **可能追问**：匿名连接占着WebSocket 不算资源浪费吗？——算一个空闲 socket，但没有任何业务能力；更严的做法是匿名也直接拒 CONNECT，当前选择保留连接是为了兼容（例如未来可能的游客只读场景，待验证产品意图）。

**Q13.【问题】订阅 `/topic/excel/5` 和订阅 `/topic/excel/5/users` 是怎么被权限拦截的？正则怎么写的？**

- **考点**：SUBSCRIBE 帧鉴权、可测试性设计。
- **答案要点**：`ExcelSubscribeInterceptor` 也挂在 clientInboundChannel 上，只处理 `StompCommand.SUBSCRIBE`。频道模式是静态常量 `Pattern.compile("^/topic/excel/(\\d+)(/users)?$")`，第 1 分组即文档 ID。核心判定沉淀在静态方法 `assertAuthorized(destination, user, permissionService)`：非 Excel 频道直接放行；Excel 频道要求 `permissionService.canView(documentId, user)`，不满足抛 `MessageDeliveryException("没有查看该文档的权限")`，broker 把它转成 STOMP ERROR 帧给订阅者。抽成静态方法是为了单测能直接喂 destination+user 验证拒绝/放行两条路径，不启动 Spring。
- **可能追问**：只拦订阅不拦发送，会不会漏？——发送侧 `/excel/edit` 在 handleEdit 里有独立 `canEdit` 校验，join/leave 有 `canView` 校验；订阅、连接、发消息三层各自把关，不依赖单点。

**Q14.【问题】`StompSessionAuth.currentUser` 构造的 `CurrentUser` 里 `userCode` 是 null，权限判定为什么不受影响？**

- **考点**：最小身份数据、注释里的设计契约。
- **答案要点**：`currentUser` 返回 `new CurrentUser(userId, null, displayName, systemRole, false)`。注释原文解释：userCode 在 REST 侧由 DB 加载，WS 认证时未冗余存入 session；而 `ExcelPermissionService.permissionOf` 的判定只依赖 `user.userId()`（比对 creatorId）和 `user.systemRole()`（财务/管理员分支），从不使用 userCode，所以置空安全。这是"身份数据按需携带"的契约——一旦未来权限逻辑开始依赖 userCode，这个 null 就会成为隐患，注释把依赖关系写死了。
- **可能追问**：finance 判定靠 systemRole 可靠吗？——systemRole 来自 JWT claims（登录时按 DB 角色签发），access token 30 分钟内角色变化有延迟窗口，与 REST 侧一致，是已知取舍。

**Q15.【问题】WebSocket 端点的跨域为什么不允许 `*`？配置长什么样？**

- **考点**：CSWSH（跨站 WebSocket 劫持）防线、P2-08 修复。
- **答案要点**：`registerStompEndpoints` 里 `/ws` 端点 + SockJS，`setAllowedOriginPatterns(resolveOriginPatterns(allowedOriginPatterns))`；配置项 `argus.rag.websocket.allowed-origin-patterns` 默认 `http://localhost:8081,http://127.0.0.1:8081,http://localhost:10001`，LAN/域名部署用 `ARGUS_WS_ALLOWED_ORIGINS` 环境变量覆盖。`resolveOriginPatterns` 是包级静态纯函数：去空白、滤空段，配置为空时回退本机默认来源——注释强调"不回退 `*`，避免悄悄放开跨站"。允许 `*` 时恶意页面可发起跨站 WebSocket 连接复用浏览器凭据（CSWSH）。
- **可能追问**：为什么抽成 static 纯函数？——注释原文"沉淀为纯函数便于单测"，解析逻辑（split/trim/filter/回退）可以不启动容器直接测。

**Q16.【问题】`/app` 和 `/topic` 两个前缀各自是什么角色？错误消息为什么用 `/queue/error` 还要 `convertAndSendToUser`？**

- **考点**：STOMP broker 拓扑。
- **答案要点**：`configureMessageBroker` 里 `setApplicationDestinationPrefixes("/app")`——带此前缀的消息路由到 `@MessageMapping` 控制器方法；`enableSimpleBroker("/topic")`——内置简单代理直接广播给订阅者。错误回执用 `messagingTemplate.convertAndSendToUser(sessionId, "/queue/error", payload)`：`/user` 前缀让 broker 把消息只投给该 session 对应的私有队列，别人订阅不到，避免把"某人被拒绝"这种信息广播出去。Excel 内容广播用 `/topic`（公开给有权限的订阅者），错误回执用 `/user/queue`（点对点）。
- **可能追问**：`convertAndSendToUser` 第一个参数为什么是 sessionId 而不是 userId？——同一用户可能开多个页面，按 sessionId 精确回执到发起请求的那个连接；Broadcaster 里 `sessionId` 同时也放进了广播 payload 供前端去重。

**Q17.【问题】为什么用内置 SimpleBroker 而不是 RabbitMQ/StompBrokerRelay？前端为什么走 SockJS？**

- **考点**：消息中间件选型、传输降级。
- **答案要点**：`enableSimpleBroker("/topic")` 是 Spring 内置的内存代理，广播就是遍历本进程内订阅者写 session——零外部依赖，单实例场景完全够用；PROJECT_TRUTH 明确当前没有 RabbitMQ 依赖，引 Relay 就要引入 MQ 运维成本，与"按真实压力演进"原则冲突（多实例时才需要外部 broker 做跨实例广播）。SockJS 提供 WebSocket 降级通道（轮询/长轮询 fallback），公司内网老代理环境兼容性好；端点 `/ws` + `withSockJS()` 一行配置。
- **可能追问**：SimpleBroker 有什么限制？——不支持持久化、不支持 broker 端鉴权（所以订阅鉴权只能靠我们自己的拦截器）、无集群能力；这三点恰好对应多实例改造清单。

**Q18.【问题】用户断线重连（或刷新页面）之后，服务端状态和客户端数据怎么恢复一致？**

- **考点**：会话重建、增量一致性恢复。
- **答案要点**：重连对服务端是全新会话：新 CONNECT 重新验 JWT 写 session attributes，新 sessionId 重新走 join（`userJoin` 覆盖同 userId 的在线记录，`joinRoom` 按 session_id 新建房间行）。数据恢复靠两层：客户端重新拉 chunks（读到"基数+增量合并"的最新事实）；服务端 `baseRevision` 校验兜住重连窗口内漏掉的编辑——客户端基于旧版本提交会被 `REVISION_CONFLICT` 拒绝并要求刷新。LWW 时间戳表在服务端不随会话失效，跨重连仍有效。
- **可能追问**：旧 sessionId 的房间行一定会被清吗？——正常路径靠 `SessionDisconnectEvent`；服务端崩溃时残留行无人清理（`excel_collab_room` 无 TTL 清扫，诚实边界），影响限于运营统计而非功能。

---

## 三、chunk+delta 存储模型（ExcelSheetService / SheetChunkMerger / RedisChunkCacheService / ExcelDocumentLockService）

**Q19.【问题】解释 Sheet、Chunk、Delta 三个概念，为什么编辑要存增量而不是改写整块？**

- **考点**：存储模型设计动机（本包最核心一题）。
- **答案要点**：`ExcelSheet` 存工作表元信息（行列数/合并/列宽等 JSON 列，不含单元格数据）；`ExcelSheetChunk` 是把表按 `ExcelParserService.CHUNK_SIZE=1000` 行切片存的"基数快照"，解析导入时生成、之后基本不动；`ExcelSheetChunkDelta` 只记"第 r 行第 c 列的值变成 v"。编辑从不改写基数，因为单格编辑高频而块重写成本高：一次编辑重写 1000 行 JSON 是严重写放大，且两请求并发改同一块冲突面大；只追加增量写入成本接近零。代价是读要合并、增量表膨胀——所以有 `compactSheetDeltas` 压实。注释原文点题：这是 **LSM-Tree / Redis AOF 重写的思想：顺序追加 + 周期性压实**。
- **可能追问**：一个诚实的数字勘误？——`ExcelSheetService` 类注释写"按 500 行切块"，但代码常量 `CHUNK_SIZE = 1000`，注释是历史残留；我引用数字一律以代码常量为准。

**Q20.【问题】读一张表的完整数据，`listChunksBySheetId` 为什么要"最后再合并一次增量"？**

- **考点**：缓存一致性的时间窗兜底。
- **答案要点**：标准三段：①`cacheService.getSheetChunks(documentId, sheetId)` 命中直接用；②未命中 `listRawChunksBySheetId` 查库（纯基数）后 `putSheetChunks` 回填；③**返回前 `mergeDeltasInto(chunks)` 无条件再合并一次增量**。第③步的原因：写入路径只失效缓存、增量永远先于缓存刷新落库，"刚写完马上读"时缓存可能已被回填为旧基数，最后一步合并用 DB 里的增量兜底，保证读到最新事实。这是"缓存只做加速层、正确性以 DB 为准"的落地。
- **可能追问**：这样读性能不是浪费了吗？——合并只发生在有增量的块上（`byChunk.getOrDefault(..., List.of())` 为空直接跳过），压实后大部分时间是 O(1) 空检查。

**Q21.【问题】`SheetChunkMerger.merge` 的合并规则是什么？怎么表达"删除一个单元格"？**

- **考点**：纯函数设计、增量语义。
- **答案要点**：包私有 final 类，`merge(String baseJson, List<ExcelSheetChunkDelta> deltas)` 无副作用、不依赖 DB，便于单测。规则：基数按 `r + "_" + c` 放进 `LinkedHashMap`（保序），增量按序生效，同键覆盖（后序增量赢）；`valueJson` 为 null/空白/`{}`/`null` 字符串时 `cells.remove(key)`——即**空值增量 = 删除单元格**；否则写成 `{r, c, v}` 三元组。抽成纯函数是包内重构的产物（注释：从 ExcelSheetService 中抽离便于独立单元测试与复用）。
- **可能追问**：为什么用 LinkedHashMap？——保序让合并输出与原 celldata 顺序稳定，前端 diff 和测试断言都受益。

**Q22.【问题】写路径 `batchUpdateCells` 为什么要先拿文档级锁？块不存在怎么办？**

- **考点**：并发写入串行化、动态扩块。
- **答案要点**：`batchUpdateCells` 第一行 `lockService.runWithLock(documentId, () -> writeCellsGrouped(documentId, updates))`——`ExcelDocumentLockService` 是 JVM 内 `ConcurrentHashMap<Long, ReentrantLock>`，把"HTTP 保存/协同写入/撤销重做/版本回滚"对同一文档的写全部串行，防止两请求并发写产生交错的增量顺序。锁粒度是整个文档而不是单格，注释的解释：500 人规模同文档并发写本来就少，简单可靠。`writeCellsGrouped` 按 `(sheetId, r/CHUNK_SIZE)` 分组，块查不到就现场 `insert`（`rowStart = chunkIndex*1000`，`rowEnd = chunkIndex*1000+999`）并推进 `excel_sheet.chunk_count`——在线新增行超出原解析范围时也能落库。
- **可能追问**：这把锁是分布式的吗？——不是，注释明确"锁是 JVM 内 ReentrantLock，只在单实例内互斥；多实例部署前必须先替换为跨实例锁，不能凭此处注释当作分布式能力"。

**Q23.【问题】同一格反复编辑，delta 表会无限膨胀吗？增量表存的是全历史吗？**

- **考点**：delta 语义 + 职责分离（容易答错）。
- **答案要点**：不会膨胀，delta 表存的**不是操作历史而是每格最新值**。`saveDeltasForChunk` 先 `loadDeltasByChunk` 建出 `existingByCell`（键 `cellRow + "_" + cellCol`），同格再编辑走 `deltaMapper.updateById` 覆盖旧增量而不是追加新行。完整的变更历史另有两个归宿：`operation_log`（新旧值 + 操作人，支撑撤销/审计）和版本快照表（整时点复制）。三层职责：chunk=基数、delta=最新事实、operation_log/version=历史。压实的触发在 `maybeCompactSheetDeltas`：每次写入后 `selectCount` 一把（O(1) 计数），sheet 级增量满 **500 条**就 `compactSheetDeltas`。
- **可能追问**：压实为什么必须在事务里？——`compactSheetDeltas` 标了 `@Transactional`：把合并结果写回各 chunk 的 `celldataJson` + `deltaMapper.delete(qw)` 删光增量必须原子，否则删了增量还没写基数就崩溃，数据直接丢。

**Q24.【问题】`RedisChunkCacheService` 这个名字有 Redis，实际是什么？为什么这样是合理的？**

- **考点**：诚实边界（PROJECT_TRUTH 明确项）、缓存实现。
- **答案要点**：实际是**进程内有界 LRU + TTL 缓存**，没有任何 Redis 依赖（PROJECT_TRUTH："没有真实 Redis 依赖"）。实现：`LinkedHashMap(64, 0.75f, accessOrder=true)` 重写 `removeEldestEntry`，`size() > maxEntries(512)` 淘汰最久未用；每条 `Entry(json, expireAtMillis)`，TTL 默认 120_000ms，`purgeExpired` 惰性清理；所有方法 `synchronized`；键 `chunks:v1:{documentId}:{sheetId}`；`hitCount/missCount` 用 AtomicLong 可观测。合理性：缓存的是 (document, sheet) 粒度、上限 512 条、单实例部署，量级根本不需要独立 Redis；类名沿用历史命名，注释写明"多实例部署时可在本类内换成 Redis，读路径无需改动"。还有一段修复史：P1-05 之前是无界 ConcurrentHashMap 且"只写不读的假缓存"。
- **可能追问**：为什么 get 返回的是反序列化副本？——`getSheetChunks` 每次从存储的 JSON 重新 `readValue`，调用方改动不污染缓存；put 时也序列化成 JSON 快照，双向防御别名问题。

**Q25.【问题】缓存失效纪律是怎么执行的？漏一处会怎样？**

- **考点**：写路径一致性（AGENTS.md 硬约束）。
- **答案要点**：所有 chunk/delta 写路径必须调用 `cacheService.invalidateSheet(documentId, sheetId)` 或 `invalidateDocument(documentId)`——包括 `insertChunkWithoutHistory`、`writeCellsGrouped` 每块写完、`compactSheetDeltas` 末尾逐 sheet 失效、`replaceWorkbook`/`deleteByDocumentId`/`hardDeleteByDocumentId` 的文档级失效、版本回滚后的 `insertChunkWithoutHistory` 链路。漏失效的后果不是脏数据永久化（TTL 120s 兜底），而是最长 2 分钟读到旧值 + Q19 的增量再合并能救回 delta 部分，但基数变化（回滚/整簿替换）救不回来。`invalidateDocument` 用 `localCache.keySet().removeIf(k -> k.startsWith(prefix))` 前缀批量删。
- **可能追问**：失效和删除是不是重复防御？——是三层：写后显式失效（主）、TTL 过期（兜底）、LRU 容量淘汰（内存保护），每层各管一种失效原因。

**Q26.【问题】`ExcelDocumentLockService` 的锁 map 会不会泄漏？解锁后为什么还有一句 `hasQueuedThreads` 判断？**

- **考点**：锁生命周期管理细节。
- **答案要点**：`withLock` 里 `locks.computeIfAbsent(documentId, k -> new ReentrantLock())`，finally 中 `lock.unlock()` 后 `if (!lock.hasQueuedThreads()) locks.remove(documentId)`——没有排队线程才把锁对象从 map 移除，防止长期运行后每个文档留一把死锁对象（内存泄漏）。但这句有一个经典竞态：remove 与另一线程刚 computeIfAbsent 拿到旧锁对象之间可能交错，导致两个线程短暂持有不同锁对象（注释未展开，这是可主动指出的理论缺口；真实影响小因为文档级并发低，且最坏只是短暂失去互斥而非死锁）。它统一串行 HTTP 保存、协同写入、undo/redo、版本回滚四类写。
- **可能追问**：怎么彻底修？——用 `ConcurrentHashMap.compute` 原子地"取锁+入队"，或 Guava Striped 锁 + 引用计数；多实例则换 PostgreSQL advisory lock。

**Q27.【问题】`replaceWorkbook`（整簿全量保存）为什么整个方法一个大事务？中途失败会留下什么？**

- **考点**：事务边界与全量替换。
- **答案要点**：`replaceWorkbook` 标 `@Transactional(rollbackFor = Exception.class)`：先删该文档全部 chunk+delta、`invalidateDocument`、旧 sheet 全部置 status=3（软删），再逐张新建 sheet（name/config/merge/columnlen/rowlen/hyperlink/images/conditionformat/chart 各自独立 JSON 列），`saveCelldataChunks` 按行切块入库，最后 `documentService.updateSheetMeta` 写 sheet 数与名单。任何一步抛异常整体回滚，不会出现"新表建了一半、旧表已被软删"的中间态。返回 `Map<Integer, Long>`（前端 sheet 索引 → 数据库 sheetId）供调用方继续关联。
- **可能追问**：celldata 两种形态怎么统一？——前端可能发稀疏 `celldata` 列表或 `data` 二维矩阵，`resolveCelldata` 先取前者、为空则 `dataMatrixToCelldata` 转换，`normalizeCelldata` 剔除空值格——稀疏存储对大表省一个数量级空间。

**Q28.【问题】一次打开大工作簿（多张 sheet、几十万行），数据是怎么被按需加载的？**

- **考点**：chunk 化的读路径价值。
- **答案要点**：三个入口对应三种粒度：①`loadSheetChunks`（`GET /{id}/sheet/{sheetId}/chunks?start=&end=`）按**块索引范围**加载，参数默认 0 到 Integer.MAX_VALUE，前端滚动到哪加载到哪；②`getSheetChunksByRange` 按**行区间**选"与区间有交集"的块（`le("row_start", rowEnd).ge("row_end", rowStart)`）；③`listChunksByDocumentGrouped` 打开整簿时一次查全文档、按 sheetId 分组（省 N 次往返），版本快照与导出复用。`loadMergedCelldata` 则是全量合并。80 万行的表 = 800 个 chunk，单次请求只碰几个。
- **可能追问**：单格读呢？——`loadCellSnapshot` 用 `r / CHUNK_SIZE` 直接算出块号，只查一个块 + 合并该块增量，O(块内行数) 与整表大小无关；它是 LWW 撤销前值和 undo 旧值快照的底座。

---

## 四、版本快照与回滚（ExcelVersionService / SnapshotService / ExcelVersionController）

**Q29.【问题】版本为什么做全量快照而不是 diff？存储不是浪费吗？**

- **考点**：设计哲学（注释原文可背）。
- **答案要点**：注释原文："快照而不是差异——存储便宜、正确性第一"。每个版本把当时全部 Sheet 的全部块原样复制（`versionChunk.setCelldataJson(chunk.getCelldataJson())` 逐字复制），恢复时"整体换血"，不用逐版本重放差异——重放一步错步步错，diff 链任何一环损坏后面全部不可恢复。数据量大了再演进"定期全量 + 中间 diff"。三层落库：`excel_document_version`（主表：snapshotType/creator/summary/sheetCount/cellCount）→ `excel_document_version_sheet`（含全部配置 JSON 列）→ `excel_document_version_chunk`（chunkIndex/rowStart/rowEnd/celldataJson）。
- **可能追问**：空间账怎么算？——默认保留 10 版（`configService.getRetentionCount()`），单版本≈当前数据一份拷贝，10 倍存储换任意时点 O(1) 恢复，中小规模完全可接受。

**Q30.【问题】四种 snapshotType 分别什么时候产生？"回滚"为什么自己也产生版本？**

- **考点**：版本语义完整性。
- **答案要点**：常量 `TYPE_MANUAL`（手动保存，`manualSnapshot`）、`TYPE_CELL`（单元格批量保存/定时自动快照）、`TYPE_STRUCTURE`（工作簿结构变化）、`TYPE_ROLLBACK_POINT`（回滚前自动保存）。回滚四步曲（`restoreVersion`，一个事务）：①先把当前状态存成 ROLLBACK_POINT——注释原文"后悔药的反悔药"，回滚错了还能再退回来；②`sheetService.hardDeleteByDocumentId` 物理删现有 sheet/块/增量；③`rebuildSheets` 按版本数据重建；④`documentService.bumpVersion` 推进版本号——其他持旧 baseRevision 的编辑请求自然冲突。历史只向前追加、永不改写。
- **可能追问**：restore 的事务里调用 createSnapshot，两个 @Transactional 怎么传播？——默认 REQUIRED，加入同一物理事务，回滚点快照与重建同生共死：重建失败则快照也撤销，文档保持回滚前状态，语义正确。

**Q31.【问题】定时自动快照的触发条件是什么？有什么诚实边界？**

- **考点**：快照调度 + 代码级诚实（面试加分点）。
- **答案要点**：`SnapshotService` 维护 `ConcurrentHashMap<Long, LocalDateTime> lastEditTimes`，`CollabService.handleEdit` 里每次编辑 `snapshotService.touch(documentId)` 打点。`@Scheduled(fixedRate = 300_000)`（5 分钟）扫一遍：距上次编辑 1 小时内必快照，1-24 小时且当前分钟数 `% 30 < 5` 也快照，类型 TYPE_CELL。诚实边界有二：①`createSnapshot` 的 javadoc 写"调用方需持有文档写锁"，但 `scheduledSnapshot` 和 `tryAutoCellSnapshot` 都没持锁直接调，与写路径并发时理论上可能快照到中间态；②`tryAutoCellSnapshot`/`tryAfterStructureSave` 当前在生产代码里没有调用方（预留方法），自动快照实际只有定时器一条路。两处我都如实承认并给出改法（调度线程也走 `lockService.withLock`）。
- **可能追问**：快照失败会怎样？——`tryAutoCellSnapshot` 和定时器都 catch 后只 log，注释原文"版本是保险，不能反过来挡住保存主流程"。

**Q32.【问题】版本保留策略怎么清理？为什么"手动保存优先保留"只是目标，实现上做到了吗？**

- **考点**：pruneOldVersions 实现细节 + 诚实辨析。
- **答案要点**：`pruneOldVersions` 在每次 `createSnapshot` 末尾执行：按 `create_time desc, id desc` 取全部版本 ID，超过 `retention`（默认 10，最小 1）的部分整链删除（versionChunk → versionSheet → version 按 `in(version_id(s))` 删）。诚实辨析：类注释说"按手动保存优先保留清理最老的自动快照"，但**当前实现按时间序清理，并没有区分 snapshotType**——最老的手动版本同样会被删。这是我核对代码后确认的注释与实现偏差，面试时应说"目标是手动优先，实现目前是纯时间序"。
- **可能追问**：删除顺序有讲究吗？——先删子表（chunk/sheet）再删主表，即便中途失败也只留孤儿子行不留悬挂主行；配合事务整体回滚则顺序不敏感，双保险。

**Q33.【问题】2026-10-24 修复的"版本子资源越权"是什么问题？守卫怎么写的？**

- **考点**：IDOR 修复案例（可复述的真实安全事件）。
- **答案要点**：此前版本 detail/preview 只校验了用户对 documentId 的 view 权限，没校验 versionId 是否属于该 documentId——持有 A 文档查看权的人可以枚举 versionId 读到 B 文档的版本内容。修复是 `ExcelVersionService.requireVersionOfDocument`：`version == null || !documentId.equals(version.getDocumentId())` 就抛 `EXCEL_NOT_FOUND`"版本不存在或不属于当前文档"——**按不存在处理而非 403，不泄露"其他文档的版本存在"这一事实**；`versionDetail`/`previewVersion`/`restoreVersion` 三处同口径（PROJECT_TRUTH 记录的提交 `76d4f7d`）。
- **可能追问**：为什么返回 EXCEL_NOT_FOUND 而不是 Forbidden？——与"查无此文"同呈现是 fail-closed 的呈现策略：403 会确认资源存在，给枚举者提供信号。

**Q34.【问题】版本接口的权限口径是什么？回滚为什么在 Controller 里又包了一层锁？**

- **考点**：权限分级 + 锁的归属层。
- **答案要点**：`ExcelVersionController`：list/detail/preview 走 `permissionService.requireView`（只读）；create（手动快照）和 restore 走 `requireEdit`。restore 额外 `lockService.withLock(documentId, () -> versionService.restoreVersion(...))`——restore 内部含删块重建的大动作，必须与协同写/HTTP 保存互斥；锁包在 Controller 是因为 service 层的 restoreVersion 自己不拿锁（createSnapshot 的 manualSnapshot 在 service 内拿）。`previewVersion` 输出的就是"恢复到这个版本？"弹窗里用户看到的内容：sheet 数组 + celldata + 全部配置 JSON。
- **可能追问**：createSnapshot 里为什么要 `lockService` 注入却不 restore 用？——`manualSnapshot` 在 service 内 `withLock`，restore 的锁在 controller 加；两处入口不同层，但效果一致：同一把文档锁串行化。

**Q35.【问题】`createSnapshot` 里修过的 N+1 是什么？为什么 cellCount 要 `safeArraySize` 重新解析 JSON？**

- **考点**：批量查询优化 + 防御性解析。
- **答案要点**：P2-07：原实现每个 Sheet 单独查一遍 chunk（N 个 sheet = N 次大字段查询），现改为一次 `sheetService.listChunksByDocumentGrouped(documentId)` 全文档批量取回按 sheetId 分组，版本快照和导出（`buildWorkbookFile`）共用这个方法。`cellCount` 累加时对每个 chunk 的 `celldataJson` 调 `safeArraySize`——静态方法内部 try/catch 解析 JSON 数组取 size，坏 JSON 返回 0：统计字段绝不能因为一条脏数据让整个快照失败。
- **可能追问**：为什么不在写入时维护 cellCount？——写入路径（delta upsert）不感知全表状态，统计以快照时点重算最准；这是"统计可重算就不维护增量状态"的取舍。

**Q36.【问题】文档被彻底删除或清空回收站时，版本快照去哪了？**

- **考点**：级联清理完整性。
- **答案要点**：`ExcelVersionService.deleteByDocumentId` 按 documentId 先查出全部 versionId（`listVersionIds` 只 select id 列），再按"chunk → sheet → 主表"三层 `in(version_id(s))` 删除——与 `pruneOldVersions` 同一套清理骨架。调用方是回收站的物理清除链路：软删（回收站）阶段版本保留（用户可恢复），彻底删除阶段版本随之消失，不留孤儿三层表数据。
- **可能追问**：软删时版本还在，恢复文档后历史版本可直接用吗？——可以，版本表独立于 chunk/delta 主数据，恢复不重建版本；但恢复出来的当前数据与最新版本快照可能有差异（软删期间的增量已随 chunk 删除），这是回收站语义的一部分。

**Q37.【问题】`previewVersion` 返回的结构为什么能直接被前端渲染？和 detail 接口的分工？**

- **考点**：版本只读两接口。
- **答案要点**：`versionDetail` 是轻量版：版本头信息 + sheet 元信息列表（id/sheetIndex/sheetName/totalRows/totalCols/chunkCount），**不含 celldata**，用于版本列表点开看摘要。`previewVersion` 是重量版：把每个版本 sheet 还原成 Luckysheet/Univer 认的形状——name/index/row/column/status（active 映射）/config/hyperlink/images/luckysheet_conditionformat_save/chart，celldata 由 `listVersionChunks` 按块序拼接。注释原文：用户在"恢复到这个版本？"弹窗里看到的就是它的输出。两者都过 `requireVersionOfDocument` 归属守卫。
- **可能追问**：preview 大表会不会很大？——会，等于整表 JSON 一次下发；它是用户主动点预览的低频操作，且恢复了该时点完整现场，取舍成立；要优化可按 sheet 增量拉取。

---

## 五、异步导出与专用线程池（ExcelExportService / AppThreadPoolConfig / ExcelExportController）

**Q38.【问题】导出为什么是异步任务而不是同步接口？为什么用专用线程池而不是项目统一的 background_job？**

- **考点**：异步化动机 + 架构决策依据（PROJECT_TRUTH 明确例外）。
- **答案要点**：大表导出（POI 构建整个工作簿）秒级到分钟级，同步接口会占死 Tomcat 线程、前端超时。`createTask` 是"餐厅等位取号"：立刻落一条 PENDING 的 `excel_export_task` 返回 taskId，前端轮询详情拿进度/下载 URL。用专用 `excelExportExecutor` 而不是 `background_job` 是 PROJECT_TRUTH 明文例外："Excel 导出是例外：继续使用专用 excelExportExecutor，不是 background_job"——导出需要 Future 可取消、需要进程内句柄做中断，后台任务表的轮询模型（15 秒粒度）不适合交互式导出体验。
- **可能追问**：那 background_job 注释里的 EXCEL_EXPORT 是什么？——`BackgroundJob` 类注释中的 `EXCEL_EXPORT / FINANCE_IMPORT` 是预留描述，不是当前 Worker 能力（Worker 是硬编码 switch 只认 INGEST_DOCUMENT），不能拿注释当事实。

**Q39.【问题】背出 excelExportExecutor 的七个参数，并解释每个为什么这么设。**

- **考点**：线程池七参数（高频必背题）。
- **答案要点**：`new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), namedThreadFactory("argus-export-"), new ThreadPoolExecutor.CallerRunsPolicy())`。①corePoolSize=2：导出是重 IO+CPU（POI 写盘），并发 2 足够，防资源挤兑；②maximumPoolSize=4：积压时最多扩到 4；③keepAliveTime=60s：空闲线程 1 分钟回收；④workQueue=ArrayBlockingQueue(100)：**有界**队列，决不无界堆积拖垮内存；⑤threadFactory：`argus-export-` 前缀非 daemon 线程，线程转储可直接归因；⑥拒绝策略 CallerRunsPolicy：队列满时任务回到调用线程执行——**不丢任务，把背压交回调用方**；⑦（Spring 侧）`destroyMethod = "shutdown"` 优雅停机。
- **可能追问**：CallerRuns 的代价是什么？——队列满时导出跑在 Tomcat 请求线程上，该 HTTP 请求被拉长到导出时长；可接受的原因是触发条件已是"100 个任务积压"的极端场景，此时慢响应好过静默丢任务。要更细可改 AbortPolicy + 前端明确报"系统繁忙"。

**Q40.【问题】取消一个正在执行的导出，为什么必须持有 Future？取消是怎么真正生效的？**

- **考点**：P1-06 修复、协作式取消。
- **答案要点**：`submitTask` 里 `runningFutures.put(taskId, exportExecutor.submit(...))`。`cancelTask` 先校验（SUCCESS 不可取消），更新 DB 状态为 CANCELLED，再 `future.cancel(true)` 发中断——修复前只改状态，线程会继续跑完并把状态覆盖回 SUCCESS。`executeTask` 是协作式响应：每个阶段前 `isCancelled(taskId)` 复查（条件：`Thread.currentThread().isInterrupted()` **或** DB 状态为 CANCELLED——以 DB 为准是因为取消请求可能来自其他线程/进程），写盘后再查一次，被取消则 `deleteQuietly(filePath)` 清理半成品并 return，不再写 SUCCESS。
- **可能追问**：为什么以 DB 状态而不只看中断标志？——`future.cancel(true)` 只能打断本进程持有的线程；DB 状态是唯一跨请求可见的真相，两条件取或，宁可多停不可漏停。

**Q41.【问题】进程重启后，DB 里残留的 PENDING/RUNNING 导出任务会怎样？**

- **考点**：悬挂任务清扫（P2-10）。
- **答案要点**：任务句柄（Future）只在内存 `runningFutures`，重启后无人推进，DB 任务永久"进行中"。`sweepStaleTasks` 定时兜底：`@Scheduled(fixedDelayString = "${argus.rag.excel.export.sweep-interval-ms:300000}", initialDelay = 60s)`，把 `status in (PENDING, RUNNING)` 且 `update_time < now - staleExportMinutes(默认30分钟)`、且 `runningFutures` 里**没有**本进程句柄的任务 `markTerminal(FAILED, "导出任务中断（服务重启或超时），请重新发起导出")`。排除本进程句柄是关键：防止把正在正常执行（写大表超过 30 分钟）的任务误杀。
- **可能追问**：为什么不用启动时清理？——启动时无法区分"本机崩溃遗留"和"其他实例正在执行"（当前单实例可以，但代码按可多实例防御写）；30 分钟阈值 + 句柄排除是更保守的通用解。

**Q42.【问题】导出产物有生命周期吗？过期后用户下载会发生什么？**

- **考点**：产物过期清扫。
- **答案要点**：成功时写 `expireAt = now + retentionHours(默认24h)`、`objectKey = taskNo + ".xlsx"`，文件落 `uploadPath/export/{taskNo}.xlsx`（taskNo 是 UUID 截 20 位的随机名，不暴露原文件名）。`sweepExpiredTasks`（`@Scheduled` 15 分钟一轮、延迟 2 分钟启动）把 SUCCESS 且过期的任务置 EXPIRED 并删文件，删除前防御性复查 `expireAt` 仍过期（防 select 到 update 之间状态变化）。下载侧 `openResult` 三道检查：status 必须 SUCCESS、`expireAt` 未过、文件存在，任一不满足抛 `EXCEL_EXPORT_STATE`。
- **可能追问**：文件删失败怎么办？——`deleteQuietly` 只 warn 不掩盖状态语义：状态已是 EXPIRED 不可下载，孤儿文件留给磁盘清理，一致性以 DB 为准。

**Q43.【问题】导出的权限校验在哪些时点发生？为什么"创建"和"下载"都要重新验 view？**

- **考点**：权限时效性、fail-closed（76d4f7d 修复项）。
- **答案要点**：四个时点：①`createTask`：财务文件仅 FINANCE 可导出（`EXCEL_FINANCE_FORBIDDEN`）+ `permissionService.requireView(documentId, user)`——创建侧此前缺这道校验，2026-10-24 补齐（PROJECT_TRUTH：防任意用户对他人文档投递导出、消耗导出线程池并探测文档存在性）；②详情/取消/重试走 `getTaskForCurrentUser`：任务存在 → 非财务用户访问财务文档的导出任务拒（PERMISSION_DENIED）→ 非 admin 只能看自己的任务 → **再 `requireView(task.getDocumentId())` 复查**；③下载复用同一方法。②的复查注释原文："ACL 收回/角色变化后，此前创建的导出产物也不能继续下载（fail-closed）"。
- **可能追问**：导出列表为什么用 inSql 过滤财务文档？——`listTasks` 里非财务用户加 `qw.inSql("document_id", "SELECT id FROM excel_document WHERE finance_required = FALSE")`，子查询是纯常量无用户输入，权限过滤下沉到 SQL 且无注入风险。

**Q44.【问题】`buildWorkbookFile` 怎么把 chunk 还原成 xlsx？sheet 名冲突怎么处理？**

- **考点**：POI 导出细节、防御性编程。
- **答案要点**：`XSSFWorkbook` + `listSheetsByDocumentId` + 一次 `listChunksByDocumentGrouped`（P2-07 修 N+1）；先把 celldata 展开进 `grid[maxRows][maxCols]` 二维数组（`LinkedHashMap` 泛型），再逐行逐列 `createRow/createCell`；取值顺序 `v → m → 整个 value`，`writeCell` 按 Number/Boolean/String 分派、异常兜底 toString。`safeSheetName` 三连：清洗 `[\\/?*\[\]:]` 为 `_`、截 31 字符（xlsx 上限）、与已有 sheet 重名时追加 4 位随机后缀。
- **可能追问**：为什么先建 grid 再写 POI？——celldata 是稀疏乱序的，先落 grid 让"同格覆盖"语义与前端一致（后写覆盖先写），也避免 POI 上随机访问行开销。

**Q45.【问题】`retryTask` 为什么是"新建任务"而不是复活原任务？**

- **考点**：审计语义。
- **答案要点**：注释原文："重试语义 = 以同一文档新建一个导出任务（保留原任务作为失败记录与审计痕迹）"。校验复用 `getTaskForCurrentUser`（可见 + 归属 + 非财务隔离），且仅 `STATUS_FAILED` 可重试（`EXCEL_EXPORT_STATE` 拦截其他状态），然后直接 `createTask(task.getDocumentId())`。原任务的 errorMessage/finishTime 保留，失败原因可追溯；新任务有新 taskNo 与新产物文件。
- **可能追问**：和文档 ETL 的"死信复活"为什么策略不同？——ETL 任务幂等键绑 generation，同键复活有防并发双复活的条件 UPDATE；导出无幂等键概念，新建任务更简单且天然避免复用旧产物文件。

---

## 六、权限模型与分享（ExcelPermissionService / ExcelSheetAccessService / ShareLinkService）

**Q46.【问题】画出文档权限判定的完整短路链条，为什么顺序本身就是安全设计？**

- **考点**：权限优先级（本包核心安全题）。
- **答案要点**：`ExcelPermissionService.permissionOf(document, user)` 五道关：①`userCanAccessDocument`——财务文件（`financeRequired=true`）只有 `SystemRole.FINANCE` 能过，**系统管理员也被挡**；②财务角色对财务文件直接视为 `owner`（财务内部自治）；③ADMIN 视为 owner（仅非财务文件）；④`creatorId` 等于当前用户视为 owner；⑤查 `excel_document_permission` ACL 表返回 viewer/editor。全不命中返回 null。注释原文："顺序就是安全等级——把 ACL 挪到财务隔离前面就是越权漏洞"（若先查 ACL，被授予 viewer 的普通用户就能在第一步命中，财务隔离形同虚设）。
- **可能追问**：管理员为什么进不了财务文件？——产品决策：管理员账号被盗 ≠ 财务数据泄露，最小化财务面暴露面；PROJECT_TRUTH 硬约束"系统管理员没有隐式财务豁免"。

**Q47.【问题】canView/canEdit/canManage 的口径分别是什么？WS 通道为什么要传 user 的重载？**

- **考点**：权限 API 分层。
- **答案要点**：canView = `permissionOf != null`（viewer 及以上）；canEdit = owner 或 editor（另含"ADMIN 且过财务门"）；canManage = 仅 owner（另含 ADMIN 过门）。`require*` 系列（`requireView/requireEdit/requireManage`）不满足抛 `ForbiddenException`（403），控制器一行门禁。重载的意义：WebSocket 线程没有 SecurityContext，`canView(Long, CurrentUser)` / `canEdit(document, user)` 接受显式用户参数避免走 `currentUser()`（它会 `getRequiredCurrentUser()` 从 SecurityContext 取，WS 线程里取不到）。CollabController 全部用的是显式用户重载。
- **可能追问**：`canEdit(Long)` 里那句 `|| isAdmin()` 不是重复吗？——是防御性冗余（permissionOf 已把 ADMIN 归为 owner），但显式再判一次让"管理员可编辑"不依赖权限链实现细节；两处并存读起来冗余、语义上双保险。

**Q48.【问题】`accessibleDocumentIds` 对 admin 返回空列表，这是"没权限"的意思吗？**

- **考点**：语义化返回值 + SQL 下沉。
- **答案要点**：不是。注释原文："admin 返回空列表的语义不是没有权限，而是 admin 走普通查询不需要这份清单"——调用方（文档列表/回收站）对 admin 直接通查，不再按 ID 清单过滤。非 admin 时查自己的 ACL 行，且非财务用户加 `qw.inSql("document_id", "SELECT id FROM excel_document WHERE finance_required = FALSE")`——把财务文件从 ACL 结果里剔除，权限过滤下沉到 SQL，避免查回来再筛；子查询是常量字符串，无注入。
- **可能追问**：为什么 ACL 用户还可能看到财务文件？——若财务文件曾被误授 ACL，这个 inSql 是第二道闸：即便 permission 表里有行，清单里也不会出现财务文档 ID。

**Q49.【问题】`ExcelSheetAccessService` 防的是什么攻击？两步校验缺一不可的原因？**

- **考点**：IDOR（不安全直接对象引用）。
- **答案要点**：Excel 的 URL 同时携带 workbookId 和 sheetId，两者都来自客户端、可任意组合。只校验工作簿权限会漏"持有 A 工作簿查看权，却能读写挂在 A 路由下的 B 工作表"。`requireSheetPermission` 两步：①`sheetMapper.selectById(sheetId)` 后 `!workbookId.equals(sheet.getDocumentId())` 抛 ForbiddenException——Sheet 不存在、已删除或挂错工作簿一律按无权处理，**不泄露存在性**；②委托 `permissionService.canView/canEdit(workbookId, user)`（含财务隔离）。注释要求：任何 Controller/WebSocket/Service 的 Sheet 级操作都应经由本类，不得自行查询后判断。
- **可能追问**：loadAllCelldata/loadSheetChunks 都用它了吗？——是，`ExcelDocumentController` 两个数据接口都走 `sheetAccessService.requireSheetView(id, sheetId, user)`；StructuredDataController 的 `requireSheetAccess` 也是同一入口。

**Q50.【问题】分享链接的 token 怎么生成的？熵够吗？过期与撤销怎么实现？**

- **考点**：token 设计 + 诚实评估。
- **答案要点**：`ShareLinkService.createShare`：`UUID.randomUUID().toString().replace("-", "").substring(0, 12)` 得 12 位 hex，即 **48 bit 熵**，存 `excel_document_share` 表（token, document_id, role, created_by, expires_at = `NOW() + INTERVAL '7 days'`，JdbcTemplate 参数化 SQL）。诚实评估：48 bit 对小团队内网分享够用（枚举不现实），但要对外开放应换 `SecureRandom` 长 token 并加解析限流。撤销 `revokeShare` 按 `token + document_id` 物理删除，0 行抛 `EXCEL_SHARE_INVALID`"分享链接不存在"。解析 `resolveShare` 的 SQL 带 `expires_at > NOW()`，过期即无效。
- **可能追问**：为什么截 12 位而不是全 UUID？——可读/可抄写性好，链接短；在"内网分享 + 7 天过期"威胁模型下是合理取舍，我承认这是取舍不是最优。

**Q51.【问题】创建分享要过哪几道门？财务文件能被分享出去吗？**

- **考点**：管理动作门禁 + 财务外泄防线。
- **答案要点**：`createShare` 顺序四道：①文档存在（EXCEL_NOT_FOUND）；②`permissionService.canManage(documentId)`——分享把内容授予他人，属管理动作，仅 owner（或有权管理员）可发起，否则 ForbiddenException；③财务文件隔离：`isFinanceDocument && !userCanAccessDocument` 抛 `EXCEL_FINANCE_FORBIDDEN`"财务文件不允许生成分享链接"——注释原文"财务文件禁止通过分享链接外泄"；④role 白名单 `ALLOWED_ROLES = List.of("VIEWER", "EDITOR")`，其他值抛 `EXCEL_SHARE_INVALID`。解析侧二次防御：`resolveShare` 再查一次文档的 `financeRequired`，非财务用户解析财务分享抛 PERMISSION_DENIED——防"创建后文件被标成财务文件"的时序漏洞。
- **可能追问**：resolveShare 之后授权怎么持续生效？——诚实边界：当前 `resolveShare` 只返回 documentId+role 给前端，包内没有"解析后写入 ACL"的持久化动作，后续访问如何被鉴权属于跨包链路，我没有核实到落点，不编造（待验证）。

**Q52.【问题】`requireNormalDocument` 里 status 2 和 3 有什么区别？为什么要区分两种错误？**

- **考点**：状态机 + 错误语义。
- **答案要点**：`requireNormalDocument`：文档不存在或 `status == 3`（已彻底删除）抛 `EXCEL_NOT_FOUND`"文档不存在"；`status == 2`（回收站）抛 `EXCEL_TRASH_STATE`"文档已移入回收站"。区分的原因：前者是资源真没了（客户端应清缓存/跳转），后者是"东西还在但需要先恢复"（前端可提示去回收站找回），错误码驱动不同 UI 行为。status 1 = 正常。回收站整条链路（RecycleBinService）都依赖这个状态区分。
- **可能追问**：财务文件在回收站里还隔离吗？——隔离逻辑在 permissionOf 的第①关，先于任何状态判断之后由调用方组合；回收站列表还要 `accessibleDocumentIds` 过滤，两层叠加。

**Q53.【问题】文档列表一页 20 条，权限判定要打多少次数据库？你怎么看这个开销？**

- **考点**：权限判定的性能诚实分析。
- **答案要点**：`ExcelDocumentController.list` 对每条记录调 `permissionOf(d, user)` + `canEdit(d, user)` + `canManage(d, user)`——后两者内部又各调一次 `permissionOf(document, user)`。`permissionOf` 基于已加载文档不再回查文档表，但 ACL 命中路径要 `findPermission`（一次 select）；最坏情况每行 3 次 ACL 查询 × 20 行 = 60 次。诚实评估：文档页是低频页面、单表主键查询便宜，当前规模可接受；优化方向明确——一次 `selectList(document_id in (页内ids) and user_id = ?)` 建 map，或 `accessibleDocumentIds` 式批量预取。能指出问题并给方案，比声称"没有性能问题"可信。
- **可能追问**：为什么不在 SQL 里 join 权限一次出？——权限链含财务隔离/角色/创建者三层短路逻辑，Java 里表达比 SQL CASE 清晰可测；等成为瓶颈再下沉，先正确后优化。

---

## 七、财务域与结构化数据（FinanceImportController / FinanceImportService / StructuredDataController）

**Q54.【问题】财务导入主流程有哪几步？文件级幂等和知识库上传的秒传有什么本质区别？**

- **考点**：幂等语义的两种形态（注释原文对比）。
- **答案要点**：`importFile` 一个 `@Transactional` 七步：①`saveFile`（UUID 前缀 + 文件名消毒 `replaceAll("[\\\\/:*?\"<>|]", "_")`）+ `sha256Hex` 算指纹 + `assertFileNotImported`（同哈希已存在批次抛 `FINANCE_FILE_DUPLICATED`）；②`FinanceWorkbookExtractor.extract` 抽结构化流水，空结果拒绝；③`periodService.assertPeriodOpen(最早业务日期)` 关账守卫；④创建 `ExcelDocument` 且 `financeRequired = true`（进入 Excel 主链路可在线看）；⑤`parseAndSave` 分块入库；⑥批次版本管理（同 bookKey 旧批次 `is_current` 置 0，本批次 nextVersion 入库为当前）；⑦`insertRecords` 512 条一批写入 `finance_ledger_record`。区别：知识库上传同哈希是**静默秒传**（省存储是好事），财务导入是**显式拒绝**（注释："防止重复记账这种资金事故"）——同一语义（幂等）在两个域的相反策略。
- **可能追问**：为什么整个方法一个事务？——文件已落盘不受事务管，但"文档、sheet/块、批次、流水行"四个库内产物要么全有要么全无，不留半截账。

**Q55.【问题】关账守卫在哪几个时点生效？fail-closed 体现在哪？**

- **考点**：财务合规控制。
- **答案要点**：两个时点：①导入时——`extractionDateGuard` 取抽取记录里**最早的业务日期**（无数据用今天），该月已关闭则整个导入拒绝；②编辑时——`batchUpdateRecords` 对每行先 `periodService.assertPeriodOpen(current.getBusinessDate())`，关账月份的记录一个字段都不许动。`FinancePeriodService.close/reopen` 都要求 reason 必填（Controller 传 `body.get("reason")`）并计入审计。fail-closed 体现：守卫查询异常或期间记录缺失时按"不可操作"处理方向失败，宁可不能导入也不能关账月份数据被改。
- **可能追问**：为什么取最早日期而不是逐行校验？——导入是原子的，取最早日期做守卫是"快速失败"的近似；逐行校验在 insert 前还有机会，但最早日期已能覆盖"文件涉及已关闭月份"的场景且实现简单。

**Q56.【问题】台账编辑的"双层锁"是什么？为什么悲观锁之外还要指纹乐观锁？**

- **考点**：并发控制组合拳（注释原文可背）。
- **答案要点**：`batchUpdateRecords`：①行级悲观锁 `ledgerMapper.selectByIdForUpdate(request.id())`（SELECT ... FOR UPDATE）——把"读指纹 → 比对 → 更新"三步变成原子，持锁期间别人改不了这行；②指纹乐观锁——前端提交时带 `expectedFingerprint`，与 `recordFingerprint(current)` 比对，不等抛 `CONCURRENT_MODIFIED`"已被其他用户修改，请刷新后重试"。指纹 = 业务字段按固定顺序用 `|` 拼接（金额 `stripTrailingZeros().toPlainString()` 归一化）。诚实勘误：代码注释写"17 个字段"，`String.join` 实际列出 18 项（dayLabel/summary/counterparty/department/category/expenseName/businessDate + 7 个金额字段 + currencyCode/warnings），注释滞后，以实现为准。注释原文回答"为什么两层都要"：悲观锁防"同时写"（物理防护），乐观锁防"你不是基于最新数据在改"（语义防护）——A 读完后 B 改了又提交，A 再提交时悲观锁早已释放，只有指纹能拦住这种过期写。
- **可能追问**：指纹为什么用字段拼接不用 version 列？——表没有 version 列且字段由导入批量生成；拼接指纹不改表结构，`|` 分隔防字段值粘连歧义。代价是指纹算法前后端必须一致。

**Q57.【问题】财务统计 SQL 是手写字符串拼接，怎么保证没有注入？**

- **考点**：SQL 注入防线（本包防注入核心题）。
- **答案要点**：三条防线：①`ledgerFromClause`（overview/accountSummaries/dailySummaries 共用）拼的只是**表名、JOIN、常量条件骨架**，所有用户传入的值（regionName/batchId/currencyCode/日期）一律 `"b.region_name = ?"` + `params.add(value)`，执行时 `jdbcTemplate.query(sql, rowMapper, params.toArray())`——**值和数据永远不混在一起**（注释原文）；②MyBatis-Plus QueryWrapper 路径（`pageFilteredRecords`）全部走预编译参数；③代码里仅有的 `inSql`（`finance_required = FALSE`、`is_current = 1`）是纯常量子查询，无任何拼接输入。另一细节：keyword 跨四列模糊搜索用 `wrapper.and(w -> w.like(...).or().like(...)...)` 生成**带括号的 OR 组**，防 OR 吃掉外部 AND——SQL 布尔逻辑经典坑。
- **可能追问**：结构化数据查询（自然语言查表）为什么不担心注入？——它根本不生成 SQL：`queryStructuredData` 是纯内存过滤（下题展开）。

**Q58.【问题】`queryStructuredData` 的完整链路？为什么它天然防注入？**

- **考点**：结构化查询 + 服务端参数生成。
- **答案要点**：`StructuredDataController.queryData`（`POST /api/excel/schema/{sheetId}/query`）先 `requireSheetAccess`（工作簿+sheet 双重校验），把 filter JSON `{column, operator, value}` 交给 `TableIngestionService.queryStructuredData`：①`schemaService.extractSchema` 从数据本身提取 Schema（服务端生成）；②`resolveColumnIndex` 把用户传的列名/列索引解析为内部列号——**列的合法性由服务端 Schema 决定，找不到列直接返回错误**；③`CellDataTable.fromChunks` 把 celldata 展成内存矩阵；④`ColumnFilterStrategy.from(operator).matches(cellStr, targetValue)`——operator 是**白名单枚举**（eq/contains/gt/lt），非法算子没有匹配分支；⑤行数上限 `maxRows = min(limit, 500)` 硬顶。全程无 SQL 拼接、无用户输入进查询语言，注入面不存在。查询参数由服务端 Schema 生成/校验，这就是"参数服务端生成防注入"的落地。
- **可能追问**：性能呢？内存过滤不怕大表？——上限 500 行 + 限定单 sheet；这是"验证 Schema + 快速取数"的定位，不是全表分析引擎，量大应走导入后的台账 SQL 路径。

**Q59.【问题】`FinanceImportController` 每个端点第一行都是 `requireFinanceAdmin()`，为什么不在 SecurityConfig 统一配？admin 能看财务数据吗？**

- **考点**：角色校验位置 + 财务隔离边界。
- **答案要点**：`requireFinanceAdmin()` 委托 `currentUserService.requireFinance()`（内部校验 `SystemRole.FINANCE`，否则 403）。不放 SecurityConfig 统一配的原因：URL 前缀匹配粗粒度，而财务域里还有"自查工资条"这类**本人数据**接口在 FinanceReportController（走自己的归属校验），粗粒度拦截会把它们一起拦死；方法级显式调用让每个端点的权限要求自文档化（注释原文："每个端点的方法注释都标了谁能调"）。admin 不能看：PROJECT_TRUTH 硬约束"系统管理员没有隐式财务豁免"，`requireFinance` 只认 FINANCE 角色，ADMIN 一样 403。
- **可能追问**：控制器层校验会不会被绕过？——Service 层是兜底（导入服务注入 currentUserService），且数据隔离还有 `excel_document.finance_required` 在 Excel 通用链路的第一道关；纵深防御不止一层。

**Q60.【问题】财务批次版本化是怎么做的？"回退到旧版本"是数据搬运吗？**

- **考点**：批次版本模型。
- **答案要点**：同一账本（bookKey）每次导入产生 `batchVersion = nextBatchVersion(bookKey)`（最新版 +1，无历史为 1）；入库前把同 bookKey 全部批次 `is_current = 0`（`UpdateWrapper.eq("book_key",...).set("is_current", 0)`），本批次 `isCurrent = 1`。`activateBatch(batchId)` 回退：同一事务两步——先全部清零、再把目标置 1；注释原文："旧批次的流水行从未被删除，回退 = 换个视角看历史数据，而不是把数据搬来搬去"。查询默认只看 `is_current = 1` 的批次（`includeAll=false` 时 `inSql` 圈定），否则同一笔流水新旧版本出现两遍。
- **可能追问**：并发两次导入同一 bookKey，版本号会撞吗？——`nextBatchVersion` 是先读后写，无锁存在竞态；兜底是两次导入各自成批、后提交者覆盖 is_current，版本号可能重复但数据不丢（诚实边界：版本号唯一性没有唯一约束保障，待验证是否有 DB 约束）。

**Q61.【问题】财务流水写入为什么用 `JdbcTemplate.batchUpdate` 而不是 MyBatis-Plus 的 insert 循环？**

- **考点**：批量写性能。
- **答案要点**：`insertRecords` 用 `jdbcTemplate.batchUpdate(sql, records, 512, parameterSetter)`，27 列预编译 INSERT，每 512 行打包一次网络往返。注释原文：一个批次可能上万行，逐条 insert = 上万次数据库往返，批处理差一个数量级。MyBatis-Plus 的 `insert` 循环即使配合 rewriteBatchedStatements 也不如显式 batchUpdate 直白可控。金额用 `setBigDecimal`、日期用 `setObject(LocalDate)`，null 显式 `setObject(index, null)`。
- **可能追问**：为什么是 512？——经验值：兼顾单批包大小（27 列 × 512 行）与往返次数，太大单包超限风险、太小往返收益低；不是魔法数字，调参空间存在。

**Q62.【问题】`financeOverview` 十个聚合指标是怎么做到一次请求算完的？**

- **考点**：聚合查询复用。
- **答案要点**：`ledgerFromClause` 拼出共用 `FROM finance_ledger_record r JOIN finance_import_batch b ON r.batch_id = b.id [WHERE ...]`，随后 `recordCount/batchCount/accountCount/dateMin/dateMax/debitTotal/creditTotal/incomeTotal/expenseTotal/warningCount` 十个 `queryForObject` 共用同一 FROM/WHERE 和同一组 `?` 参数——注释原文"十来个聚合数一次性算完，避免前端发十个请求"。两个 PG 方言细节：`COUNT(DISTINCT (r.batch_id, r.sheet_name, r.currency_code))` 用行值表达式（PG 不支持多列 DISTINCT 带多参数的形式）；`warningCount` 用 `COUNT(CASE WHEN formula_text LIKE '%#REF!%' ... END)` 统计坏公式。币种分组小计单独一条 GROUP BY 查询。
- **可能追问**：十次往返不还是十条 SQL？——是十条 SQL 但共享参数组装与一次过滤口径，避免了应用层发十个 HTTP 请求 + 十次参数校验；真正的单条聚合需要 filter 语法，复杂度不值。

**Q63.【问题】一份 `finance_required=true` 的文件，在整个 Excel 通用链路里是怎么被"层层过滤"的？**

- **考点**：财务隔离的纵深防御全景。
- **答案要点**：至少六处独立关卡：①权限链第一关 `userCanAccessDocument`——非 FINANCE 连判定都进不去（详情/编辑/版本/回滚全挡）；②文档列表 `listAccessibleByPage` 带 financeUser 标志 + `accessibleDocumentIds` 的 `finance_required = FALSE` 子查询；③导出创建 `EXCEL_FINANCE_FORBIDDEN`；④导出任务详情/下载 `isFinanceExportTask` 复查；⑤导出列表 `listTasks` 的 inSql 过滤；⑥分享创建与解析双端拦截。设计逻辑：不依赖单一闸门——任何一条新链路忘记接财务门，数据仍被其余关卡挡住；而权限链第一关保证了"即使某关漏了也是 fail-closed 方向"。
- **可能追问**：这么多关卡有没有漏网？——诚实回答：RAG 检索侧对 Excel 文档引用的隔离走 `DocumentRoleGate`，属于问答包的防线，不在本包代码内，我没有在本包核实 Excel chunk 是否进入向量索引（待验证，跨包问题如实划界）。

---

## 八、撤销重做与操作日志（OperationLogService / OperationLogController）

**Q64.【问题】一行操作日志同时支撑哪两件事？oldValue 是什么时候抓的？**

- **考点**：审计与撤销同源设计。
- **答案要点**：注释原文："一行日志 = 一次单元格变更的完整证据：旧值 + 新值 + 谁 + 何时"，同时支撑 ①审计（运营中心回放谁改的）②撤销/重做（undo 用旧值覆盖回去，redo 反之——日志即历史）。oldValue 在**写库之前**抓：协同路径 `CollabService.handleEdit` 先 `sheetService.loadCellSnapshot(documentId, sheetId, r, c)` 取当前值（走"定位 chunk → 合并该块增量 → 精确匹配 r_c"，单格读成本 O(块内行数) 与整表无关），序列化后作为 `entry.setOldValue(...)`；HTTP 批量路径 `recordBatchEditAndApply` 对每个格子 `snapshotCellValue` 同样先读后写。
- **可能追问**：delta 和 operation_log 是不是重复存储？——不重复：delta 管"最新事实"（读合并用），operation_log 管"变更历史"（审计/撤销用），读写分离互不拖累（注释原文）。

**Q65.【问题】撤销批量编辑（比如一次粘贴 200 格）怎么保证原子？undo 的实现步骤？**

- **考点**：batchId 分组 + 事务撤销。
- **答案要点**：批量编辑落库时所有格子关联同一个 `batchId = UUID.randomUUID()`（`recordBatchEditAndApply` 先逐格写日志再 `sheetService.batchUpdateCells`，**同一个 `@Transactional`**——撤销需要的"编辑前状态"和编辑本身要么都成功要么都不存在）。`undoLast`：查该用户该文档最新一条 CELL_EDIT/BATCH_EDIT（`orderByDesc("id").last("limit 1")`），若是 BATCH_EDIT 则按 `batchId` 取出全部行，对每行组装"oldValue 写回"的 update 走 `applyHistoryBatch`（内部就是 `batchUpdateCells`，同样拿文档锁、写 delta、失效缓存），并插入一条 `operationType = UNDO` 的日志行（oldValue/newValue 互换）。整体在 undo 方法自己的事务里。
- **可能追问**：为什么按 id 倒序取"最近一条"而不是时间戳？——id 单调且是主键索引，等价于插入顺序，避免时间戳同毫秒歧义。

**Q66.【问题】redo 怎么实现的？连续 undo 后再编辑，redo 会发生什么？**

- **考点**：redo 语义边界。
- **答案要点**：`redoLast` 找该用户最新一条 `operationType = UNDO` 的日志，UNDO 行的 `oldValue` 字段存的是"撤销前的当前值"（即被撤掉的新值），redo 把它写回去，再插一条 REDO 日志。诚实边界：当前实现**没有清空 redo 链的逻辑**——undo 后用户又编辑了新内容，redoLast 仍会找到那条 UNDO 并把旧值写回，这可能覆盖掉中间的新编辑（没有经典 undo 栈的"分支截断"语义）。撤销能力定位于"单步补救"，完整多级撤销栈（含截断）是明确的演进点。
- **可能追问**：undo/redo 走 delta 写路径会触发压实和缓存失效吗？——会，`applyHistoryBatch → batchUpdateCells → writeCellsGrouped → saveDeltasForChunk + invalidateSheet + maybeCompactSheetDeltas`，撤销在存储层与普通编辑无差别。

**Q67.【问题】用户 A 撤销后，正在看同一文档的用户 B 什么时候能看到值变回去？为什么？**

- **考点**：诚实边界——undo 不广播。
- **答案要点**：诚实回答：undo/redo 是 HTTP 接口（`POST /api/excel/{documentId}/audit/undo`），`OperationLogService` 没有 STOMP/SimpMessagingTemplate/Outbox 依赖，**撤销不会实时广播**——B 的界面上值不会立刻变，要等下一次拉取（刷新、重新打开、按块加载 chunks 走 `listChunksBySheetId` 读到合并后的新事实）。这是当前实现的已知限制：产品语义上撤销影响他人视图却只有拉模式同步。改进路径：undo 落库后像编辑一样写 Outbox 事件（type=UNDO）由 publisher 广播。
- **可能追问**：undo 会更新 LWW 时间戳或文档版本号吗？——都不会：`lastEditTimestamps` 不动、`bumpVersion` 不调，undo 写入绕过了协同仲裁层——这意味着 undo 后其他客户端若基于旧值再提交编辑，LWW 不会拦。这是我能指出的第二个一致性感口，修复方向是 undo 也走 `handleEdit` 的仲裁管线。

**Q68.【问题】为什么 undo 只能撤销自己的操作？审计日志查看和撤销的权限分别是什么？**

- **考点**：权限口径收口。
- **答案要点**：`undoLast(documentId, userId)` 的 QueryWrapper 带 `.eq("user_id", userId)`——只检索当前用户自己的日志，A 无法撤销 B 的编辑（避免互相干扰与权限混乱）。`OperationLogController`：`GET /logs`（最近 500 条，`.last("limit 500")`）要求 `requireView`；`POST /undo`、`POST /redo` 要求 `requireEdit`。日志响应里含 newValue/userId/userDisplayName/createTime，viewer 可看审计但不可改数据。
- **可能追问**：undo 接口没有防重放吗？——连续两次 undo 会撤掉两条不同的日志（第二次撤的是再前一条），语义自洽；幂等性由"每次撤销产生 UNDO 日志行、redo 可逆"保障，不需要防重。

**Q69.【问题】`ExcelMetaHandler` 是干什么的？为什么 Excel 包要自己的 MetaObjectHandler？**

- **考点**：MyBatis-Plus 自动填充。
- **答案要点**：`ExcelMetaHandler` 实现 `MetaObjectHandler`：insertFill 用 `strictInsertFill` 填 `createTime/updateTime`，updateFill 填 `updateTime`——配合实体上 `@TableField(fill = FieldFill.INSERT)` 等注解自动赋值，业务代码不用手动 set 时间。Excel 包自带一个而不复用全局的原因（诚实回答）：包内实体（如 delta 的 `updateTime` 在 `saveDeltasForChunk` 手动 set）时间字段命名与填充策略是包内约定，独立 handler 让 Excel 表的时间语义自治；`strictInsertFill` 只在字段为 null 时填充，不覆盖显式赋值。
- **可能追问**：手动 set 和自动填充冲突吗？——不冲突，strict 语义以显式值为准；但两套并存增加理解成本，理想是统一走填充。

**Q70.【问题】协同编辑会写两张日志表：`operation_log` 和 `excel_collab_log`，它们分工有什么不同？**

- **考点**：审计表设计分离。
- **答案要点**：`OperationLogService.recordCellEdit` 写的 `operation_log` 是**变更证据**：oldValue + newValue + userId + batchId + operationType（CELL_EDIT/BATCH_EDIT/UNDO/REDO），支撑撤销重做与操作审计。`CollabService.handleEdit` 里另插的 `ExcelCollabLog` 是**协同过程记录**：字段是 rowNo/colNo/actorId/actorName/requestId（存发送者 STOMP 会话）/baseRev/serverRev/merged/conflict 标志/valueText/resultValueText——它回答的是"这次协同写入基于哪个版本、合并结果如何"，供运营中心分析冲突率与合并行为。一个面向"值的历史"，一个面向"协同会话的历史"。
- **可能追问**：为什么 collabLog 失败只 warn 不回滚？——注释原文"失败不影响同步"：它是观测数据，持久化主链路（delta）已成功，观测表故障不应放大为用户可见错误。

**Q71.【问题】`getRecentLogs` 只查最近 500 条，undo 会不会因为日志太多而查不到？**

- **考点**：两个查询的边界区分。
- **答案要点**：不会。500 条限制只作用于**展示接口**（`getRecentLogs` 的 `.last("limit 500")`，注释"最近 500 条，供前端展示"）。`undoLast`/`redoLast` 是独立查询：`selectOne` + `orderByDesc("id").last("limit 1")` 直接定位该用户最新一条日志，与展示窗口无关，日志表再大也能撤销最近一次操作。诚实边界：undo 只能撤"最新一条"，不支持"撤第 N 条"的栈式操作——那是产品定位（单步补救）而非查询限制。
- **可能追问**：日志表无限增长怎么办？——当前没有清理任务（诚实边界）；`excel_collab_log`/`operation_log` 都只增不删，量大后应按时间归档分区， undo 依赖的是"最新一条"所以归档老数据不影响功能。

---

## 本包高危追问 Top5

**T1. "多实例部署你这个协同编辑会怎么坏？"**——如实列出四件进程内状态：LWW 时间戳（仲裁失效）、在线用户表（名单分裂）、文档写锁（互斥失效，并发写交错）、chunk 缓存（失效不跨实例，读到旧值直到 TTL 120s）。Outbox `selectPending` 无认领语义还会导致重复广播与非保存实例的真实消息丢失窗口（PROJECT_TRUTH 已核实）。答法：单实例 + 主备是当前容量下的诚实选择，演进按"Redis 原子时间戳 → PG advisory lock → Outbox 加 SKIP LOCKED 认领"逐项落地，不为简历凑架构。

**T2. "LWW 用客户端时间戳，恶意用户改本机时间或伪造 timestamp 会怎样？"——`msg.timestamp()` 客户端可任意填，快钟能恒胜、超大值可长期独占格子。承认这是真实弱点；缓解事实：编辑还需过 `canEdit` 权限与 revision 校验，伪造只影响同文档协作者；修复方向是改用 `serverTimestamp`（字段已预留）或服务端单调计数器做 LWW 键。**

**T3. "CallerRunsPolicy 队列满时导出跑在 Tomcat 线程上，不怕拖垮 Web 容器？"——承认代价：极端积压（队列 100 满 + 4 线程忙）时请求线程被导出拖住。辩护理由：不丢任务 > 慢响应，且触发即"系统过载"信号本身应限流；改进：换 AbortPolicy + 前端友好报错，或加提交侧信号量限流，两案我都能讲清取舍。**

**T4. "自动快照没拿文档写锁，会不会快照出脏数据？"——承认：`createSnapshot` javadoc 要求持锁，`SnapshotService.scheduledSnapshot` 与 `tryAutoCellSnapshot` 未持锁。影响分析：快照读走 `listChunksByDocumentGrouped`（基数 + 增量合并），单条语句一致性由 DB 快照读保障，最坏是快照到"部分批次已写入"的中间态（该时点本就没有全局一致版本语义）；修复成本低（调度侧包 `lockService.withLock`），我会在改造清单里排进去。**

**T5. "版本保留注释说手动优先，真的做到了吗？"——没做到，如实回答：`pruneOldVersions` 按时间序清理，不区分 snapshotType，最老的手动版本同样会被删。这是注释与实现的偏差，我核对过代码；修法是清理查询加 `orderByAsc(snapshot_type 优先级)` 或先保 MANUAL 再补自动。能主动指出注释失真处，恰是"文档数字与代码一致"纪律的体现。**

---

> 诚实声明：以上所有方法名、字段名、常量、SQL 片段均核对自当前源码（基线 `2d7ed06` 后工作区）。
> 已标注的待验证项：分享解析后的授权持续生效机制、协同光标前端节流参数、财务批次版本号唯一约束。
> 类注释与实现不一致处（CHUNK_SIZE 注释 500/实际 1000、快照保留"手动优先"未实现、
> recordFingerprint 注释 17 字段/实际 18 项、tryAutoCellSnapshot 无生产调用方）均已如实标注。
