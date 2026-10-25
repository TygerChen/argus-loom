# 优化评估报告 · 分阶段执行台账（optimization-assessment-report.md 配套）

> 本文件是 `docs/optimization-assessment-report.md`（2026-09-19 只读评估）的**执行台账**：
> 记录每个阶段做了什么、验证结论、以及“评估后决定不做”的项与理由。
> 后续会话（含上下文压缩后的续跑）请**先读本文件**再动手，避免重复施工或回退已完成的修复。
> 全量测试权威命令：
> ```powershell
> cd F:\ChatGPT\argus-loom\backend
> $env:JAVA_HOME='C:\Users\yuanchentai\.jdks\corretto-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
> .\mvnw.cmd -o test
> ```

> 全量回归（2026-09-20）：`logs\mvn-test-final.log` → **Tests run: 1500, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**。
> 基线 1454（stageB 日志）→ 1500，净增 46 条新增/改写测试（GlobalExceptionHandler +2、ExcelUploadService +1、
> ExcelExportService 13→20、RedisChunkCacheService 10→14、ExcelSheetService +1、HybridChunkRetrievalWebSearch +5、
> ApplicationConfigBinding +5、WebSocketConfigOrigins +3、TraceIdFilter +4、InputSanitizer +3，含部分改写不计增删）。
> 红线校验：REST URL/对外 DTO 未变；无新增空 catch；无用户输入拼 SQL；WebSocket 不再 `*`；财务域裸异常清零。
>
> 后续微调（2026-09-20）：无状态 helper 方法 static 化（零行为漂移）——
> `FinanceImportService.setAmount/setDate`、`ExcelExportService.safeSheetName/writeCell`、
> `ElasticsearchChunkIndexService.buildKeywordShouldClauses/buildKeywordRescoreShouldClauses`、
> `HybridChunkRetrievalService.shouldPreferDocumentCoverage/indexRows`、
> `AssistantConversationService.normalizeOptionalText/normalizeLimit/normalizeGroupId/requireContent/firstGroupId/requireUserId/requireSessionId/requireCreateDTO/resolveGroupIds`、
> `QaChatService.extractUsageInfo`、`AssistantService.estimateTokens/requireChatRequest/firstGroupId`、
> `ExcelVersionService.clampSummary/parseObject/parseArray`、
> `PayrollService.sumInt/cut/requireUserId`、
> `EvidenceOverviewAssembler.buildWarnings/summarize/readLong/readText/readTextOrDefault/readFileName/readInteger/readScore/sanitizeFileName`、
> `FreeWebSearchService.stripHtml/encode`、
> `DocumentQueryService.normalizeGroupRelation/normalizeStatus`、
> `DocumentAccessService.normalizeRole/parseLong/aclView`、
> `LogAop.cap`、`QaRecordPersistenceService.snippet`、
> `VectorIngestionService.normalizeBatchSize/readLegacyCompatibleFileName`、
> `FinanceReportPdfService.pad`、`AssistantKnowledgeBaseTool.requireQuery/toEvidence/readLong/readInteger/readScore/readText`、
> `EtlDocumentIngestionProcessor.truncatePreviewText`、
> `GroupManagementService.requireGroupName/normalizeDescription/requirePositiveId/requireInvitee/requirePending`、
> `AssistantMemorySummarizer.normalize/defaultText`、
> `GroupJoinRequestService.requireGroupCode/requireSameGroup/requirePending/hasRows/requirePositiveId`、
> `GroupMembershipService.requireGroupId/requireUserId/hasRows/toVisibleGroup/toPendingInvitationItem`、
> `QuestionQueryRewriteService.requireQuestion/simplifyQuestion/splitQuestion`、
> `QueryPlanningService.buildRewriteQueries/normalizeQueries/limitQueries/requireQuestion`、
> `AssistantShortTermMemoryHook.formatToolMessage/formatRecentMessage/hasNonBlankToolResponse/extractCurrentQuestion/summarizeMessages`、
> `AssistantSessionService.requireSessionId/normalizeTitle/generateSessionTitle`、
> `AdminUserService.requireUserId`、
> `DocumentUploadService.requireGroupId/requireValidFile/extractDirectFileName/calculateSha256`、
> `MemoryContextBuilder.formatMemoryLine/translateSource`、
> `MemoryExtractionListener.extractPreferenceContent`、
> `MemoryRetriever.tokenize`、
> `AssistantPromptContextBuilder.fallbackBasePrompt`。
> 全量复测 1500 绿。

