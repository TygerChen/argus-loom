# 代码级面试题库 · 02 文档与 ETL 包

> 覆盖范围：`com.argus.rag.document`（DocumentController / DocumentUploadService /
> UploadedDocumentFinalizer / DocumentFileStore / UploadInputGuard / DocumentDeleteService /
> StaleProcessingDocumentRecoveryRunner）与 `com.argus.rag.ingestion`
>（EtlDocumentIngestionProcessor / DocumentIngestionAsyncService / parser·reader·transformer /
> ChunkService / VectorIngestionService），外加 `com.argus.rag.job` 队列三件套。
> 事实基准：当前源码 + PROJECT_TRUTH（2026-10-24）+ `docs/operations/live-stack-verification-20261024.md` 实测演练。
> 所有方法名、字段名、SQL 均核对过真实代码。**文档口径与代码冲突处已用 ⚠ 标注，面试时以代码为准。**

---

## 〇、先背牢：三张状态机，别混

**Q0.【问题】这个包里有几张状态机？分别长什么样？**

- **考点**：状态机口径（最容易答混的地方）。
- **答案要点**：三张，各自独立。① **文档状态**（`DocumentStatus` 枚举）：`UPLOADED → PROCESSING → READY | FAILED`，用户在列表页看到的；② **队列任务状态**（`BackgroundJob` 常量）：`QUEUED → RUNNING → SUCCEEDED | DEAD_LETTER`，没有 PENDING/RETRY 中间态；③ **观测任务状态**（`IngestionJobStatus`）：`PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED`，是 `ingestion_jobs` 观测表。三者映射：一次上传 = 1 个文档 PROCESSING + 1 条 background_job QUEUED + 1 条 ingestion_jobs PENDING。
- **可能追问**：⚠ 很多资料把"QUEUED/RUNNING/SUCCEEDED/DEAD_LETTER"说成文档状态——错，那是 `background_job` 表的状态。文档没有 QUEUED，只有 PROCESSING。

---

## 一、DocumentUploadService（三段式分片上传）

**Q1.【问题】分片上传为什么设计成 init / chunks / complete 三段式？各段做什么？**

- **考点**：大文件上传协议设计。
- **答案要点**：三段各管一件事。`initUpload`：校验参数（`validateInitRequest`）→ 按"秒传 → 续传 → 新建"三级尝试，返回 `uploadId`、`chunkSize`、`chunkCount`。`uploadChunk`：单分片接收，算 SHA-256（`FileHasher.sha256Hex`）与客户端传的 `chunkHash` 比对，`fileStore.putChunk` 写 MinIO，`documentUploadChunkMapper.upsert` 记元数据。`completeUpload`：`ensureAllChunksPresent` 校验分片齐且按序 → `fileStore.compose` 合并 → `finalizeUploadedDocument` 落文档 → 会话置 COMPLETED。三段式让每一小步可独立失败重试，而不必整文件重来。
- **可能追问**：直传和小分片上传的界限？——`MAX_DIRECT_FILE_SIZE = 10MB` 走 `uploadDocument` 直传；`MAX_FILE_SIZE = 256MB`、单分片 `MAX_CHUNK_SIZE = 10MB` 走分片。

**Q2.【问题】init 时秒传、续传、新建的优先级怎么排的？各自的判定条件是什么？**

- **考点**：去重与续传的编排。
- **答案要点**：`initUpload` 里顺序是：① 秒传——`documentMapper.selectByGroupIdAndFileHash(groupId, fileHash)` 查到 **READY** 文档且 `duplicatePolicy` 不是 `CREATE_NEW`，就 `createInstantUploadedDocument` 新建一条文档记录（**复用原 bucket/objectKey，不拷文件**），返回 `UploadInitResponse.instant(documentId)`；② 续传——`selectLatestReusableSession(groupId, userId, fileHash)`，SQL 条件是 `status in ('INIT','UPLOADING','COMPLETING') and expires_at > now()`，命中就带回已上传分片索引列表；③ 新建——`buildUploadSession`，`uploadId` 是去横线 UUID，`expiresAt = now + 24 小时`（`SESSION_EXPIRE_HOURS`）。
- **可能追问**：CREATE_NEW 时同一份文件会不会真传两遍？——会，这是产品语义："作为新版本重新入库"需要新对象，`finalObjectKey` 里的随机 UUID 保证不覆盖旧文件。

**Q3.【问题】分片校验做了哪几层？只靠客户端哈希够吗？**

- **考点**：不信任客户端输入。
- **答案要点**：`uploadChunk` 里 `requireChunk` 校验 chunkIndex 在 `[0, chunkCount)`、分片非空、大小不超会话 `chunkSize`；然后服务端**自己重算** `FileHasher.sha256Hex(chunk.getBytes())`，与请求里的 `chunkHash` 比对，不一致抛 `UPLOAD_INCOMPLETE`（"分片校验失败，数据可能已损坏"）。init 时还有一致性校验：`chunkCount` 必须等于 `(fileSize + chunkSize - 1) / chunkSize`，不等直接拒绝。客户端哈希只是"客户端声称"，服务端重算才落库——两端各算一次，坏了哪一段都能发现。
- **可能追问**：为什么 chunkIndex 从 0 开始还要判 `< 0`？——防御式校验，负数索引若漏过会在 objectKey 拼接和排序环节产生不可预期行为，入口一次挡掉。

**Q4.【问题】同一分片传两次会怎样？分片幂等怎么实现的？**

- **考点**：upsert 幂等。
- **答案要点**：对象存储侧，`fileStore.chunkKey(groupId, uploadId, chunkIndex)` 生成的 key 是确定性的 `uploads/{groupId}/{uploadId}/chunks/{chunkIndex}`，重传直接覆盖同 key 对象；数据库侧，`DocumentUploadChunkMapper.upsert` 是 `insert ... on conflict (upload_id, chunk_index) do update`，把 chunk_size/chunk_hash/objectKey 等字段刷新。所以同一分片重传 N 次结果一致，断点续传时 `selectByUploadId` 返回的索引列表就是"哪些片已就位"的真相。
- **可能追问**：DB upsert 失败了，MinIO 里那个分片对象怎么办？——代码里有补偿：upsert 包在 try-catch 里，RuntimeException 时 `fileStore.deleteQuietly(bucket, objectKey)` 删掉刚写的对象再抛。顺序是"先写对象、后写库"，反过来会有"库里有了对象没有"的更糟状态。

**Q5.【问题】completeUpload 里那个 CAS 是防什么的？**

