# Argus-Loom Java 项目优化评估报告（只读篇）

> 报告日期：2026-09-19
> 评估对象：`F:\ChatGPT\argus-loom`
> 本次执行：只读分析，不修改任何业务代码；所有结论都基于源码阅读、离线构建与离线测试。
> 基线：`backend` 执行 `.\mvnw.cmd -o test`，共 `1402` 项测试全部通过（`Failures: 0, Errors: 0, Skipped: 0`）。
> 配套记忆文档：项目根目录 `MEMORY.md` 已另文维护，供后续会话快速了解整个项目。

---

## 1. 结论摘要

项目整体质量中上，属于“业务完整、分层意识良好、测试量大”的单模块 Spring Boot 应用：

- 后端为 Spring Boot 3.5 / Java 21 / MyBatis-Plus / PostgreSQL / Flyway / Spring Security + JWT / Spring AI / ES / MinIO / WebSocket 的单一 Maven 模块。
- 前端为 Vue 3 + TypeScript + UniverJS 0.25.x + STOMP/SockJS + SSE 的在线表格与 AI 助手界面。
- `controller → service → mapper` 依赖方向总体正确；Service 层 `@Transactional` 使用普遍；全局异常、统一响应、限流、日志脱敏、角色权限等横切能力已存在。
- 测试体量较大：全量离线测试 `1402` 个全部通过，说明现有业务行为有相对可靠的回归基线。

但仍有几个必须在动手前先讲清楚的风险点：

1. **P0-01 实时协作缺少文档级权限校验**：STOMP 的 `edit / join / leave / subscribe` 只做了 JWT 认证，未检查用户对该 Excel 文档是否有查看/编辑权限。任何已登录用户理论上都可以订阅他人文档频道并写入编辑消息。
2. **P0-02 Excel Delta 写入与合并缺少文档级互斥**：`batchUpdateCells / saveDeltasForChunk / compactSheetDeltas` 都是“读-改-写”，当前只有版本快照路径使用了 `ExcelDocumentLockService`，协同写入与自动合并可能并发覆盖。
3. **P1 层存在“宣称流式但未真正流式”“缓存无界但不被读取”“@Async/@Scheduled 无自定义线程池”“配置缩进错位导致默认值兜底”等一类问题**，不会破坏现有一次功能，但会放大后续资源与可观测性风险。

因此，本报告先给出问题清单与分阶段修复计划，暂不写代码。我们会保持小步、可验证、可回滚的节奏，等你确认后再按阶段实施。

## 2. 评估范围与方法

### 2.1 范围

- 后端：`backend/src/main/java` 全部源码、`backend/src/main/resources` 配置与 Flyway 迁移、`backend/pom.xml`、后端测试代码。
- 前端：`frontend/src` 的视图、store、API 封装、Univer 适配与协同客户端，用于理解后端契约和确认改动不会破坏前端调用。
- 工程文件：`MEMORY.md`、`backend/docs/*`、`logs/`、根目录辅助 SQL 与脚本。

### 2.2 方法

1. 先读 `pom.xml`、Flyway 迁移、核心配置和目录结构，形成模块地图。
2. 通读认证、权限、Controller、Service、Mapper、Entity/DTO/VO、异常、事务、日志、缓存、异步与 WebSocket 代码。
3. 查找明显坏味道：裸 `BusinessException("中文")`、空 catch、循环查库、`parallelStream`、无界 Map、公共线程池、配置占位符与代码读取不一致等。
4. 运行一次离线全量测试，确认当前基线的真实状态。
5. 按 P0（高影响/越权/丢数据）、P1（中影响）、P2（影响面较小但仍建议修）、P3（可维护性）排序输出问题与分阶段计划。

本报告不使用截图或视觉核对，所有结论都来自源码、配置文件和构建/测试输出这些文本证据。

## 3. 项目结构与分层现状

### 3.1 整体目录