## 阶段状态总览

| 阶段 | 目标 | 状态 | 证据 |
|---|---|---|---|
| A | 实时协作权限（P0-01） | ✅ 完成 | `ExcelSubscribeInterceptor`、`StompSessionAuth`、`CollabController` 权限前置、`joinRoom(canEdit)`；日志 `logs\mvn-test-stageA.log` |
| B | 并发与一致性（P0-02、P1-04） | ✅ 完成 | `ExcelSheetService.batchUpdateCells` 走 `lockService.runWithLock`；`FinanceLedgerRecordMapper.selectByIdForUpdate` FOR UPDATE；日志 `logs\mvn-test-stageB.log`（Tests run: 1454，全绿） |
| C | 上传链路（P1-01、P1-02、P2-12） | ✅ 完成 | 新增 `ExcelUploadService`（校验/白名单/路径安全/≤50MB 普通 &gt;50MB 流式/失败清理）；`ExcelFileController` 退化为薄壳；`StreamingParserService` OPCPackage try-with-resources |
| D | 异常与错误码（P1-03） | ✅ 完成 | `GlobalExceptionHandler` 新增 `IllegalArgumentException`→400；`FinanceImportService` 7 处裸异常→`BusinessException`（并发冲突带 `CONCURRENT_MODIFIED`）；`ExcelUploadService` 固定用户文案（不透传内部 message，根因进日志，业务异常不再被降级）。聚焦验证：`mvn-test-stageD-focus.log`（32 绿，新增 `GlobalExceptionHandlerTest` 2 条 + `ExcelUploadServiceTest` 业务异常不降级 1 条） |
| E | 异步与缓存资源（P1-05、P1-06、P2-02、P2-04、P2-10、P2-07） | ✅ 完成 | 新增 `AppThreadPoolConfig`（taskExecutor/excelExportExecutor/retrievalExecutor/taskScheduler）；`ExcelExportService` 专用池 + Future 真取消 + 取消不被 SUCCESS 覆盖 + 悬挂清扫；`RedisChunkCacheService` 重写为有界 LRU + TTL + 命中统计，`ExcelSheetService.listChunksBySheetId` 真实启用读缓存（写路径全量显式失效）；`HybridChunkRetrievalService` parallelStream→retrievalExecutor；新增 `listChunksByDocumentGrouped` 批量读并接入 版本快照/导出/表格摄取（N+1 消除）。聚焦验证：`mvn-test-stageE-focus2/3.log`（116 + 85 绿；新增 `HybridChunkRetrievalWebSearchTest` 5 条、缓存契约重写为 14 条、导出 20 条） |
| F | 配置与可观测（P1-07、P2-03、P2-08） | ✅ 完成 | 三个 profile 的 `llm`/`excel`/`servlet` 全部归位到代码读取的键路径（dev 曾挂在 `elasticsearch` 下、local/prod 挂在根层级，均从未生效）；新增 `common/log/TraceIdFilter`（MDC traceId + 响应头回写 + finally 清理，logback 三条 pattern 加 `[%X{traceId}]`）；`WebSocketConfig` 来源配置化 `argus.rag.websocket.allowed-origin-patterns`（不再 `*`）。聚焦验证：`mvn-test-stageF-focus.log`（12 绿；新增 `ApplicationConfigBindingTest` 5 条、`WebSocketConfigOriginsTest` 3 条、`TraceIdFilterTest` 4 条） |
| G | 卫生与边界（P2-05、P2-06、P2-07、P2-11、P3-04） | ✅ 完成 | 23 个 `.bak*` 备份集中到 `docs/backup/`（含 README）；`InputSanitizer` 文档与实现对齐 + 新增真实无害化 `neutralize`；N+1 批量读（见阶段 E）；`ExcelParserService` 残余 `SimpleDateFormat` → `DateFormats.DATE`；依赖审计结论见下节 |

## 阶段 D · 异常与错误码（P1-03）

- `GlobalExceptionHandler` 新增 `IllegalArgumentException` → 400（通用文案，堆栈进日志），避免残留裸参数异常落 500 兜底。
- `FinanceImportService` 7 处裸异常 → `BusinessException`（并发冲突带 `ErrorCodes.CONCURRENT_MODIFIED`，不存在类带 `EXCEL_NOT_FOUND`）。
- `ExcelUploadService` 上传失败不再把内部异常 message 回抛给用户（固定文案 + 原始异常进日志）。
- 测试：`GlobalExceptionHandlerTest`（新增）、`FinanceImportServiceTest`、`ExcelUploadServiceTest`（断言收紧：不泄露内部细节）。