- **考点**：条件更新防并发重复 complete。
- **答案要点**：`completeUpload` 先 `requireOwnedActiveSession`（存在、属于当前用户、未过期、状态不是 COMPLETED/COMPLETING），再执行条件 UPDATE：`set status=COMPLETING where uploadId=? and status=UPLOADING`，**影响行数为 0 就抛 `UPLOAD_SESSION_INVALID`**。防的是两个并发 complete（或 complete 与重试撞车）：两个请求都读到 UPLOADING，但数据库行锁保证只有一个 UPDATE 生效，另一个拿到 0 行失败退出。会话状态 INIT/UPLOADING/COMPLETING/COMPLETED 四态就是围绕这个 CAS 设计的。
- **可能追问**：为什么 requireOwnedActiveSession 就把 COMPLETING 拒了，CAS 不是多余？——不是多余。require 是查时快照，查完到 UPDATE 之间状态可能被别的请求改掉；CAS 是数据库层面的最终裁决，check-then-act 的竞态必须靠条件更新收口。

**Q6.【问题】合并失败后现场是什么状态？用户还能重试吗？**

- **考点**：失败路径 + 补偿 + 事务回滚的组合。
- **答案要点**：`completeUpload` 的 try 块里依次是 compose → finalizeUploadedDocument → 会话置 COMPLETED；catch RuntimeException 后做两件事：`fileStore.deleteQuietly` 删合并产物 + **循环删掉所有分片对象**，然后 rethrow。rethrow 导致整个 `@Transactional` 回滚——COMPLETING 状态回滚回 UPLOADING，分片元数据行也回来。所以用户面对的是"分片齐全、可再次 complete"的会话。MinIO 写入是即时的（不受 Spring 事务管），所以必须手动补偿删除，这是"外部资源 + 本地事务"的经典组合拳。
- **可能追问**：删分片对象是不是过度补偿？——不是，合并已完成时分片对象已无用处；但如果失败发生在 compose 之前（如 finalize 阶段），分片对象删了用户就要重传。这是当前实现的真实取舍：宁可让用户重传，不留悬空分片。

**Q7.【问题】文件名和扩展名的安全校验在哪做的？拦什么攻击？**

- **考点**：上传入口的安全防线。
- **答案要点**：`UploadInputGuard.sanitizeFileName`：`StringUtils.cleanPath` + 截取最后一个 `/` 之后的部分，挡**路径穿越**（`../../etc/passwd` 之类），限长 255。`extractFileExt`：取最后一个点后的扩展名、转小写，必须命中白名单 `SUPPORTED_EXTENSIONS = Set.of("txt","md","pdf","docx")`，否则抛"文件类型不支持"——白名单同时挡了 exe/jsp 等危险类型。`normalizeContentType` 空值回落 `application/octet-stream`，限长 128。
- **可能追问**：只看扩展名不 sniff 文件头，伪造扩展名怎么办？——确实不 sniff，恶意 PDF 内容会在 ETL 解析阶段被 PDFBox 抛异常拦下（文档 FAILED）。这是已知边界：类型校验靠扩展名白名单 + 解析器兜底，没有魔数校验。

**Q8.【问题】DocumentUploadService 的构造器里为什么要手动 `new UploadedDocumentFinalizer`？它是 @Component 啊。**

- **考点**：读代码的细心程度 + Spring 装配理解。⚠ 这是一处真实的代码异味，面试敢讲是加分项。
- **答案要点**：构造器注入了 `vectorIngestionService`、`elasticsearchChunkIndexService`、`applicationEventPublisher`、`ingestionJobService`、`backgroundJobService` 五个依赖，然后在构造器末尾 `this.finalizer = new UploadedDocumentFinalizer(...)` 手动组装。结果是容器里存在**两个** Finalizer 实例（Spring 管一个，这里 new 一个）。行为上等价（Finalizer 无状态、无 AOP 代理，new 出来也能用），但绕过了 Spring 管理：单测 mock 不了、后续给 Finalizer 加 @Transactional/@Async 会静默失效。更顺的写法是直接注入 Finalizer Bean。
- **可能追问**：为什么当时这么写？——推测是拆分重构时的过渡形态（Finalizer 的职责就是从 UploadService 拆出来的），能说出"识别得出异味 + 改法"就够，不用替代码辩护。

**Q9.【问题】上传会话 24 小时过期，过期后那些分片对象谁清理？**

- **考点**：诚实边界 + 资源生命周期。
- **答案要点**：坦白说：**没有自动清理**。代码里没有扫描过期会话/孤儿分片的定时任务，`deleteQuietly` 只在上传失败补偿和软删除路径出现。过期会话行留在 `document_upload_sessions`，分片对象留在 MinIO。靠的是 completeUpload 前的 `expiresAt` 校验挡住"用过期会话继续传"。要收口的话应加一个定时清理 Runner（按 expires_at 找过期会话删对象删行）——我能主动指出这个缺口。
- **可能追问**：量级上这是问题吗？——上传量级小（百级/天），孤儿对象增长极慢，属于"知道但不紧急"的技术债，和队列选型"够用就好"是同一种务实。

---

## 二、UploadedDocumentFinalizer + DocumentDeleteService（落库入队与手动重试）

**Q10.【问题】上传接口返回 documentId 的时候，ETL 任务已经存在了吗？为什么入队要在上传事务里？**

- **考点**：数据库当队列的强一致红利。
- **答案要点**：在。`UploadedDocumentFinalizer.finalizeUploaded` 在上传的 `@Transactional` 内依次做三步：`documentMapper.insert`（状态 `PROCESSING`）→ `ingestionJobService.registerIngestDocumentJob`（登记观测记录）→ `enqueueIngestionJob` 写 `background_job`。任务行和文档行**同事务**落库：要么都提交（Worker 接手），要么都回滚（上传报错，不留文档）。入队失败（DB 异常）向上抛回滚整个上传——注释原话："没有 ETL 任务的文档永远不会变 READY，宁可上传失败也不能留假文档"。这个强一致是"数据库当队列"独有的；MQ 方案要靠 Outbox 模式迂回才能拿到。
- **可能追问**：MinIO 的文件呢，它不在这个事务里？——对，MinIO 上传在事务外先完成。事务回滚时会 `compensateExternalIndexes` + `fileStore.deleteQuietly` 补偿删对象，顺序是"先外部、后事务，失败补偿外部"。

**Q11.【问题】幂等键 `INGEST:{documentId}:{retryGeneration}` 为什么带代数？初始上传是几？**

- **考点**：幂等键设计。⚠ 注意：retryGeneration **不是递增计数器**。
- **答案要点**：初始上传 `finalizeUploaded` 里写死 `enqueueIngestionJob(documentId, groupId, 0)`，即 `INGEST:{documentId}:0`。手动重试时（`DocumentDeleteService.retryFailedDocumentIngestion`）取 `System.currentTimeMillis()` 当代数——所以它是"0 或时间戳"，不是 +1 递增。带代数的理由：手动重试走**全新键**，不会撞上历史已 SUCCEEDED/DEAD_LETTER 的旧任务行；而同一代数内重复触发（用户连点、事件重放）命中幂等键直接复用。
- **可能追问**：那死信复活分支什么时候走到？——只发生在**同键**再次入队时（如历史事件重放对已死信的键再 enqueue）。手动重试因为换了新键，走的是"插入新行"，不触发复活。两条路径别混。