```text
argus-loom/
├── backend/                       Java 后端（单模块 Maven）
│   ├── src/main/java/com/argus/rag/
│   │   ├── auth/                   JWT、登录、Token、Cookie
│   │   ├── user/                   用户资料、管理员
│   │   ├── group/                  群组、成员、邀请
│   │   ├── document/               文档上传、权限门槛、预览
│   │   ├── ingestion/              ETL：解析→切块→向量/ES
│   │   ├── qa/                     RAG 问答、混合检索
│   │   ├── assistant/             Agent、会话、记忆、工具
│   │   ├── excel/                  在线表、协同、版本、财务导入
│   │   ├── finance/                薪酬、考勤、报表
│   │   ├── metrics/                LLM 用量统计
│   │   ├── engine/                 MinIO/ES/PGVector/搜索适配
│   │   ├── prompt/                 提示词模板
│   │   ├── config/                 模型、AI、OpenAPI 配置
│   │   └── common/                 统一响应/异常/限流/日志/安全
│   ├── src/main/resources/db/migration/   Flyway（V1..V4 已存在）
│   └── pom.xml
├── frontend/                      Vue 3 + UniverJS 前端
├── sql/                          辅助 SQL（非 Flyway）
├── scripts/                       启动/冒烟脚本
├── logs/                          运行日志
└── MEMORY.md                      项目记忆文档
```

### 3.2 分层现状

- Controller 层：`RestController/Controller` 负责接收参数、鉴权、简单权限放行、包装 `ApiResponse`。
- Service 层：`@Service` 负责业务编排、`@Transactional` 事务、权限调用、状态机。
- Mapper 层：MyBatis-Plus 的 `BaseMapper` 与自定义 XML，基本只做数据访问。
- 数据模型：`entity` 表结构映射，`model/dto|vo` 传输对象，`record` 风格使用较多（约 144 处）。
- 横切：`GlobalExceptionHandler` 兜底、`ApiResponse` 统一返回、`RateLimitAspect` 限流、`LogAop` 日志脱敏、JWT 过滤器、WebSocket 认证。

总体判断：分层方向正确，Controller 内只做路由、解析和简单的 permission 前置判断，业务规则没有批量泄漏到 SQL；事务基本落在 Service 层。**主要问题不在“有没有分层”，而在“某些链路没有把既有分层能力用足”。**

### 3.3 前端简述

- Vue 3 + Vite + TypeScript + Pinia + Element Plus。
- 在线表格基于 UniverJS 0.25.x，前端通过 STOMP/SockJS 发 `edit/join/leave` 并订阅 `/topic/excel/{id}`。
- 后端 ROLE 判断在前端也做了一部分视觉性隐藏，但真正权限安全必须由后端保证；前端不承担最终权限判断。

前端本次不需要修改，后续如有接口调整（例如协同错误回执或权限拒绝消息），再同步小改前端。

---

## 4. 依赖关系与模块边界

### 4.1 Maven 依赖概况

- `spring-boot-starter-web`、`validation`、`actuator`、`aop`、`security`、`websocket`。
- MyBatis-Plus 3.5.15 + `jsqlparser`，Flyway 管理 `db/migration/V1..V4`。
- PostgreSQL、MinIO、Elasticsearch 8、Spring AI Alibaba 1.1.2、PGVector。
- Apache POI 5.5.1（工作簿/流式解析）、PDFBox、Knife4j/OpenAPI 文档。
- 测试：`spring-boot-starter-test`、`spring-security-test`、JUnit 5 与 mockito 风格单元测试。

### 4.2 依赖方向与循环

- 按包扫描和构造器注入审计，`controller → service → mapper/engine` 依赖方向正确，未发现 Service 与 Service 循环依赖。
- 事务 `@Transactional` 全部集中在 Service 层；Controller 未直接调用 Mapper。
- 领域包内 `entity / dto / vo` 划分基本清晰；少量 Controller 直接使用 Entity 返回数据（见 P2-01）。

### 4.3 依赖卫生候选

- `spring-boot-starter-webflux` 与 `spring-boot-starter-web` 同时存在。当前以 Web MVC 为主，若无 SSE/Reactive 硬需求，后续应评估去掉 webflux，避免双 Web 栈造成 bean 与类路径困惑。
- `fastjson 1.2.83` 仍在 Excel 模块大量使用，属于快停止维护的 1.x 主线。若保留，建议至少审计反序列化输入范围；更优是本地收敛到 Jackson 或 fastjson2。
- `commons-fileupload` 是否仍真实需要需要确认（Spring MVC 自带 multipart），如确认无实际调用点再移除，避免无谓依赖面。

## 5. 良性资产（本次不重复施工）

以下能力已经存在且质量合格，后续优化应以“复用、收口、补洞”为主，不要推倒重做：

- `common/exception/ErrorCodes` + `BusinessException` + `GlobalExceptionHandler`：已有统一响应和堆栈落日志、用户文案不透内部细节的基础。
- `ApiResponse<T>`（record，`NON_NULL`）：REST 返回契约统一。
- `common/ratelimit/*`：已有限流切面。
- `common/log/LogAop`：入参日志已做敏感字段脱敏与超长截断。
- `ExcelPermissionService..canView/canEdit/requireView/requireEdit`：REST 链路权限判断已实现，可以直接复用到协作/WS。
- `ExcelDocumentLockService.withLock/runWithLock`：文档级单实例锁已实现，只是协同写路径还没接入。
- `DateFormats`、record DTO、枚举常量不规范使用（例如财务记录解析）已大量统一过，不要为了“重建”而破坏稳定行为。