## 阶段 E · 异步与缓存资源

- 新增 `common/config/AppThreadPoolConfig`：`taskExecutor`（@Async 默认，core4/max8/queue200/CallerRuns）、`excelExportExecutor`（导出专用 core2/max4/queue100/CallerRuns）、`retrievalExecutor`（检索并行 core4/max8/queue64）、`taskScheduler`（@Scheduled 专用 pool2）。
- `ExcelExportService`：投递到 `excelExportExecutor` 并持有 `Future`；`cancelTask` 真正 `future.cancel(true)`；`executeTask` 在写入 SUCCESS 前复查 DB 状态，取消不再被覆盖；新增 `@Scheduled` 悬挂任务清扫（P2-10）。
- `RedisChunkCacheService`：无界 `ConcurrentHashMap` → 有界 + TTL + 命中统计；新增 sheet 级 `getSheetChunks/putSheetChunks` 与 `invalidateDocument`，并在 `ExcelSheetService` 真实读路径启用（读缓存命中免 DB 查询）。
- `HybridChunkRetrievalService`：`parallelStream`（ForkJoinPool.commonPool）→ `retrievalExecutor`，未注入/被拒绝时退化为顺序执行（P2-04）。

## 阶段 F · 配置与可观测

- `application-dev.yml`：`llm` / `excel` / `servlet` 缩进错位（挂在 `elasticsearch` 下）修正到 `argus.rag.llm` / `argus.rag.excel` / `spring.servlet.multipart`。
- 新增 `common/log/TraceIdFilter`：MDC `traceId`（入站 `X-Trace-Id` 仅接受安全字符，否则生成 UUID）+ 响应头回写 + finally 清理；logback 三条 pattern 增加 `[%X{traceId}]`。
- `WebSocketConfig`：`setAllowedOriginPatterns("*")` → 配置项 `argus.rag.websocket.allowed-origin-patterns`（默认仅本机 8081/10001；LAN/域名经环境变量覆盖）。
- 新增 `ApplicationConfigBindingTest`：直接加载 `application-dev.yml` 断言关键键路径已绑定（配置键错位会红）。

## 阶段 G · 卫生与边界（已完成项）

- P2-05：`*.bak*` 备份文件从源码树移到 `backend/docs/backup/`。
- P2-06：`InputSanitizer` 文档与实现对齐 + 新增真实“无害化替换”能力（`neutralize`），不再“假安全”。
- P2-07：`ExcelSheetService` 新增按文档批量取分块（`listChunksByDocumentGrouped`），`ExcelVersionService` / `ExcelExportService` / `TableIngestionService` 去掉“每 sheet 查一次”的 N+1。
- P3-04：`ExcelParserService` 残余 `SimpleDateFormat` → `DateFormats.DATE`。
- P2-11：依赖审计结论见下节“评估后不改”。

## 评估后不改（含理由，避免后续重复施工）

| ID | 项 | 结论与理由 |
|---|---|---|
| P2-01 | Controller 直接返回 Entity → VO | 暂不改：实体字段与 REST 契约当前一一对应，投影 VO 属零收益高风险（Jackson null 字段包含策略变化会改响应体，触碰 8.3“不改对外 DTO/VO”红线）。待有真实字段收敛需求时再做。 |
| P2-09 | BusinessException 按错误码映射 403/409/422 | 暂不改：实测多数业务异常路径（`FinanceImportController` 等）已自行 catch 并返回 HTTP 200 + `success:false`，改映射只影响少量直抛路径而需前端整体回归；留待前端契约评审后单独推进。 |
| P2-11 | webflux / fastjson 1.x / commons-fileupload | webflux **必须保留**（Spring AI OpenAI 走 WebClient，移除即断）；commons-fileupload 在离线栈下不做移除实验（历史结论见 `optimization-report.md` Phase 6 子步 2）；fastjson 1.x → fastjson2/Jackson 属跨模块大改，另立专项。 |
| P3-01 | god class 拆分（QA 检索 / ExcelSheetService） | 维持 `optimization-report.md` 既有结论：先行为锁、不硬拆。 |
| P3-02/P3-03 | Map 返回收敛 / 根目录 sql 与 Flyway 并存 | 内部投影与辅助 SQL 不影响运行；如需推进，走“先锁行为再加类型”的独立小步。 |