**Q12.【问题】手动重试端点的完整行为？它对文档状态有什么前置要求？**

- **考点**：状态机守卫。
- **答案要点**：`POST /api/documents/{documentId}/retry-ingestion` → `DocumentDeleteService.retryFailedDocumentIngestion`（`@Transactional`）：① `requireGroupOwner` 群组管理员校验；② 查文档，**只有 `DocumentStatus.FAILED` 才放行**，其他状态抛"仅失败文档支持重新处理"；③ 条件 UPDATE 把状态重置 PROCESSING、清空 `failureReason` 和 `processedAt`（影响 0 行抛异常）；④ 用时间戳代数构造新幂等键 enqueue `INGEST_DOCUMENT`，maxAttempts 3。之后队列的退避/租约/死信机制完整接管本轮。
- **可能追问**：为什么重置状态和入队要在同一事务？——若状态先置 PROCESSING 而入队失败，文档会永远卡在 PROCESSING（前端永远转圈）；同事务保证"状态复位"和"新任务存在"原子生效。

**Q13.【问题】软删除文档为什么不走任务队列？**

- **考点**：同步 vs 异步的边界判断。
- **答案要点**：`softDeleteDocument` 是同步三步：`documentMapper.markDeleted`（软删标记，影响 0 行抛 DOCUMENT_NOT_FOUND）→ `vectorIngestionService.deleteDocumentVectors(documentId)` → `elasticsearchChunkIndexService.deleteDocumentChunks(documentId)`。不走队列的理由：删除是轻量操作（按 documentId 过滤器删向量和 ES chunk），毫秒级完成，异步化反而引入"删了还能搜到"的窗口期；而且**删除必须即时生效**——用户删完立刻搜，不该再命中。这和 ETL 相反：ETL 重（解析+向量化）才值得异步。
- **可能追问**：删除是软删，向量 ES 却真删了，恢复怎么办？——对，当前软删后向量/ES 不保留，恢复=重新入队跑一遍 ETL（原文档对象还在 MinIO）。delete-then-rebuild 可行正是因为 ETL 幂等。

**Q14.【问题】`compensateExternalIndexes` 为什么每个删除都单独 try-catch？**

- **考点**：补偿路径的容错原则。
- **答案要点**：`UploadedDocumentFinalizer.compensateExternalIndexes` 对 `deleteDocumentVectors` 和 `deleteDocumentChunks` 各包一层 try-catch，失败只 warn 不抛。逻辑：此时主流程已经失败、正在向上抛原始异常，补偿是"尽力而为"的善后——如果补偿再抛，会**吞掉根因异常**，排查方向全错。且留一点点残留也安全：下次 ETL 开头 `cleanupProcessingArtifacts` 会再清一遍。和"业务事实 vs 观察数据"的划线同理：补偿失败不改变"上传已失败"这个用户可见事实。
- **可能追问**：会不会补偿成功但事务最终提交了？——不会，补偿只出现在 catch 块里，执行完必然 rethrow，事务必然回滚，不存在"补偿了却成功"的路径。

---

## 三、BackgroundJobService / Worker + DocumentIngestionAsyncService（队列与 ETL 状态机）

**Q15.【问题】为什么用 PostgreSQL 表当任务队列，不上 RabbitMQ/Kafka？**

- **考点**：队列选型论证（高频）。
- **答案要点**：三个理由：① 项目已强依赖 PostgreSQL，不引入新运维组件；② 任务量级是百级/天，MQ 的吞吐优势用不上；③ **事务强一致**——任务行和业务数据（文档行）同事务落库，天然不丢消息，MQ 要靠 Outbox 模式迂回。表结构语义和 MQ 几乎一一对应（idempotency_key=消息去重键、next_run_at=延迟投递、DEAD_LETTER=死信队列），任务量真上来迁移路径平滑。面试补一句权衡：DB 轮询的代价是秒级延迟和轮询开销，对分钟级容忍的 ETL 完全够。
- **可能追问**：什么信号出现时你会上 MQ？——任务量到万级/天、或出现需要广播/高扇出的场景、或轮询对 DB 的压力可观测地上升。

**Q16.【问题】claimDueJobs 怎么保证多 Worker 抢任务不重复不阻塞？把 SQL 说出来。**

- **考点**：FOR UPDATE SKIP LOCKED（本包最硬的题）。
- **答案要点**：`BackgroundJobMapper.selectDueJobs` 的 SQL：`SELECT * FROM background_job WHERE (status='QUEUED' AND (next_run_at IS NULL OR next_run_at <= #{now})) OR (status='RUNNING' AND lease_until IS NOT NULL AND lease_until <= #{now}) ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED`。SKIP LOCKED 让被其他 Worker 锁住的行直接跳过、不等待。领取流程（`claimDueJobs`，`@Transactional`）：候选行加锁 → 逐条 `claim` 条件 UPDATE（`where status=QUEUED or RUNNING且租约过期`，同时 `attempt_count+1`、`started_at` 用 COALESCE 只记首次）→ 影响行数=1 才算抢到 → `updateLease` 回写 `lease_owner`/`lease_until`。整个过程一个短事务原子提交。
- **可能追问**：为什么"选候选"和"认领"要分两步，候选查出来就可能被抢走？——因为 SKIP LOCKED 只保证不加锁等待，不保证选出的行属于你；`claim` 的条件 UPDATE（where status 守卫）才是最终裁决，抢不到就跳过。选-抢两段式是乐观锁思想在队列上的应用。

**Q17.【问题】租约机制解决什么问题？代价是什么？**

- **考点**：at-least-once + 幂等的教科书分工。
- **答案要点**：解决"Worker 拿到任务后崩溃"。领取时写 5 分钟后的 `lease_until`，Worker 凭自己的 workerId 完成：`complete` 的 SQL 带 `WHERE lease_owner = #{workerId}`，不是持有者改不了状态。Worker 挂了，租约到期后任务重新出现在 `selectDueJobs` 的 RUNNING 分支里（`lease_until <= now`），其他实例自动接管。**代价**：任务还在跑但超过 5 分钟时可能被第二个 Worker 重复执行——所以队列保证 at-least-once，**幂等由业务层兜底**（ETL 开头先清后建，天然可重跑）。每轮轮询新建 workerId（`worker-` + 8 位 UUID），写入 lease_owner 作为"归我管"的凭证。
- **可能追问**：租约为什么 5 分钟？——Worker 里 `now.plusMinutes(5)` 写死（服务里另有 `LEASE_MINUTES = 5` 常量）。取值依据：ETL 常规耗时远小于 5 分钟，同时不能太长否则真崩溃后恢复慢。可以配的话按 P99 耗时 ×2 定。