---

## 6. 问题清单与 P0 / P1 / P2 / P3 详细说明

### 6.1 问题总表

| ID | 优先级 | 维度 | 文件/位置 | 问题 | 建议 | 影响/风险 | 验证方式 |
|---|---|---|---|---|---|---|---|
| P0-01 | P0 | 安全/越权 | `excel/collab/CollabController.java:40-94`、`excel/config/WebSocketConfig.java:55-82` | STOMP 编辑/加入/订阅均未做文档级权限校验 | edit 前 `requireEdit`，join/leave 前 `requireView`，SUBSCRIBE 拦截器校验 `/topic/excel/{id}` | 未授权用户可读取他人/财务文档并写入 | WebSocket/STOMP 集成测试 + 全量回归 |
| P0-02 | P0 | 并发/数据一致性 | `excel/service/ExcelSheetService.java:261-371`、`excel/collab/CollabService.java:91-193` | Delta 写入与合并均为读-改-写，无文档级互斥 | 用 `withLock` 包住写路径与压缩；压缩放独立事务/异步 | 并发编辑丢单元格或丢失 delta | 并发单元测试 + 事务测试 |
| P1-01 | P1 | 性能/架构 | `excel/controller/ExcelFileController.java:63-90` | `isLargeFile` 只打日志，始终调用 `parseAndSave`，流式服务未被使用 | 抽 `ExcelUploadService` 并按大小选择解析器 | 大文件 OOM/请求阻塞 | 大文件上传测试 |
| P1-02 | P1 | 安全/输入校验 | `excel/controller/ExcelFileController.java:68-73` | 未校验扩展名、空文件、文件内容签名 | 白名单 `.xlsx/.xls`、空文件和 signature 校验 | 恶意文件进入解析链路 | 参数化测试 |
| P1-03 | P1 | 异常/错误码 | `finance 域多处`、`excel/controller/ExcelFileController.java:112` | 业务错误抛 `IllegalArgumentException/IllegalStateException` 被兜底成 500 | 统一 `BusinessException + ErrorCodes` | 用户提示与错误码不可用 | HTTP 状态码测试 |
| P1-04 | P1 | 并发/一致性 | `excel/service/FinanceImportService.java:365-405` | 指纹乐观锁为读-比-写，非原子 | 条件 `UPDATE ... WHERE fingerprint=expected` 或 `FOR UPDATE` | 财务数据可被并发覆盖 | 并发更新测试 |
| P1-05 | P1 | 资源/性能 | `excel/service/RedisChunkCacheService.java:27-78`、`ExcelSheetService.java:71` | 本地无界 Map，且 `get` 无调用方 | 有界+TTL 或真正接 Redis；无调用方则移除 | 内存无限增长、缓存空转 | 测试 + 内存断言 |
| P1-06 | P1 | 异步/资源 | `excel/service/ExcelExportService.java:82,155` | `runAsync` 用公共池，cancel 只改状态 | 自定义线程池 + 可取消任务 | 公共池饥饿、取消无效 | 取消/线程池测试 |
| P1-07 | P1 | 配置 | `application-dev.yml:84-112`、`qa/service/QaChatService.java:66` | `llm/excel/servlet` 缩进在 `elasticsearch` 下，代码默认值兜底 | 修正为 `argus.rag.*` 层级并加自检 | 超时/上传路径不生效 | 配置加载测试 |
| P2-01 | P2 | 分层边界 | 多个 Controller 直接返回 Entity | Entity 暴露到 REST 契约 | 增加 VO/record 投影 | 耦合与信息泄漏 | 代码审查 |
| P2-02 | P2 | 异步/资源 | `@Async` 多模块、`SnapshotService.java:34` | 无自定义线程池 | 统一 `ThreadPoolTaskExecutor` | 并发不可控 | 线程池测试 |
| P2-03 | P2 | 可观测性 | 全局日志 | 无 TraceId/MDC | 增加请求过滤器 | 跨模块排障难 | 日志断言 |
| P2-04 | P2 | 并发/资源 | `qa/rag/HybridChunkRetrievalService.java:256` | `parallelStream` 用 `ForkJoinPool.commonPool` | 专用线程池 | 公共池饥饿 | 压力测试 |
| P2-05 | P2 | 仓库卫生 | 约 19 个 `.bak*` 备份文件 | 备份文件在源码目录/根目录 | 移到备份目录或删除 | 误导、评审噪声 | 文本检查 |
| P2-06 | P2 | 安全/代码契约 | `common/security/InputSanitizer.java:45-69` | 注释声称替换/返回空，实际只记日志并原样返回 | 补实现或改名/改注释 | 假安全错觉 | 单测 |
| P2-07 | P2 | 性能 | `ExcelVersionService.java:114`、`ExcelExportService.java:202`、`TableIngestionService.java:65` | 循环内逐个查询 chunks | 批量聚合查询 | N+1 | 查询计数测试 |
| P2-08 | P2 | 安全 | `WebSocketConfig.java:50-52` | `setAllowedOriginPatterns("*")` | 收敛到环境配置 | 跨站 WS | 配置检查 |
| P2-09 | P2 | 异常语义 | `GlobalExceptionHandler.java:26-29` | 所有业务异常都 400 | 错误码映射 403/409/422 | 前端误判 | 接口测试 |
| P2-10 | P2 | 异步 | `ExcelExportService.java:64-84` | 导出任务仅内存 Future，重启丢失 | 状态机 + 恢复清扫 | 任务悬挂 | 重启模拟 |
| P2-11 | P2 | 依赖 | `pom.xml:56,236-251` | web+webflux、fastjson 1.x、commons-fileupload | 审计移除无用项 | 攻击面和类路径 | 编译/包检查 |
| P2-12 | P2 | 资源 | `StreamingParserService.java:34-60` | `OPCPackage` 未 close | try/finally 关闭 | 文件句柄泄漏 | 资源关闭测试 |
| P3-01 | P3 | 可维护性 | God class 多处（如 QA 检索 862 行） | 大类型难以演进 | 先行为锁后拆分 | 维护成本 | 行为锁测试 |
| P3-02 | P3 | 返回类型 | 多处手拼 `Map<String,Object>` | 弱类型契约 | 收敛 record/VO | 可读性 | 代码审查 |
| P3-03 | P3 | 维护 | 根目录 `sql/*.sql` 与 Flyway 并存 | schema 双份易漂移 | 统一 Flyway 唯一源 | 环境漂移 | 迁移比对 |
| P3-04 | P3 | 日期安全 | 少量 `SimpleDateFormat` 残留 | 线程不安全 | 统一 `DateTimeFormatter` | 日期解析偶发错乱 | 单测 |