**Q18.【问题】⚠ 队列失败的退避到底是 1/5/15 分钟吗？把 failOrRetry 的索引计算走一遍。**

- **考点**：读代码纠错能力（本题库最重要的口径修正之一）。
- **答案要点**：声明是 `RETRY_BACKOFF_MINUTES = {1, 5, 15}`，但**实际首次退避是 5 分钟**。推导：`claim` SQL 在领取时就把 `attempt_count` 加了 1，所以第一次执行失败时读到的 `attemptCount = 1`；`failOrRetry` 里 `backoffIndex = Math.min(attempts, RETRY_BACKOFF_MINUTES.length - 1) = min(1, 2) = 1`，取到的是 `RETRY_BACKOFF_MINUTES[1] = 5` 分钟。第二次失败 attempts=2 → index 2 → 15 分钟。第三次 attempts=3 → `newAttemptCount=4 > maxAttempts=3` → DEAD_LETTER。**所以真实序列是 5 分钟 → 15 分钟 → 死信，数组第 0 位（1 分钟）是不可达的死代码**。文档口径"首次退避 1 分钟"与代码不符，面试引用时以这个推导为准。
- **可能追问**：怎么修？——把索引改成 `newAttemptCount - 1` 或直接 `Math.min(attempts - 1, 2)`，一行改动 + 一个断言 `nextRunAt = now+1min` 的单测就能锁住行为。

**Q19.【问题】死信复活的并发安全怎么保证的？为什么不用 updateById？**

- **考点**：条件 UPDATE + MyBatis-Plus 的坑。
- **答案要点**：`enqueue` 查到同键任务是 DEAD_LETTER 时走 `reviveDeadLetter`：用 `UpdateWrapper` 显式 set 全部字段——`status=QUEUED`、`attempt_count=0`、清 `lease_owner`/`lease_until`/`last_error_code`/`last_error_message`/`finished_at`、`next_run_at=now`——并且 **where 条件带 `status = DEAD_LETTER`**。两个并发请求同时复活同一个死信，只有一个 UPDATE 影响 1 行，另一个影响 0 行记日志放弃，不会双复活。不用 `updateById` 的原因写在注释里：**MyBatis-Plus 默认忽略 null 字段**，`leaseOwner=null` 这种"清空"语义会被丢掉，错误信息永远清不掉。
- **可能追问**：SUCCEEDED 的任务同键再入队呢？——直接复用返回（幂等命中），不会重跑：上传链路键基于 documentId 派生，文档没变就不该再跑 ETL。

**Q20.【问题】Worker 的轮询为什么用 fixedDelay 不用 fixedRate？轮询失败怎么处理？**

- **考点**：Spring 调度细节。
- **答案要点**：`@Scheduled(fixedDelayString = "${argus.job.poll-interval-ms:15000}", initialDelayString = "${argus.job.poll-initial-delay-ms:20000}")`。fixedDelay 是"上一轮执行完再等 15 秒"，fixedRate 是"每 15 秒触发不管上轮完没完"——轮询必须前者，否则一轮慢了下一轮叠进来，多批任务同时跑。每轮 `pollAndDispatch` 新建 workerId，领到的任务 `workerExecutor.execute` 提交线程池异步执行（领取在调度线程，执行在线程池）。整个 try-catch 包住，轮询失败只 warn 不抛——**这一轮空转不可怕，可怕的是把调度线程搞死**，15 秒后下一轮自然恢复。
- **可能追问**：执行也在线程池里，那执行失败会波及调度线程吗？——不会，`executeSafely` 在 workerExecutor 线程里把一切异常转给 `failOrRetry`。

**Q21.【问题】Worker 的 switch 只认 INGEST_DOCUMENT，其他类型呢？BackgroundJob 注释里的 EXCEL_EXPORT 是真的吗？**

- **考点**：⚠ 又一处口径陷阱。
- **答案要点**：`executeSafely` 是**硬编码 switch**：`case "INGEST_DOCUMENT"` 解析 payload 里的 groupId 调 `ingestionService.ingestDocument`，完成后 `jobService.complete`；`default` 分支 `failOrRetry` 带 `JobFailure("UNKNOWN_JOB_TYPE", ...)`——**看不懂的任务转死信而不是默默忽略**，因为"看不懂"本身就是需要人看的异常。`BackgroundJob` 类注释里的 `EXCEL_EXPORT / FINANCE_IMPORT` 是预留描述，**当前 Worker 不支持**；Excel 导出走专用 `excelExportExecutor`，不经这张表。没有 processor 注册机制。
- **可能追问**：payload 里 groupId 解析失败（readGroupId 返回 null）会怎样？——`documentId != null && groupId != null` 不成立，**不执行 ETL 直接 complete 置 SUCCEEDED**——这是个真实的边界瑕疵：坏 payload 的任务被静默标记成功而非死信，值得在面试里主动指出。

**Q22.【问题】ingestDocument 的四步流程和事务边界？为什么 @Retryable 和 @Transactional 叠在一个方法上？**

- **考点**：ETL 主流程 + 注解叠加语义。
- **答案要点**：`ingestDocument`（`@Retryable(retryFor=RuntimeException.class, maxAttempts=3, backoff=@Backoff(delay=2000, multiplier=2.0))` + `@Transactional`）四步：① `markJobRunningQuietly`（观测表 PENDING→RUNNING）；② `cleanupProcessingArtifacts` 先清 chunk/向量/ES 旧产物；③ `documentIngestionProcessor.process`（解析→清洗→预览→切片→落库→向量）；④ `syncSearchIndex` 从 `document_chunks` 反查 ready chunks 写 ES，然后 `markDocumentStatus(READY)` + `markJobSucceededQuietly`。两个注解都在代理上生效：每次重试都是**新的一次事务**（代理重新进方法），第 N 次重试不会背着第 N-1 次的脏事务。
- **可能追问**：第 2 步成功第 4 步失败，重试时第 2 步删什么？——删的就是第 2 步刚建到一半的产物，这就是"先清后建"存在的意义：重跑第 N 遍结果和第 1 遍一致。

**Q23.【问题】⚠ 坏 PDF 会进死信吗？两层重试是叠加还是互斥？**

- **考点**：2026-10-24 演练修正的核心结论。
- **答案要点**：**不进死信，两层重试互斥**。链路：坏 PDF → `PdfDocumentParser` 包装成"PDF 文档解析失败"的 BusinessException（RuntimeException 子类）→ 被 `@Retryable` 捕获按 2s/4s/8s 重试 3 次 → 耗尽后 `@Recover` 的 `recover` 接管：`cleanupProcessingArtifacts` + `markDocumentStatus(FAILED, 截断后的原因)` + `markJobFailedQuietly`，然后**正常返回**。Worker 看到方法正常结束 → `jobService.complete` → 任务 SUCCEEDED。所以业务性失败终结于"**文档 FAILED + 任务 SUCCEEDED**"，队列的 5/15 分钟退避和死信对它完全不可达。实测证据（live-stack-verification-20261024）：1KB 损坏 PDF 真实上传，`argus.job.dead_letter` 指标**全程 0.0**。
- **可能追问**：这暴露了什么健壮性缺口？——ETL 里的**瞬态故障**（如 MinIO 读抖动）也会被 `@Recover` 一次性判死为文档 FAILED，队列级重试救不了它。改进方向是让 `recover` 对可分类的瞬态异常重抛，交给队列退避；代价是要重新设计用户可见状态（FAILED 攒在队列里 vs 立即可重试）。说得出这个权衡比背结论值钱。

**Q24.【问题】markJobRunningQuietly 这组"Quietly"方法为什么全吞异常？划线原则是什么？**

- **考点**：哪些失败可容忍、哪些必须传播。
- **答案要点**：`markJobRunningQuietly` / `markJobSucceededQuietly` / `markJobFailedQuietly` 三个方法都是 try-catch 包住 `ingestionJobService` 的调用只打 warn。划线原则：**ingestion_jobs 是观察窗口不是业务事实**——用户看到的是 `documents.status`，观测表坏了顶多监控盲区，不能反过来毁掉 ETL 主流程或已完成的成果。特别注意 `markJobSucceededQuietly`：ETL 已全部成功、文档已 READY，此时观测表更新失败若抛出会回滚整个成功事务——绝不允许。和 QaService"记账失败可容忍、落库失败必须抛"是同一条原则的两种应用。
- **可能追问**：markSucceeded 找不到 RUNNING 任务会怎样？——`findJobInStatus` 返回 empty Optional，`ifPresent` 静默跳过。兼容任务表启用前的历史文档，观测缺失不影响业务。

**Q25.【问题】三张表的状态更新失败各是什么表现？updated=0 在哪些地方被当错误抛？**

- **考点**：条件更新的失败语义。
- **答案要点**：三处显式校验影响行数：① `markDocumentStatus`（文档状态 READY/FAILED）updated=0 抛 `DOCUMENT_PROCESSING_FAILED`——文档是业务事实，更新失败必须暴露；② `persistPreviewText` updated=0 同样抛；③ `retryFailedDocumentIngestion` 重置状态 updated=0 抛"重置文档状态失败"。对照：`BackgroundJobService.complete` 影响行数不校验（非持有者改不动是预期内静默失败）；`reviveDeadLetter` updated=0 是"竞争失败"正常分支。**同一套条件更新，在业务事实上抛、在竞争语义上算正常**——这就是失败语义的设计感。
- **可能追问**：markDocumentStatus 的 where 为什么带 groupId？——双重守卫（id+groupId），文档一定属于其群组，防止跨群组误写，和 `selectByIdAndGroupId` 的读取守卫对称。

---

## 四、EtlDocumentIngestionProcessor + Reader/Parser/Transformer（ETL 流水线前段）

**Q26.【问题】EtlDocumentIngestionProcessor.process 的完整流水线顺序？ES 索引在哪一步？**

- **考点**：⚠ 口径修正——ES 不在 Processor 里。
- **答案要点**：`process(documentId, groupId)` 六步：① `findDocument`（`selectByIdAndGroupId`，查不到抛"待入库文档不存在"）；② `new StoredObjectDocumentReader(...).get()` 从 MinIO 读 + 解析；③ `textCleanupTransformer.apply` 清洗；④ `persistPreviewText` 预览落库；⑤ `chunkTransformer.apply` 切片 → `chunkService.saveChunkDocuments` 落库；⑥ `vectorService.ingestChunks` 写向量。**ES 索引不在 Processor 内**，在外层 `DocumentIngestionAsyncService.syncSearchIndex`（调 `ElasticsearchChunkIndexService.indexReadyChunks`）——因为 ES 要等 chunk 全部落库拿到主键后统一索引。每步完成打 INFO 日志，`rawDocuments/cleanedDocuments/chunkDocuments` 数量可直接在日志里对账。
- **可能追问**：为什么向量化在 Processor 里、ES 在外面？——向量按 chunk 实体直接写（文档 ID 过滤器已能幂等），ES 索引读的是 `selectByDocumentId` 反查结果，放外层能让"切片落库"和"索引"的先后关系显式化。

**Q27.【问题】StoredObjectDocumentReader 做了什么？元数据里埋了哪些字段、给谁用？**

- **考点**：元数据是检索侧的本钱。
- **答案要点**：实现 Spring AI `DocumentReader.get()`：校验 documentId/groupId → `resolveBucket`（文档实体指定桶优先，空则默认桶）→ `parserFactory.getParser(fileExt)` 按扩展名拿解析器 → `storageService.getObject` 拿流 → `parser.parse` 转纯文本 → 构建单个 `Document`，metadata 放 `groupId`、`documentId`、`fileName`、`source`（`minio://bucket/objectKey` 格式）。这些字段一路透传：fileName 进向量 metadata 供引用展示，source 供溯源定位。
- **可能追问**：解析大文件会不会 OOM？——`parser.parse(inputStream)` 返回整篇 String，200MB PDF 全文进内存是真实约束；当前 256MB 上限下靠 JVM 堆兜着，真要优化得按页流式抽取（PDFTextStripper 支持区间），我可以说出方向但项目没做。

**Q28.【问题】解析器工厂怎么路由？加一种新格式要动几处？**

- **考点**：工厂 + 策略模式。
- **答案要点**：`DocumentParserFactory` 维护 `extension -> parser` 的 LinkedHashMap，无参构造注册 `TxtDocumentParser / MdDocumentParser / PdfDocumentParser / DocxDocumentParser` 四个，注册走 `parser.supports(extension)` 自声明。`getParser` 先 `normalizeExtension`（去前导点、`toLowerCase(Locale.ROOT)`，`.TXT`/`TXT`/`txt` 归一）再查表，查不到抛"不支持的文档类型"。加新格式：写一个 `DocumentParser` 实现 + 在构造器的 `List.of(...)` 加一行，两处。扩展点收口在工厂，上层（Reader/Processor）完全无感。
- **可能追问**：txt 和 md 都是纯文本，为什么分两个解析器？——Md 后续可做结构保留（标题层级直接喂给结构感知切片），Txt 走通用解码（`TextDecodingSupport` 处理编码探测）；分开才有独立演化的空间。