### 6.2 P0 详述

#### P0-01 实时协作缺少文档级权限

**位置**

- `CollabController.handleEdit`（`:40-65`）只解析 userId 后直接调用 `collabService.handleEdit`，没有 `ExcelPermissionService.requireEdit(documentId)`。
- `handleJoin`（`:66-81`）、`handleLeave`（`:82-95`）同样没有 `requireView`。
- `CollabService.joinRoom`（`CollabService.java:149-165`）把 `room.setCanEdit(1)` 固定为可编辑，等于给所有进入房间的人都放了编辑权。
- `WebSocketConfig` 只做 CONNECT JWT 认证（`:66-93`），`configureMessageBroker` 把所有 `/topic/**` 订阅都交给简单 broker，不校验订阅目标文档（`:41-46`）。

**原因**

REST 链路已经有 `ExcelPermissionService.requireEdit/requireView`，但 STOMP 链路没有复用；“不同通道，同一权限”这条安全基线没有被严格执行。

**建议**

1. `handleEdit` 第一行先 `excelPermissionService.requireEdit(msg.documentId())`。
2. `handleJoin/handleLeave` 前先 `requireView(documentId)`。
3. 在 `configureClientInboundChannel` 加 SUBSCRIBE 拦截器：对 `/app/topic/excel/{id}` 或 `/topic/excel/{id}`，拿到 userId 后执行 `canView`；未通过则抛出 `AccessDeniedException`。
4. `CollabService.joinRoom` 的 `canEdit` 字段不再硬编码 `1`，由 `ExcelPermissionService` 的 `canEdit` 决定。
5. 给 `CollabMessage` 增加 `@Min/@NotNull` 等轻量校验。

**预计影响**

- 未授权用户：join/edit/subscribe 全被拒绝。
- 授权用户：现有 LWW 冲突解决和广播协议不变。

**验证方式**

新增 `CollabPermissionTest`：普通用户操作他人文档的 `edit` 消息应被拒绝；订阅自己不参与的 `/topic/excel/{id}` 应被拒绝；然后跑全量 `mvn -o test`。