**Q29.【问题】TextCleanupTransformer 清了什么、留了什么？为什么代码块要特殊处理？**

- **考点**：清洗的"不伤害"边界。
- **答案要点**：`clean` 三步：换行归一（`\r\n`/`\r` → `\n`）→ `CONTROL_CHARACTERS` 正则删控制字符（`\x00-\x08\x0B\x0C\x0E-\x1F`，注意**保留 `\t` 和 `\n` 本身**）→ `cleanLines` 逐行处理：普通行 `strip` + 行内 `[ \t]+` 压成单空格，连续 3+ 空行（`EXCESSIVE_BLANK_LINES = \n{3,}`）压成 `\n\n`。**代码围栏内原样保留**：`cleanLines` 用 FenceMarker 状态机（`` ` ``/`~`、≥3 个、缩进 ≤3）把围栏段落整体收集、跳过压缩——因为代码里的缩进和空行是语义，压了代码就废了。
- **可能追问**：清洗会不会把英文缩进式（4 空格）代码块破坏？——4 空格缩进不是 fence 语法的代码块，会被当普通文本压缩——这是真实边界，项目按 CommonMark 围栏语法（``` / ~~~）划定保护范围，缩进式代码块不在保护内。

**Q30.【问题】预览文本怎么生成的？为什么 updated=0 要抛异常？**

- **考点**：小步骤里的失败语义。
- **答案要点**：`persistPreviewText` 把所有清洗后 Document 的 text 过滤空白后用 `reduce((l, r) -> l + "\n" + r)` 拼接、trim、截断到 `PREVIEW_MAX_LENGTH = 200` 字符，写入 `documents.previewText`（全空白时写 null）。UPDATE 带 `id + groupId` 双条件，**影响 0 行抛 `DOCUMENT_PROCESSING_FAILED`**——文档行此刻必然存在（第①步刚查过），0 行只可能是并发删除或数据异常，这种"不可能发生但发生了"的情况必须炸出来而不是静默继续，否则后面所有步骤都在对幽灵文档工作。
- **可能追问**：预览为什么截 200 字符不截 200 token？——预览是给列表页 UI 的人类看的，字符数直观；token 是给 embedding 的度量，两者服务不同目的，不混用单位。

---

## 五、StructureAwareChunkTransformer + ChunkService + VectorIngestionService（切片与幂等落库）

**Q31.【问题】讲一下结构感知切片的完整降级链路。**

- **考点**：切片算法主干（本包核心算法题）。
- **答案要点**：`chunkDocument` 先 `splitBySections`：`collectHeadings` 逐行扫 Markdown ATX 标题（`^#{1,6}\s+`），把文本按标题边界切章节（第一个标题前的前导文本也算一段）；然后每个章节 `splitSection`：整体不超 max 就整章一块；超了走 `splitOversizedPieces`——先按连续空行（`BLANK_LINES = \n\s*\n+`）拆段落，段落仍超再按句子（`isSentenceBoundary`：`。！？；!?;`）拆，还不超的走 `splitRemainingOversized` 按 `maxTokens * charsPerToken` 步长硬截断；最后 `mergePieces` 贪心合并相邻小片段。**降级链：标题 → 段落 → 句子 → 字符硬切，能不切碎就不切碎**。
- **可能追问**：贪心合并的判定条件（canMerge）精确是什么？——`estimateTokens(current..next合并) <= targetTokens`，或"当前块还不足 target 且合并后不超 max"（`currentTokens < targetTokens() && estimateTokens(candidate) <= maxTokens()`）——第二个条件允许"半满块吸收小邻居到接近上限"，避免产生大量碎片。

**Q32.【问题】⚠ 240/320/32 这组参数，"token"是怎么算的？说"240 token ≈ 480 字"对吗？**

- **考点**：口径与实现的真实差距（诚实边界必考）。
- **答案要点**：不对，要修正。配置是 yml 的 `ingestion.chunking`：target 240 / max 320 / overlap 32 / charsPerToken 2（Java 默认 500/800/80/2，**以 yml 为准**）。但看实现：`estimateTokens(String text)` 就是 `Math.max(1, text.length())`——**直接返回字符数，根本没用 charsPerToken 折算**。所以 merge/split 的预算实际按"240~320 个字符"执行；`charsPerToken` 只在两处生效：`splitByTokenBudget` 的步长（`maxTokens * charsPerToken = 640` 字符）和 `overlapStart` 的回退距离（`32 * 2 = 64` 字符）。结论：**代码内部自身就不自洽**——预算判断按字符、硬切步长和重叠按字符×2。面试话术："参数名和 Javadoc 说的是 token 预算，实现是字符数近似，中文场景 2:1 折算的意图有，但没贯穿 estimateTokens，这是我复核代码后确认的真实行为，不吹成精确分词。"
- **可能追问**：为什么不接真分词器？——工程折中：精确分词要引入模型或词典、拉慢 ETL，而切片预算对误差不敏感；正确演进是用评测集（Recall@5）验证当前近似是否够用，不够再上真分词。

**Q33.【问题】代码块里的 `#` 为什么不会误判成标题？这个坑不讲会怎样？**

- **考点**：细节严谨性。
- **答案要点**：`collectHeadings` 逐行扫描时维护围栏状态 `Fence(marker, length)`：围栏未开时才 `parseHeading`；遇到 ```` ``` ```` 或 `~~~`（≥3 个、缩进 ≤3）开围栏并记录 marker 和长度，围栏开着时只检查 `isClosingFence`（同 marker、长度不小于开的、行尾只余空白），内部所有行——包括 `# 注释`——都不当标题。如果不处理，Python 脚本里的 `# import` 会被切成新章节，技术文档分块全乱。
- **可能追问**：围栏没闭合（漏了结尾 ```）会怎样？——`cleanLines`/`collectHeadings` 的扫描中 fence 一直开着到文末，剩余文本整体被当代码块保护，标题不再识别——宁可漏切不可错切，是合理的失效方向。

**Q34.【问题】overlap（重叠）是怎么实现的？为什么第一块没有重叠？重叠越多越好吗？**

- **考点**：接缝保险的取舍。
- **答案要点**：`buildDocuments` 里对非首块计算 `overlapStart = max(sectionStart, start - overlapTokens * charsPerToken)`，即块起点向前回退 64 字符（yml 32×2），但不越过所属章节的起点（防止把上一章内容混进来）；然后 `trimRange` 去首尾空白。第一块 `chunks.isEmpty()` 直接用原 start，没有可回退的上一块。重叠是"接缝保险"——防关键句正好跨缝被切丢——但不是越多越好：重叠加大存储、加大重复召回噪音（同一句在两张卡片都被召回）。32 token ≈ 一到两句，够覆盖缝两侧。
- **可能追问**：重叠内容会重复向量化、重复被引用吗？——会，两张卡片各自含这段文字，检索命中哪张算哪张。这是刻意接受的成本。

**Q35.【问题】切片元数据埋了什么？检索后引用定位靠哪个字段？**

- **考点**：分块与检索的契约。
- **答案要点**：`buildDocument` 给每块写四个 metadata：`sectionPath`（章节标题路径）、`charStart`/`charEnd`（原文位置）、`chunkStrategy = "structure-aware-token-budget-v1"`；Spring AI Document 的 id 是 `源文档id:chunkIndex`。落库时 `ChunkService.buildMetadataJson` 再补充 documentId/groupId/chunkIndex/charStart/charEnd 并序列化成 `metadata_json`。检索命中后 `CitationAssembler` 做**引用定位**靠的就是 sectionPath + charStart/charEnd——能把"答案来自第几章第几段"还原出来。分块时多埋的每一分元数据，都是检索后排查的本钱。
- **可能追问**：chunkIndex 是原文顺序吗？——是**稠密重编号**：`buildChunkDocuments` 里 `chunks.size()` 作 index，空白文本被跳过不占号，所以 index 连续但 charStart 可能不连续（跳过了被清洗掉的空白区）。

**Q36.【问题】ChunkService 落库为什么"先删后插"？单条 INSERT 最多塞多少行？**

- **考点**：幂等 + PostgreSQL 参数上限。
- **答案要点**：`saveChunkDocuments`（`@Transactional`）：`validateIdentifiers` → `buildChunkDocuments`（空列表抛"文档切片结果为空"）→ **`documentChunkMapper.deleteByDocumentId` 先删旧切片** → `persistChunkBatches` 批插 → `backfillChunkIds` 回填主键。先删后插保证重跑时 chunk 集合精确等于本次结果，不留旧残留——这是切片层幂等的第一重。批量上限：每行 10 个参数（`INSERT_BATCH_PARAMETER_COUNT`），PostgreSQL 单语句 65535 参数上限，`MAX_INSERT_BATCH_SIZE = (65535 - 128) / 10 = 6540` 行/批，超了自动分批。
- **可能追问**：6540 这个余量 128 是干嘛的？——给 SQL 里其他占位符和驱动行为的保险余量，卡着上限写 6553 反而脆；留常数余量是防上限被其他因素（如 where 参数）吃掉。

**Q37.【问题】backfillChunkIds 为什么要反查回填？insertBatch 不是有 useGeneratedKeys 吗？**

- **考点**：批量返回主键的可靠性防御。
- **答案要点**：`insertBatch` 声明了 `useGeneratedKeys="true" keyProperty="id"`，但多行批插时驱动对返回主键的支持不一（部分驱动只返回最后生成的键）。所以 `backfillChunkIds` 兜底：若内存实体有 id 为 null，按 `selectByDocumentId` 反查，构建 `chunkIndex -> id` 映射回填；反查数量与内存数量不等、或映射缺项，一律抛"批量保存切片后……失败"。**向量写入强依赖 chunk.id**（`validateChunk` 第一条就是"向量写入前必须先完成 chunk 落库"），所以这一步不能含糊。
- **可能追问**：为什么向量非要 chunkId？——向量 metadata 里带 `chunkId`，检索命中后凭它回查 chunk 全文和定位信息，没有 id 的向量是"孤儿命中"。

**Q38.【问题】向量写入的幂等有哪两重保障？确定性 UUID 怎么生成的、为什么用它？**

- **考点**：幂等三重保障的最后一层（高频）。
- **答案要点**：`VectorIngestionService.ingestChunks` 两重：① 写前 `deleteExistingVectors`——按 `documentId` 的 FilterExpression 逐文档 `vectorStore.delete(filter)`（`cleanupProcessingArtifacts` 在任务开头还清过一遍，这是任务级+步骤级两道）；② **确定性 UUID**：`buildStableDocumentId` 用 `UUID.nameUUIDFromBytes((documentId + ":" + chunkIndex).getBytes(UTF_8))` 生成（UUIDv3，MD5 派生），同文档同分片永远生成同一 ID——即使删除有漏网，重复写入也是同 ID 覆盖而非新增。好处：幂等不依赖"删除一定成功"，写入本身就是收敛的。
- **可能追问**：为什么不用随机 UUID？——随机 UUID 每次重跑都生成新 ID，失败重试会在向量库里堆出多份同义向量（检索重复命中、引用错乱）。确定性 ID 的代价是可预测，但向量库内无越权查询面，可接受。另外批大小 `addBatchSize` 默认 9（`ingestion.vector.add-batch-size` 可配，fallback 到 pgvector 的 max-document-batch-size），`embedAndStore` 分批调 `vectorStore.add`。

**Q39.【问题】整条 ETL 的"幂等三重保障"连起来说一遍，每层各挡什么？**

- **考点**：把散点收拢成体系（综合题）。
- **答案要点**：三层各挡一个时间尺度的问题：① **任务级"先清后建"**（`cleanupProcessingArtifacts`，删 chunk/向量/ES 各自独立 try-catch）——挡 at-least-once 重复执行和上一轮失败残留，保证第 N 遍与第 1 遍等价；② **切片级"先删后插"**（`ChunkService.saveChunkDocuments` 的 `deleteByDocumentId` + 批插）——挡同任务内步骤重试的表内残留，保证 chunk 表精确等于本次结果；③ **向量级"先删后插 + 确定性 UUID"**——挡外部系统（pgvector）删除不彻底的漏网，同源同 ID 覆盖。三层叠起来，"队列 at-least-once + 业务幂等"的分工才闭环——这也是腐坏文件演练零残留的底层原因。
- **可能追问**：只靠确定性 UUID、不做先清后建行不行？——不行：chunk 表是自增主键的行，没有稳定业务键可覆盖，必须先删；ES 文档同理。确定性 UUID 只救得了向量库这一层。

---

## 六、StaleProcessingDocumentRecoveryRunner + 失败注入演练

**Q40.【问题】服务重启后，卡在 PROCESSING 的文档会怎样？RecoveryRunner 具体做了什么、没做什么？**

- **考点**：⚠ 恢复口径（此前多份文档在这里失真）。
- **答案要点**：`StaleProcessingDocumentRecoveryRunner` 是 `ApplicationRunner`，启动完成后在 `@Transactional` 里调 `documentMapper.markStaleProcessingDocumentsFailed(PROCESSING, FAILED, DEFAULT_FAILURE_REASON("文档处理任务因服务中断未完成，请重试。"), null, now)`。SQL（DocumentMapper.xml）：`update documents set status/failure_reason/processed_at/updated_at where status=#{fromStatus} and deleted=false`——**没有时间窗**：`staleBefore` 参数传 null，XML 里的 `<if test="staleBefore != null">` 分支不生效，所有遗留 PROCESSING 文档一律置 FAILED。也**不重新入队**，靠用户手动重试。此前注释声称的 `document.ingestion.processing-timeout-minutes` 时间窗从未生效，不要引用。
- **可能追问**：Worker 租约接管和这个 Runner 会不会打架？——会重叠：Runner 把文档置 FAILED 后，队列里遗留的 RUNNING 任务租约到期被接管继续跑，成功后 `markDocumentStatus(READY)` 会把状态改回 READY——类注释自己写了这一点（"Worker 接管并执行完成后会把状态改回 READY"）。最终一致，但中间会有"FAILED 又变 READY"的状态跳变，前端要能容忍。