#### P0-02 Excel Delta 写入与压缩缺少文档级锁

**位置**

- `ExcelSheetService.batchUpdateCells`（`:261-303`）先 `selectOne` 后 insert，`saveDeltasForChunk`（`:305-341`）先读取已存在的 delta 再决定 insert/update。
- `maybeCompactSheetDeltas`（`:343-349`）到 `compactSheetDeltas`（`:351-371`）是类内自调用，`@Transactional` 代理不生效；压缩过程读全部 delta → 合并到 chunk → “删除全部 delta”。
- `CollabService.persistEdit`（`CollabService.java:183-191`）直接调用 `sheetService.batchUpdateCells`，没有 `ExcelDocumentLockService.withLock`。

**根因**

同文档的两次保存请求会并发执行；`ReentrantLock` 已提供但没被这套路径接上，两个请求可能同时读到同一 delta 的旧值，或压缩期间删除另一个请求刚插入的新 delta。

**建议**

1. 把 `batchUpdateCells` 映射路径（REST 保存 / 协同 / 撤销重做）统一切换到 `documentLockService.withLock(documentId, ...)` 包装。
2. `compactSheetDeltas` 用独立事务执行，避免“长事务 + 全量 delta 读”占住 DB 连接；`maybeCompact` 只在锁内、事务外触发。
3. 为 delta 增加唯一键的条件 `UPDATE ... WHERE` 原子语义（当前已有唯一索引，可作为兜底）。
4. 文档注释标明：当前锁是单实例 JVM 锁，未来多实例部署时需替换为 PostgreSQL advisory lock 或 Redis。

**预计影响**

单文档写吞吐被串行化，但协同场景的写频率通常远低于文档数量；业务语义不变。

**验证方式**

并发向同文档同一个单元格发两个 update，跑 100 次随机顺序；断言两个请求的最终值都是其中一个请求的值，且不出现 500 或静默丢 delta。

### 6.3 P1 详述

#### P1-01 大文件上传“宣称流式”实际未走流式

**位置**

- `ExcelFileController` 定义 `LARGE_FILE_THRESHOLD = 50MB`（`:40`），并注入 `streamingParserService`（`:46`）。
- 上传实现固定调用 `parserService.parseAndSave(is, doc)`（`:90`），`streamingParserService` 没有任何调用点。

**原因**

流式开关只体现在日志与注释里，真实路径仍是全量 `ExcelParserService` 解析；50MB 以上文件会显著占用堆内存，与“自动切换 SAX 流式防 OOM”的接口说明不一致。

**建议**

1. 抽 `ExcelUploadService`，按大小和扩展名切换到普通解析或 `StreamingParserService`。
2. `StreamingParserService.parseStreaming` 当前只产出内存模型，仍需补齐 `sheet/chunk/delta` 落库链路，不能只简单替换入参。
3. 大文件建议异步 + 进度状态，避免占住 REST 请求线程。
4. `OPCPackage` 用 try/finally 或 try-with-resources 关闭。

**验证方式**

用 mock 大文件断言调用流式解析器；普通文件仍走原路径；再全量回归。

#### P1-02 上传原文件名、扩展名与路径校验缺失

**位置**：`ExcelFileController.upload`

- `savedName = UUID.randomUUID() + "_" + originalName`（`:69`）；
- `dir.resolve(savedName)`（`:72`）若 `originalName` 含 `..` 或路径分隔符，可能导致保存路径逃出 `uploadPath`；
- 未校验空文件、扩展名白名单、内容签名。

**建议**

- 用 `FilenameUtils.getName`/`cleanPath` 只保留文件名，再拼 `UUID + "." + 合法扩展名`。
- 白名单仅 `.xlsx`/`.xls`；空文件和错误扩展名直接返回 400。
- 对 `file.isEmpty()`、`file.getSize()` 为 0、`MaxUploadSizeExceeded` 两层限制做测试。

**验证方式**

参数化上传测试：`../evil.xlsx`、空文件、`.txt` 伪装文件均被拒绝且不落盘。

#### P1-03 业务异常仍落到 500 兜底

**位置**

- `FinanceImportService:373、377` 抛 `IllegalArgumentException`，`:381` 抛 `IllegalStateException`。
- `ExcelFileController.upload` 把 `e.getMessage()` 塞进 `BusinessException` 返回给用户（`:112`），可能泄露内部路径。
- `GlobalExceptionHandler` 没有专门处理这两类异常（`Exception.class` 兜底 `:111-116`）。

**建议**

1. 业务错误统一 `BusinessException(ErrorCodes.XXX, message)`。
2. 给 `IllegalArgumentException` 增加 handler：返回 400 + 通用文案，完整日志保留。
3. 上传失败改为“文件解析失败”固定文案，原始异常保留日志。

**验证方式**：接口测试断言不存在记录/参数错误返回 400 且 code 不为空，上传失败响应不含内部路径。

#### P1-04 财务指纹乐观锁非原子

**位置**：`FinanceImportService.batchUpdateRecords`（`365-405`）

- `selectById` → 比较指纹 → `UpdateWrapper` 更新，WHERE 中不包含 expected 指纹条件。
- 两个并发事务可同时通过比较并互相覆盖。

**建议**

1. 同事务内 `SELECT ... FOR UPDATE` 锁行后再校验（不改表）；
2. 或新增 Flyway 迁移记录 fingerprint 列并回填，然后 `UPDATE ... WHERE fingerprint=expected`，影响行数=0 视为冲突。

**验证方式**：并发线程测试同一旧指纹两个请求，只有一方成功。

#### P1-05 缓存是无界 Map 且读取路径从未使用

**位置**：`RedisChunkCacheService.java:32、44-62`，`ExcelSheetService.java:71、300`

- 本地 `localCache` 是无界 `ConcurrentHashMap`；
- 搜索全项目只有 `put` 和 `invalidateSheet` 调用，`get` 从未被读取；
- 缓存只增不减，类名与真实实现不符（暗示 Redis）。

**建议**

- 若保留缓存：让 chunk 查询真实使用 `get`，并加容量上限和 TTL 清理，或直接接 Redis；
- 若暂无 Redis 实现：先移除 `put/invalidate` 调用，避免无界假缓存。

#### P1-06 导出任务公共线程池 + 取消无效

**位置**：`ExcelExportService.createTask(:64-84)`、`cancelTask(:154-168)`

- `CompletableFuture.runAsync` 使用公共 ForkJoinPool；
- `cancelTask` 只更新 DB 状态，不打断运行中 Future；
- `executeTask` 完成后还会写 `SUCCESS`，可能覆盖 `CANCELLED`。

**建议**

- 自定义 `ThreadPoolExecutor`；显式记录 `Future`；
- `cancelTask` 时 `future.cancel(true)` 并保持状态一致；
- `executeTask` 每阶段检查“是否已取消”，发现取消直接返回；
- 增加失败重试/悬挂任务清扫。

#### P1-07 `application-dev.yml` 配置层级错位

**位置**

- `application-dev.yml`：`elasticsearch` 在 `:84`，而 `llm`（`:93`）、`excel`（`:108`）、`servlet`（`:112`）缩进 2 空格，实际挂在 `elasticsearch` 名下。
- 代码读取的是 `argus.rag.llm.timeout-seconds`（`QaChatService`）与 `argus.rag.excel.upload-path`（`ExcelFileController`）。

**影响**

开发配置里的 llm 超时、上传路径永远不生效，只能走默认值；“配置键对不上”这类问题最容易扩散。

**建议**

- 把 `llm/excel/servlet` 从 `elasticsearch` 名下移出，统一挂到 `argus.rag.*`；
- 后续收敛 `@Value` 为 `@ConfigurationProperties(prefix = "argus.rag")`；
- 增加“配置键已绑定”的启动自检测试。

### 6.4 P2 详述（要点）

| ID | 原因 | 建议优先级 | 注意点 |
|---|---|---|---|
| P2-01 | Controller 直接返回 Entity，契约随表结构变化 | 适配 VO/record，不改 REST 字段名时保持兼容 | 需要回归前端 |
| P2-02 | `@Async/@Scheduled` 使用 Spring 默认执行器和公共池 | 建 `ThreadPoolTaskExecutor`，队列+拒绝策略 | 防止任务无限排队 |
| P2-03 | 日志无请求级 TraceId | 加 `OncePerRequestFilter` 写 MDC，异常日志带上 | 不要打印长串 token |
| P2-04 | `parallelStream` 用 `ForkJoinPool.commonPool` | 换专用线程池 | 按小批量 QPS 评估 |
| P2-05 | `.bak*` 备份文件混在源码/根目录 | 移入 `docs/backup` 或确认删除 | 删除前先 diff |
| P2-06 | `InputSanitizer.sanitize` 只打日志不替换/不返回空 | 改实现或改注释/名字，不要“假安全” | 可能需同步调用方 |
| P2-07 | 循环查 sheet→chunks | 批量查询或 join | 先测真实 N+1 数量 |
| P2-08 | SockJS origins `*` | 环境配置 origins | 前端同源即可 |
| P2-09 | `BusinessException` 全部 400 | 按错误码映射 403/409/422 | 前端需配合 |
| P2-10 | 导出任务重启丢 Future | 状态机+恢复清扫 | 清理过期任务 |
| P2-11 | webflux/fastjson/commons-fileupload 待审 | 移除或升级 | 用 `mvn dependency:analyze` 辅助 |
| P2-12 | `StreamingParserService` 未关闭 OPCPackage | try-with-resources | 与 C 阶段一起做 |