**Q41.【问题】为什么设计成"全量置 FAILED + 手动重试"而不是"自动重新入队"？**

- **考点**：恢复策略的取舍论证。
- **答案要点**：自动重入队看似贴心，实则危险：服务中断的原因未知——如果是 OOM、DB 故障或配置问题导致的批量失败，启动即自动重放会把刚恢复的服务再次打挂（雷群效应），而且失败原因没有人工确认过。置 FAILED 把决定权交给用户（群组管理员点"重试入库"），重试入口有状态守卫、有新幂等键、走完整队列机制，可控可观测。这是"故障恢复宁保守勿激进"的取舍：**卡死的代价是用户手动点一下，自动恢复失败的代价是二次雪崩**。
- **可能追问**：量大了手动点不过来怎么办？——可以加"管理员批量重试 FAILED 文档"入口，复用 `retryFailedDocumentIngestion` 的单文档逻辑循环调用；仍然优于启动时无脑重放。

**Q42.【问题】失败注入演练是怎么做的？实测到了什么？"腐坏文件零丢失"的证据链是什么？**

- **考点**：用实测证据说话（面试的杀手锏）。
- **答案要点**：演练记录在 `docs/operations/live-stack-verification-20261024.md` §4b，全程真实 API + 本机真实栈（PG16/ES8.9/MinIO/后端 local profile）：① 上传 1KB 损坏 PDF（`%PDF-1.4` 头 + 乱码）→ 上传 200 秒回，文档 PROCESSING；② ETL 内 Spring Retry 2s/4s/8s 耗尽 → `@Recover` 置 FAILED，`failureReason = "PDF 文档解析失败"`（用户可读）；③ `argus.job.dead_letter` 指标**全程 0.0**——实证两层重试互斥；④ `POST /retry-ingestion` 200 接受 → 复位 PROCESSING → 再失败回 FAILED，闭环可重复。"零丢失"的含义：失败后**不留半成品**——`recover` 里 `cleanupProcessingArtifacts` 把 chunk/向量/ES 全清，原文档对象仍在 MinIO 可随时重试，用户看到的是确定、可重试的 FAILED 而非永远转圈。
- **可能追问**：这个演练没覆盖什么？——记录里明确写了未执行项：Worker 崩溃租约接管实机演练（杀进程等 5 分钟，仅单测 `BackgroundJobServiceTest` 的 expiredLeaseIsClaimable 等 10 用例覆盖）、死信复活实机观察（仅单测 deadLetterJobIsRevivedOnEnqueue）、ES 重建演练（**无产品入口**，全库没有 reindex 端点，只能逐文档重跑 ETL，已列为改进项）。诚实说出"哪些是实测、哪些是单测推定"比全说"测过了"可信。

**Q43.【问题】如果把这套 ETL 的失败语义重新设计一次，你会改哪三处？**

- **考点**：架构演进思考（收束题）。
- **答案要点**：① **让 recover 对瞬态异常重抛**：给异常分类（业务性如解析失败 → 照旧 FAILED；系统级如 IO/DB 抖动 → 重抛给队列走退避重试），修复"两层重试互斥"导致的瞬态故障一次判死缺口；② **补上"业务失败也进队列重试"或明确放弃队列重试**：现状业务失败只吃 Spring Retry 的 2s/4s/8s，若下游是真不可用（如 embedding 服务重启中），三次 14 秒内的重试太密，要么放宽 Spring Retry 间隔要么让队列接管；③ **加 ES 重建管理端点**：从 `document_chunks` 回灌 ES，故障恢复不再依赖逐文档重跑。每条都对应演练中实证过的缺口，不是空想。
- **可能追问**：为什么现在不改？——当前量级小、FAILED 文档有人工重试兜底，收益/风险比不划算；这三条属于"知道痛点、留有方案、等量级触发"的典型技术债管理。

---

## 七、本包高危追问 Top5（面试官最可能往死里问的）

**1. "你说退避是 1/5/15 分钟，那第一次失败后任务多久重跑？"**
陷阱在 `failOrRetry` 的 `backoffIndex = Math.min(attempts, 2)`：claim 已把 attempt_count 加到 1，所以第一次失败取 index 1 → **5 分钟**，1 分钟档位不可达。背文档数字直接露馅，背代码推导直接加分。

**2. "坏文件重试三次还是失败，最后任务是什么状态？文档呢？"**
必须答出三状态并存：文档 FAILED（@Recover 置的，原因可读）、background_job SUCCEEDED（@Recover 吞异常正常返回，Worker complete）、ingestion_jobs FAILED（quietly 记的）。**不会进 DEAD_LETTER**——两层重试互斥，实测 dead_letter 指标全程 0。

**3. "Worker 执行到一半进程被 kill，这个任务的下场是什么？"**
完整链：任务 RUNNING + lease_until 5 分钟后过期 → selectDueJobs 的 RUNNING 分支重新选出 → 其他 Worker claim 接管（attempt_count 再 +1）→ 重跑。前提是 ETL 幂等（先清后建）。若重试 3 次仍崩 → failOrRetry → 5/15 分钟退避 → 死信。同时文档可能被 RecoveryRunner 置过 FAILED，接管成功后会改回 READY。

**4. "幂等你说有三层，砍掉一层分别会坏在哪？"**
砍"先清后建"→ 重试后 chunk/向量新旧混杂；砍"先删后插"→ 同任务内重试在 chunk 表留重复行，检索重复命中；砍"确定性 UUID"→ 向量库堆多份同义向量、引用错乱。三层各挡一个时间尺度，缺一不可。

**5. "服务重启时那批卡死的文档，你的系统怎么自愈？"**
先纠正问题前提：**没有全自动自愈**。RecoveryRunner 无时间窗全量置 FAILED、不重入队，靠群组管理员手动 retry-ingestion（新幂等键、完整队列机制接管）；队列侧 Worker 租约到期自动接管未完成的任务。两条恢复路径重叠时文档状态可能 FAILED→READY 跳变。能说清"哪部分自动、哪部分人工、为什么这样分"才是真懂。