> 这些不阻塞现有功能，建议在 P0/P1 稳定后再批量收口。

### 6.5 P3 详述（简要）

- **P3-01**：几个 500 行以上的大 Service 可以先提纯函数/行为锁，不强行拆类；拆分必须“先有测试、再动结构”。
- **P3-02**：手拼 `Map<String,Object>` 的内部返回建议收敛为 record/VO，对外接口保持字段名不变。
- **P3-03**：根目录 `sql/*.sql` 与 Flyway V1-V4 并存，后续 schema 改动统一走 Flyway。
- **P3-04**：残余 `SimpleDateFormat` 用 `DateTimeFormatter` 和 `DateFormats` 收敛。

---

## 7. 分阶段优化计划

### 7.1 总览

| 阶段 | 目标 | 涉及核心文件 | 步骤摘要 | 验证命令 | 回滚方式 |
|---|---|---|---|---|---|
| A | 实时协作权限（P0-01） | `CollabController`、`WebSocketConfig`、`CollabService`、`ExcelPermissionService`、测试 | 先写拒绝测试，再加 `requireEdit/requireView` 与 SUBSCRIBE 拦截，修正 `joinRoom` 的 canEdit | `mvn -o test` | 文件级备份 + reverse diff |
| B | 并发与数据一致性（P0-02、P1-04） | `ExcelSheetService`、`CollabService`、`ExcelExportService`、`FinanceImportService`、Flyway V6 | 先加并发红测；给 Delta 写加锁，压缩拆事务；财务改 FOR UPDATE 或条件 UPDATE | `mvn -o test` | 先回滚代码，再评估 DB 变更 |
| C | 上传与解析链路（P1-01、P1-02、P2-12） | `ExcelFileController`、新增 `ExcelUploadService`、`StreamingParserService` | 抽上传服务；扩展名/空文件/路径校验；大文件路由；OPCPackage 资源关闭 | `mvn -o test` | 回滚控制器与新增类 |
| D | 异常与错误码（P1-03） | `FinanceImportService`、`GlobalExceptionHandler`、`ExcelFileController` | 先列出现有裸异常清单；改 BusinessException；补 handler 和状态码测试 | `mvn -o test` | 逐个文件 revert |
| E | 异步与缓存资源（P1-05、P1-06、P2-02、P2-04） | `RedisChunkCacheService`、`ExcelExportService`、`AsyncConfig`、QA 检索 | 增加线程池；导出 Future 可取消；缓存无界改有界/TTL 或移除 | `mvn -o test` | 回滚线程池/缓存代码 |
| F | 配置与可观测性（P1-07、P2-03、P2-08） | `application-*.yml`、新 MDC Filter、`WebSocketConfig` | 修正缩进；加 TraceId filter；收敛 WebSocket origins | `mvn -o test` + 启动配置检查 | 配置文件恢复旧版本 |
| G | 边界与可维护性（P2/P3） | 多个 VO、`pom.xml`、`.bak*`、循环查询 | 收敛 Entity→VO、清理备份、依赖审计、SimpleDateFormat 替换 | `mvn -o test`、`rg` 检查 | 每次行为不变验证 |

### 7.2 阶段细节

#### 阶段 A：实时协同权限

- 前置：补 3 个测试分别覆盖 `edit/join/subscribe` 的权限拒绝路径；测试应真实走 STOMP 或至少 mock `SimpMessageHeaderAccessor`。
- 改动：
  1. `CollabController` 注入 `ExcelPermissionService`；
  2. `handleEdit` 首行 `requireEdit(msg.documentId())`，`handleJoin/handleLeave` 首行 `requireView(documentId)`；
  3. `WebSocketConfig` 增加 SUBSCRIBE 拦截器，拒绝无权限的 `/topic/excel/{id}`；
  4. `CollabService.joinRoom` 不再硬编码 `canEdit=1`，改为按 `canEdit` 写入。
- 验证：`.\mvnw.cmd -o test`；若失败先修测试语义，不要跳过。
- 风险：STOMP 拦截器可能影响已经正常的“尚未 join 先订阅”的客户端布局，若出现需与前端协作确认协议。

#### 阶段 B：并发与数据一致性

- 改动范围：`ExcelSheetService.batchUpdateCells/saveDeltasForChunk/compactSheetDeltas` 与 `FinanceImportService.batchUpdateRecords`。
- 步骤：
  1. 写并发红测：两个线程同时写同一个 cell 断言不漏值；两个事务同时压缩断言不丢 delta；
  2. 接入 `ExcelDocumentLockService.withLock`，并把压缩拆分到独立事务/锁外；
  3. 财务指纹改为 `FOR UPDATE` 或新增 Migration + 条件 UPDATE（二选一，不引新依赖）；
  4. 跑全量。
- 风险与回滚：若加列（V6/Flyway），先备份该表数据；回滚顺序：先回退代码 → 再视情况撤销迁移。

#### 阶段 C：上传链路
1. 新增 `ExcelUploadService`：校验、存盘、选路由；
2. 普通文件继续 `ExcelParserService`；>50MB 走 `StreamingParserService`；非白名单扩展名直接拒绝；
3. `StreamingParserService` 补全持久化路径，并用 try-with-resources；
4. 控制器只保留“接收 request → 调用 service → 返回 VO”。
- 验证：mock 大小、mock 文件名、错误路径测试 + 简洁回归。

#### 阶段 D：异常与错误码
- 清理 `IllegalArgumentException/IllegalStateException` 到 `BusinessException`；
- `GlobalExceptionHandler` 保留原生异常兜底日志；
- 上传失败和财务冲突的响应文案单测。
- 注意：不要把已有 `BusinessException("中文")` 全部一次性改掉，按模块分组。

#### 阶段 E：异步与缓存
- 增加 `AppThreadPoolConfig`，`@Async/@Scheduled` 绑定；
- `ExcelExportService` 用自定义线程池 + `Future` 句柄 + 取消状态检查；
- 缓存：先用最保守方案（只移除假缓存写入 或 加容量/TTL），再考虑 Redis；
- `parallelStream` 替换成专用线程池。

#### 阶段 F：配置与可观测
- 把 dev yml 层级修复；
- 新增 `MDC` 请求过滤器（traceId=UUID，线程名/上下文）；不打印 JWT/密码；
- 固定 `WebSocketConfig` allowed origins 值到配置。

#### 阶段 G：边界与可维护（可选）
- VO 收敛、`.bak` 清理、依赖审计、SQL 迁移、SimpleDateFormat；每小步独立验证。

---

## 8. 验证策略与测试基线

### 8.1 当前基线

```powershell
cd "F:\ChatGPT\argus-loom\backend"
$env:JAVA_HOME = "C:\Users\yuanchentai\.jdks\corretto-21.0.11"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -o test
```

基线：`Tests run: 1402, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，日志见 `logs\mvn-test-baseline.log`。

### 8.2 每个阶段的验收

- 跑全量 `mvn -o test`，必须全绿。
- 新增 P0/P1 测试必须真实执行并覆盖“拒绝/冲突/取消”等行为。
- `rg` 复查：未新增空 catch、硬编码可执行字符串、把用户输入拼回 SQL 等。

### 8.3 行为不变红线

- 不改 REST URL 与对外 DTO/VO 字段名。
- 表结构变更一律走 Flyway，先验证回填。
- 不删测试、不跳过测试；不引不必要新依赖。

## 9. 本次执行结果（只读）

- 业务代码：未改动。
- 测试基线：1402 通过，0 失败。
- 本报告：唯一的“本次新增”文档；`MEMORY.md` 为既有记忆文档。
- 待办集合：P0(P0-01/P0-02)、P1(P1-01..P1-07)、P2(12 项)、P3(4 项)。

## 10. 后续实施建议

- 阶段 A（越权）与阶段 B（丢数据）属于安全与一致性红线，优先执行。
- 阶段 C（大文件）与 D（错误码）能提升真实用户的健壮性。
- 阶段 E（线程池/缓存）与 F（配置/可观测）建议在 A-D 之后做，避免并发改动跨模块。
- 阶段 G（VO/依赖/卫生）作为长期收尾，以“小步 + 回归”推进。

> 请确认后回复“开始阶段 A”；我将按报告第 7 节逐阶段输出：改动文件、关键 diff、测试结果、风险与回滚方式。
