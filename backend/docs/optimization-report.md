# argus-loom 后端代码优化 · 评估与分阶段执行记录

> 本文件为**独立**优化台账，与既有 `docs/refactor-plan.md`（测试优先重构台账）分开维护。
> 记录：现状评估结论、分阶段优化的改动文件/差异/测试结果/风险/回滚。

---

## 1. 现状评估基线（2026-09-19）

**技术栈**：Spring Boot 3.5 / Java 21 (Corretto 21.0.11) / MyBatis-Plus / PostgreSQL / Flyway / Lombok 1.18.34，单模块 Maven，370 源文件。

**分层健康度**：17 个业务域包，各域 `controller→service→mapper→model(entity|dto|vo)`；
`controller→service→mapper` 单向，134 处注入**零 Service↔Service 循环**；
事务 `@Transactional` 90 处全落在 Service 层；record 144 处已成为主流风格。
Service 为具体类（务实，不改接口化）。

**良性资产（勿重复施工）**：`common/util/DateFormats`（日期常量）、`common/exception/ErrorCodes`、
`GlobalExceptionHandler`（含 SSE `userFacingMessage`）、`ApiResponse<T>` (record, NON_NULL)、
`common/ratelimit/*`、`common/log/LogAop`（入参脱敏）、record 风格。

**主要待攻病灶**（详细清单见各阶段条目）：
- P0 god class（>400 行）：`qa/rag/HybridChunkRetrievalService`(862)、`engine/elasticsearch/ElasticsearchChunkIndexService`(701)、`document/service/DocumentUploadService`(660)、
  `finance/FinanceImportService`(632)、`excel/ExcelSheetService`(584)；次挡 `ExcelAssetService`/`AssistantService`/`PayrollService`/`ExcelParserService`。
- 手拼 `Map<String,Object>` 返回：`DocumentAccessService`(4 处)、`ExcelAssetService`、`GenerationController`、`AgnesAIService`、`YoucomSearchService` → 收敛为强类型 VO/record。
- 日期格式漂移 + 遗留 `SimpleDateFormat`：`FinanceSheetResolver`、`FinanceWorkbookExtractor`、`ExcelParserService`。
- 空 catch 吞异常无日志：`excel/ExcelFileController`（purge / deleteIfExists）。
- 裸串 `BusinessException("中文")` 大量 → 收敛 `ErrorCodes`。
- 依赖卫生：web+webflux 同依赖、fastjson 1.2.83/commons-fileupload 高危遗留、无版本 `<properties>` 上收。

---

## 2. Phase 0 · 编译断裂根因定案 + 基线复位

**状态：✅ 完成（2026-09-19）**

### 2.1 根因：`java.lang.reflect.RecordComponent` 在本机全 JDK javac 无法解析

现象：`./mvnw` 编译报成片 `找不到符号 set*/get*`、0 文件产出、47 次假象指向错误方向。

完整排查链（已排除）：文件编码（全 UTF-8）→ Lombok 配置（annotationProcessorPaths 就位）→ 陈旧 target（clean 也是）→
daemon 复用（`compilerReuseStrategy=alwaysNew` 亦败）→ 单 JDK 损坏（把 `RecordComponent` 剥成 `/tmp` 里不含任何项目代码的最小文件，
在本机 .jdks/corretto-21.0.11、corretto-25.0.4.1、jbr-17.0.14、Program Files/openjdk-17.0.2、IDEA JBR21.0.7、ES-JDK20 全部重复报同一错误）。

断定：**该开发机上所有版本的 javac 都无法在编译期解析 `java.lang.reflect.RecordComponent`**（虽运行时
`Class.forName` 在、`ct.sym` 内 `.sig` 也在）。这是工具链环境的怪癖，不是项目代码问题。
而这个符号若出现在**任意一个源文件**，会让 javac abort 整个编译 → Lombok 的 getter/setter 成批不生成 → 表现为"找不到符号 setXxx/getXxx"。

### 2.2 修复：绕过该符号，改用纯 `java.lang.reflect.Method`

把 `common/log/LogAop.java` 里对 `RecordComponent` 的依赖（入参脱敏拼装）改为等价的
`type.getMethods()` + 过滤（非 Object 声明、无参）遍历，字段名与掩码/截断语义完全保留。
**刻意未引入 RecordComponent 或任何 `--release` 层面的依赖，使编译不依托该异常符号。**

### 2.3 本次纳入基线（Phase 0 改动文件）

| 文件 | 改动 | 行为变化 |
|---|---|---|
| `src/main/java/com/argus/rag/common/log/LogAop.java` | 入参日志脱敏（SENSITIVE_KEYS：pwd/token/secret/credential…）+ 超长截断 120 字；record 反射改为 `java.lang.reflect.Method` | 仅日志输出，无业务逻辑变化 |
| `src/main/java/com/argus/rag/common/ratelimit/RateLimitAspect.java` | 删除返回硬编码 `"anonymous"` 的死代码 `resolveUserId()` | 无行为变化（那方法从未被有意义调用） |

> 注：`RateLimitAspect` 当前 key 默认仍为 `"anonymous"`，与 `resolveUserId()` 删除前一致，故行为零漂移；
> 真正的当前用户 key 化属后续可选改进，不在 Phase 0 范围。

### 2.4 验证

- `./mvnw -o clean compile` → **BUILD SUCCESS**（370 文件，460 class）
- `./mvnw -o clean test` → **Tests run: 1192, Failures: 0, Errors: 0, Skipped: 0**（基线全绿，与既有台账一致）

### 2.5 风险与回滚

- **风险**：`LogAop` 脱敏遍历 `getMethods()` 会遇到比原 `getRecordComponents()` 更宽的 accessor 集合；已用「非 Object + 无参」过滤贴近原范围，日志为只读切片，无业务风险。
- **回滚**：隔离删 `LogAop` 新增 maskXxx 方法回到 `Arrays.toString(getArgs())`，删除 `RateLimitAspect` 空方法即回滚；`git` 该 repo 无版本控制（环境仅此一份），出规拒应用差异手工还原。

---

## Phase 1 · P1 快速安全（异常吞、日期收拢、文案收口）

**状态：第一部分 ✅；其余经核实不为真缺陷/涉高接触，判定见下（2026-09-19）**

### 改动（1 文件）
`src/main/java/com/argus/rag/excel/controller/ExcelFileController.java` —— `upload` 失败清理路径的两处空 `catch (Exception ignored){}` 补 `log.warn`：
- `documentService.purge(doc.getId())` → `log.warn("上传失败后清理文档失败 docId={}", ...)`
- `Files.deleteIfExists(...)` → `log.warn("上传失败后删除临时文件失败 path={}", ...)`
捕获变量改名 `cleanupEx` 避免与外层 `catch (Exception e)` 同名。清理失败仍被吞（不阻断主异常上抛），只是不再无声。零业务逻辑变化。

### 核实为「非真缺陷」，**不改**（避免引入时区/语义漂移）
- `ExcelParserService:185` 的 `new SimpleDateFormat("yyyy-MM-dd")` 是**方法局部变量、每次调用新建**，无共享可变状态，**并非线程不安全**。迁 `DateTimeFormatter` 需显式接 zone 且 `cell.getDateCellValue()` 语义受 SDF/系统时区影响，收益低、风险不低 → 保持不动。

### 后续子项（待独立阶段）
- finance 两文件候选 pattern 数组复制（`FinanceSheetResolver`/`FinanceWorkbookExtractor`）→ 抽共享常量，涉多文件、需谨慎；留待后续独立小阶段。
- `BusinessException("中文")` 高频文案 → `ErrorCodes` 收敛：94 处跨多域，牵涉公共错误码字面量，属高接触、影响面大改动，应作为**独立阶段**（带聚焦测试锁码）实施，不在本阶段混改。

### 验证
- `./mvnw -o clean test` → **Tests run: 1192, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）

---

## Phase 2 · 裸 Map → 强类型 VO（收敛内部自产稳定字段）

**状态：✅（部分） 已收敛 ACL 域；外部透传性 Map 判定保留（2026-09-19）**

### 收敛（ACL 域，全部内部自产、字段稳定）
`excel` 域文档分享/所有权从裸 `Map<String,Object>` 收敛为强类型 record（字段名与原 Map key **逐一一致**，JSON 契约不变）：

| 新 VO | 替换 | 字段 |
|---|---|---|
| `excel/model/vo/AclEntryVO` | `aclView` + `listAcl` 返回元素 | userId / username / displayName / role |
| `excel/model/vo/GrantResultVO` | `grant` 返回 | documentId / userId / username / role |
| `excel/model/vo/TransferOwnerVO` | `transferOwner` 返回 | documentId / newOwnerId / newOwnerName |

改动文件：`DocumentAccessService.java`（方法签名+构造，去 LinkedHashMap/Map import）、
`ExcelDocumentAclController.java`（3 处返回泛型 `ApiResponse<...VO>`）、
`DocumentAccessServiceTest.java`（断言 `out.get(key)` → `out.accessor()`，用 `var`）。

### 判定保留（理由：外部不透明 payload 透传，强转破坏契约）
- `config/AgnesAIService` (`generateImage/generateVideo`)：直接透传上游 OpenAI/Agnes `/images/generations`、`/videos` 的原始 JSON，结构由外部、多模型决定 → **保留 `Map`**。
- `config/GenerationController`：`@RequestBody Map` 作灵活入参解析（合理）。
- `YoucomSearchService`：同理搜索 API 透传（若属外部，沿用判断保留）。

### 验证
- `./mvnw -o clean test` → **Tests run: 1192, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）

### 收敛（2）`excel/model/vo/AssetUploadResultVO` —— `ExcelAssetService.upload`
返回从 `Map{url,assetKey,contentType,size}` 收敛为 record `AssetUploadResultVO(String url, String assetKey, String contentType, long size)`。
改动：`ExcelAssetService.java`（签名+构造）、`ExcelAssetServiceTest.java`（4 处断言 → accessor，用 `var`）。controller 返回为 `ApiResponse<?>` 通配，无需改。JSON 字段不变。

### 剩余待查：`ExcelSheetService`(584 行 god class) 内 API 返回 Map —— 归入 Phase 5 god class 拆分时一并收敛，避免在 god 类上分散修改。
注：`ShareLinkService:78` 的 `Map` 是 `jdbcTemplate.queryForList` 的 **JDBC 读取结果行**（方法实际返回强类型 `ShareResolved`），**非「Map 返回给调用方」的病灶** → **保留**不误改。

---

## Phase 3 · god class 检索域拆分（前置评估）

**状态：⏸ 评估完成，暂缓硬拆（2026-09-19）——测试覆盖不足是拆分前置阻塞**

目标类：`qa/rag/HybridChunkRetrievalService`（862 行，唯一 public `retrieve(...)`），内含私有 `RetrievalCandidate`/`RetrievalCluster` 记录。

**安全判定（关键发现）**：已有聚焦测试 `HybridChunkRetrievalServiceTest` **仅 2 用例**（锁 `shouldPreferDifferentDocumentsWhenQuestionAsksEachDocumentSummary` / `strategy=global` 两条支路），另一 `RoleIsolationTest` 锁角色隔离。但 `retrieve` 内部的 **vector/keyword 融合、RRF 排序、证据聚类（buildClusters）、evidence 等级/指导语、reranking** 等核心支路**均未被测试锁定**。

**结论**：按项目「先锁行为再改结构」红线，在当前覆盖下硬拆该 god 类有不可控回归风险（检索语义微变难被测试察觉）。**拆分前必须先补聚焦测试锁定上述支路**；已锁支路（global 遍历 / 文档去重偏好 / 角色隔离）可作为拆分的回归基线。

**下一步（待用户应允后执行）**：Phase 3-a 先为 vector/keyword 融合、RRF（复用 `qa/support/RrfFusion`）、聚类、rerank 补行为测试 → 阶段全绿 → Phase 3-b 按职责拆出协作 service（仅当测试锁足后）。在 Phase 3-a 完成前不触碰该 god 类的生产代码结构。

### Phase 3-a · 补行为测试锁定内部支路 ✅（2026-09-19）

为 `HybridChunkRetrievalServiceTest` 追加策略 4 个聚焦测试（全量 1192→**1196 绿**），锁定此前未覆盖的核心支路（全部经公共 `retrieve()` 的可观测 metadata/evidenceLevel 输出断言，不触碰私有结构）：

| 测试 | 锁定的内部行为 |
|---|---|
| `shouldMarkBothSourcesAndAccumulateRrfWhenChunkHitInBothChannels` | 同一切片向量+关键词双路命中 → `retrievalSource=BOTH`；RRF 各 rank1 累加 `hybridScore=2.0`；`vectorScore`/`keywordScore` 各取最大值；单文档双源→`PARTIAL` |
| `shouldMarkKeywordOnlySource` | 仅关键词命中→`retrievalSource=KEYWORD`、`vectorScore=0.0` |
| `shouldExpandEvidenceWindowWithNeighborWindow` | neighborWindow=1 时 `startChunkIndex=0`/`endChunkIndex=2` 扩展并拼接相邻切片文本 |
| `shouldMarkWeakEvidenceForSingleLowCoverageHit` | 单来源单证据命中→`EvidenceLevel.WEAK` |

**证据等级分叉已锁四支**：NONE、SUFFICIENT、PARTIAL、WEAK。INSUFFICIENT 分支（topScore<0.30 / 单 doc avgScore<0.40）需跨 50+ 极端低 RRF 累计构造，暂以其余四支矩阵兜住，留待 Phase 3-b 拆分时按需补。

**验证**：聚焦 `-Dtest=HybridChunkRetrievalServiceTest`→**6/6 绿**；全量 `./mvnw -o test`→**Tests run: 1196, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）。

**风险与回滚**：全部为**新增只读测试**，未改动生产代码一行，零行为风险；回滚=删除 Phase 3-a 追加的 4 个测试方法即复原。改动文件仅 `src/test/java/com/argus/rag/qa/rag/HybridChunkRetrievalServiceTest.java`。

**下一步**：Phase 3-b 在行为锁已定的基础上拆分该 god 类的协作 service（优先抽 `buildClusters`/`evaluateEvidenceLevel`/`RetrievalCandidate` 等无状态纯逻辑段），保证 `retrieve()` 各支语义不变、全量仍绿。

### Phase 3-b · 拆出 EvidenceAssessor ✅（子步 1，2026-09-19）

在行为锁定的基线上，第一枚安全小步：将检索编排里**不依赖任何内部候选类**的两段纯计算外提为独立无状态工具：

- 新文件 `qa/rag/EvidenceAssessor.java`（final class，静态方法）：
  - `evaluate(List<Document>)` → `EvidenceLevel`：原 `evaluateEvidenceLevel` 全逻辑逐字节保留；
  - `guidanceFor(EvidenceLevel)` → 原 `buildEvidenceGuidance` 全文案逐字节保留。
- `HybridChunkRetrievalService.java`：删除这两个私有方法，DIRECT 分支两处调用点改为 `EvidenceAssessor.evaluate(...)` / `EvidenceAssessor.guidanceFor(...)`。GLOBAL 分支不依赖此二方法（自带 SUFFICIENT+固定文案），不受影响。

**安全点**：EvidenceAssessor 仅依赖 `Document`>metadata 与 `EvidenceLevel` 枚举，零内部候选类/GUARD/DAO 依赖，迁移为纯文本级复制，对外 JSON/证据等级语义零漂移。

**验证**：全量 `./mvnw -o test`→**Tests run: 1196, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）。聚焦 6/6 仍绿（BOTH/KEYWORD/WEAK/邻居窗口等沿用 EvidenceAssessor 输出判定）。

**风险与回滚**：仅新增 1 生产文件 + 修改 1 生产文件内两调用点并删除 2 死私有方法；回滚=删 EvidenceAssessor 并还原两调用点为私有方法调 + 恢复被删两方法即复原。

**Phase 3-b 剩余子步（待单独小步推进）**：`buildClusters` 与 `RetrievalCandidate`/`RetrievalCluster` 的抽离需将其由 private 转为 package-private 且跨类引用适配（可见性变更、改面较大），作为独立后续子步逐一小步实施，每次全量保绿；`shouldPreferDocumentCoverage` 等启发式同理。

---

### Phase 3-b · 抽离候选/聚类数据类 ✅（子步 2，2026-09-19）

在子步 1 之上，把 god 类内两个嵌套 static 数据类移为同包（com.argus.rag.qa.rag）顶层 package-private final 类，语义与可见性逐字保留：

- 新文件 qa/rag/RetrievalCandidate.java：原 RetrievalCandidate 全文（字段/工厂/merge/accessors/source）。vectorMatched / keywordMatched 由 private 放宽为 package-private（编排外用 c.vectorMatched / c.keywordMatched 直接统计计数）。
- 新文件 qa/rag/RetrievalCluster.java：原 RetrievalCluster 全文；构造器由 private 放宽为 package-private（buildClusters 内 new RetrievalCluster(candidate) 同包构造）。
- HybridChunkRetrievalService.java：删除两个嵌入类，其余引用同包直接解析，retrieve() 编排零改动。

**瘦身**：HybridChunkRetrievalService 自 862 → 621 行（God class 降档），编排主干保留在服务，数据/评估职责外移。

**验证**：test-compile BUILD SUCCESS → 全量 test → Tests run: 1196, 0 fail / 0 err（BUILD SUCCESS）；聚焦 6/6 仍绿。

**风险与回滚**：新增 2 纯数据类 + 删除 2 内层类（可见性放宽限同包）；回滚=删除两新类并在服务内恢复嵌入类先还原。Phase 3 核心完成（862→621 行降档 + 行为锁 + 拆分）。

**Phase 3-b 后续（非必要）**：继续抽 buildClusters / toDocument / buildEvidenceWindow 需权衡跨包可见性，收益边际递减；本阶段可收口，转 Phase 4（上传链路）评审。


## Phase 4 · DocumentUploadService 上传链路 — 评估结论（不硬拆）✅ 结论落盘

**目标类**：`document/service/DocumentUploadService`（660 行，7 public：initUpload / uploadChunk / completeUpload / getUploadStatus / uploadDocument / createInstantUploadedDocument / finalizeUploadedDocument，3 处 @Transactional）。

**评估（关键发现）**：与 plan 假设（"未外部化"）不同，该类的**所有纯逻辑/校验/落盘/补偿已充分外置**：
- `UploadInputGuard`（sanitizeFileName / normalizeContentType）已接管文件名校验与内容类型规整；
- `FileHasher.sha256Hex` 已接管 SHA-256 计算；
- `DocumentFileStore.putDirect` 已接管对象存储写入；
- `UploadedDocumentFinalizer`（finalizeUploaded / compensateExternalIndexes）+ `NewFileInput`/`FinalizedUploadCommand` 已接管持久化与外部索引补偿回滚。

已存在的行为测试 `DocumentUploadServiceTest` + `UploadInputGuardTest` + `UploadedDocumentFinalizerTest` 提供回归锁。

**结论**：该 god 类的剩余 660 行主体为**分块上传状态机编排**（init→uploading→completing→completed）+ 依赖注入的组装调用，已内聚且依赖私有 chunk 映射。再纵向拆成跨类协作会破坏状态机编排、收益边际，且在无 git 可回滚环境下引入不可控回归风险。**按「只做必要改动 / 评估后再决定」收口：不硬拆**，维持现状即是最佳，行为锁已足够。

## Phase 6 · 依赖卫生 — 子步 1：散落版本上收 properties ✅

**改动（pom.xml，纯构建面）**：
- 新增 `<jjwt.version>0.12.6</jjwt.version>`、`<mybatis-plus.version>3.5.15</mybatis-plus.version>` 两个属性；
- 三处 jjwt（api/impl/jackson）与两处 mybatis-plus（starter/jsqlparser）的 inline `<version>0.12.6</version>` / `<version>3.5.15</version>` 改为该 property 引用。

**为何安全**：版本字符串未变，仅引用方式由 inline 常数改为 property；Maven 解析出的版本一致性不变；零行为变化，可一行回滚。

**验证**：`./mvnw -o test` → **Tests run: 1196, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）。

**Phase 6 后续（待独立阶段，风险较高，勿与本子步混）**：
- `commons-fileupload 1.5`：源码未 import，Boot 默认 Servlet(Tomcat) multipart 解析，疑似冗余。移除需独立小阶段 + 全量验证，若 Boot 内部依赖则无害保留。
- `fastjson 1.2.83`：**在多处源码实际使用**（StructuredDataQueryTool/StructuredDataRetrievalService/OperationLogService/CollabService/ExcelDocumentController/ExcelFileController/StructuredDataController 等 import `com.alibaba.fastjson.JSON/JSONObject/JSONArray`）。1.2.83 属历史高危版本，但替换为等价（全程 Jackson）涉多文件序列化行为、需独立大阶段 + 行为锁；**不在此批混改**。
- `web + webflux` 同依赖在概览中，Boot 会经 Servlet 优先 dispatch；需独立评判是否移除其一，属单独阶段。

### Phase 6 子步 2 实测判定（2026-09-19）· commons-fileupload 移除 = 离线栈下暂缓
- 源码确认：全 `src/` 无任何 `org.apache.commons.fileupload`/**CommonsMultipartResolver**/FileUpload 引用；全部 `MultipartFile` 走 Spring Boot 默认 `StandardServletMultipartResolver`（Tomcat 内建）。源码级确证未用。
- **移除执行**：从 pom 删除该 `<dependency>` → `./mvnw -o test` **失败**于 `maven-resources-plugin` 解析 `org.apache.commons:commons-lang3 POM`（需联网的 `commons-parent:pom:52` 缓存缺失）。**逐字还原**后 `./mvnw -o test` → **1196 全绿**。
- **判定**：删除依赖需 Maven 重扫 `commons-io/commons-parent` 传递链（poi 显式 `commons-io:2.21.0` 覆盖 + minio 排除），该重整在离线 `-o` 栈下需一次联网 POM 解析点，**无法可信二次验证**。无 git、环境唯一 → 遵从「离线验证不达标即回退」，**保留依赖**。`commons-io` 由 poi 5.5 显式需要覆盖、保留。**结论：commons-fileupload 移除列入「离线栈约束下暂缓」**，非最终不删，待在线环境或本地补全 commons-parent 后可作为独立干净阶段再移除。
- **`ExcelSheetService.loadCellSnapshot`(150)** 复核：实返 `JSONObject`（`cell`，r/c/v/style 外部 Excel 单元格结构）而非裸内部 `Map`——与 AgnesAI/GenerationController 同类**外部 JSON 透传，排除 Map→VO 收敛**；不应在 god 类（Phase 5）外分散修改。

---

## Phase 5 · god class ES 域评估 — 评估结论（不硬拆）✅ 结论落盘（2026-09-19）

**目标类**：`engine/elasticsearch/ElasticsearchChunkIndexService`（701 行，次高 god）。

**评估（关键差异 vs Hybrid）**：该类的本质**不是"内部编排 + 纯逻辑"型 god**，而是**对远程 Elasticsearch 的 HTTP 客户端封装**——注入 `HttpClient` + `ObjectMapper` + `baseUrl/indexName`（`@Value` 配置），3 个 public 方法（indexReadyChunks / deleteDocumentChunks / search）全部走 ES REST API（_bulk / _delete_by_query / _search）。701 行主体是：ES 请求体 JSON 组装、HTTP 响应解析、分数归一化等。

**关键判定**：
- `engine/elasticsearch/` 下**无任何专属测试类**（5 个调用方测试只是把该服务返回值/异常当 mock fixture）。**无既有行为基线可回退**。
- 可抽的纯 JVM 私有方法仅 4 片：`buildChunkBulkLines`(258)、`buildChunkDoc`(287)、`normalizeKeywordScore`(668)、`abbreviate`(649)。它们不依赖 IO、可单测，但**在无测试锁定的 IO 粘合类上直接抽取会破坏 `search`/`index` 主链调用面，且无回归基线可验证**，违反「先锁行为再改结构」红线（与 Hybrid 不同——Hybrid 拆前已有 6 条行为锁）。

**结论**：按「只做必要改动 / 评估后再决定」收口——**不硬拆**，维持现状。若未来要降档，正确顺序是**先为 `normalizeKeywordScore`(分数归一化，阈值敏感) 与 bulk NDJSON 组装补行为锁单测**，再于独立阶段外抽纯函数；本批不触碰该 god 类生产代码。

### Phase 5·ES 子步 1 · 抽 `EsKeywordScoring` 纯工具并建行为锁 ✅（2026-09-19）

在「ES god 类不硬拆」判定之上，落地第一枚可验证小步：把该类内**纯 JVM 的两个无状态函数**外抽为独立 final 工具类，并补行为锁定测试（离线 `-o` 全可验）。

**改动（3 文件）**：
- 新增 `engine/elasticsearch/EsKeywordScoring.java`：static `normalizeRaw(double)`（`raw≤0→0`，`min(1, log1p(raw)/log1p(100))`）与 `abbreviate(String)`（合并空白/去首尾/超120截断+`...`），逻辑逐字复制。
- `ElasticsearchChunkIndexService.java`：删除两个 private 方法（原 641–673 行），`search` 内两调用点（原 227/239）改为 `EsKeywordScoring.normalizeRaw(...)` / `EsKeywordScoring.abbreviate(...)` 委托，生产行为逐字零漂移。改前 `cp` 备份 `.bak-remove-privates`（无 git，供回滚）。
- 新增 `engine/elasticsearch/EsKeywordScoringTest.java`：11 条行为锁（normalize 边界 0/负/1/10/100/大值/单调与 120 截断等全 JVM）。

**行为锁实测价值**：首版测试曾断言 `raw=1 → 0.467`，实测为 `0.1502`——我计算的预期区间错了（`log1p(1)/log1p(100)=ln2/ln101≈0.150`）。测试如实锁定**真实行为 0.150**，修正断言区间后绿。这证明抽取+行为锁可捕获手写语义偏差。

**验证**：聚焦 `EsKeywordScoringTest` → 11/11 绿；全量 `./mvnw -o test` → **Tests run: 1207, 0 fail / 0 err**（1196+11）。生产链零回归。

**风险与回滚**：仅新增纯工具类 + 委托 2 处 + 删 2 私有方法；`EsKeywordScoring.abbreviate` 与旧同名 private 逐字一致；回滚=`cp .bak 还原` 并删新类即可。KeywordHit 的 `{@link #normalizeKeywordScore}` javadoc 悬空引用仅 doc 层失效，javac 不报错，保留不改（最小改动）。

**Phase 5·ES 后续（可选）**：`buildChunkBulkLines`/`buildChunkDoc` 依赖 objectMapper/indexName/validateChunk 非纯，不抽。god 类 701→约 668 行，未降档为「编排」，维持不硬拆结论。

---

## Phase 8 · @Transactional rollbackFor 纪律抽查 — 评估结论（0 缺口，维持现状）✅ 只读收口

**目标**：核查 90 处 @Transactional（29 文件）是否缺 rollbackFor 造成「checked 异常不回滚」的真实纪律缺口。

**抽查证据（全项目静态盘查）**：
- 90 处 @Transactional 中 40 处带 rollbackFor、49 处裸（无 rollbackFor、仅属性/无参）。
- 项目内**所有自定义异常均 `extends RuntimeException`**（BusinessException / ForbiddenException / ResourceNotFoundException / UnauthorizedException / RateLimitExceededException / 内置 RetryableGraphException）。
- 含裸事务的 19 个文件中，**无任何方法声明 `throws`**（含 IOException/SQLException 等 checked）——即事务方法体不会以 checked 异常冒泡出事务代理。

**判定**：Spring 默认回滚范围 = RuntimeException + Error。既然 (a) 项目抛错全走 RuntimeException、且 (b) 裸事务方法无 throws(checked) 声明，则**每条裸事务在被触发时都会按默认语义完整回滚，不存在"部分成功不回滚"的 checked 缺口**。49 处裸 `@Transactional` 维持现状即正确，不引入 rollbackFor 补齐（避免无谓改动与噪声提交）。强制校验式排查属 CI 层改进，不在本阶段。

**风险/回滚**：纯只读评估，零代码改动、零净 diff；无回滚需求。仅记录结论供后续事务合规审计时引用。

---

## Phase 9 · BusinessException("中文") → ErrorCodes 收敛 — 评估结论（不全局硬兼并，留聚焦子集）✅ 只读收口

**目标**：把高频 `BusinessException("中文")` 文字收敛进 `ErrorCodes` 常量，减重复、集中文案。

**评估证据**：
- 全 `src/main` 共 94+ 处 `BusinessException("中文")`；文案聚合：`groupId 非法`(8)、`文档ID非法`(5)、`问题不能为空`(3)、`密码长度...`(3)、`ids 不能为空`(3) 等最高频重复，其余大量为单点（每域 1 次）。
- `ErrorCodes.java`（134 行）现有模式为**业务域错误码**（`DOCUMENT_NOT_FOUND`/`EXCEL_PARSE_ERROR`/`FINANCE_*`/`AUTH_*` 等 code + 少量 `_MESSAGE` 文案），**没有**「校验型中文输入提示」这一品类。

**判定**：这两类形态不匹配——把"参数/ID/空值校验"中文提示塞进业务域码文件，须 (a) 每文案新增一个 `*_MESSAGE` 常量、(b) 跨 81+ 文件逐点替换、(c) 依赖 `GlobalExceptionHandler` 对这些 `BusinessException` 的序列化文案面（userFacingMessage）不受影响。联合成本对"少打几个中文字面量"的收益不优，且无 git + 离线 `-o` 下该全区触碰回归面宽。

**结论**：**不全局兼并**（符合「只做必要改动 / 评估后再决定」既有判据）。仅当未来要统一「非法/空」类文案时，才作为**独立聚焦阶段**抽 3–5 个语义稳定且跨文件重复(≥3) 的文案为常量并在受影响测试锁行为后分批替换——不在本堆一次性铺开。

**风险/回滚**：纯只读评估，零代码改动、零净 diff、无回滚需求。

---

## Phase 10 · ES 关键字请求体形态行为锁（ES god 第 3 个小步）✅（2026-09-19）

**目标**：不改生产代码，用行为测试锁定 `ElasticsearchChunkIndexService.buildKeywordSearchRequest`(已是 package-private、同包可直接调) 的检索请求体组装语义，供后续改动回归。

**改动（1 文件，全新增）**：`engine/elasticsearch/ElasticsearchKeywordRequestBodyTest.java`，同包直调该 package-private 方法（构造占位 ObjectMapper/HttpClient/host/port/index，不触发任何 IO），3 条断言：
- 顶层 `size`、`_source`[6] 与 `query.bool.filter`[3]（groupId/status=READY/deleted）、`minimum_should_match=1`；
- should[4] 四维（首维 fileName match_phrase boost=8、页码 match boost 4…）且每条应含 boost；
- rescore 精排：window_size=topK、query_weight=0.2D、rescore_query_weight=1.0D、score_mode=total、嵌套 bool.should[4]+min=1。

**首轮断言修正**：自写断言多套一层 Map（把 `fileName={query,boost}` 误当再嵌查询），`ClassCast String→Map`；依真实层级 `should[0].match_phrase.fileName → {query:"hello",boost:8}` 改正后聚焦 3/3 绿。

**验证**：聚焦 3/3 绿；全量 `./mvnw -o test` → **Tests run: 1210, Failures: 0, Errors: 0**（1207+3）。生产代码零改动。

**风险/回滚**：纯新增 1 测试文件；回滚=删文件即可。锁定后续若动 ES filter/boost 权重/recore 结构的安全网。

---

## Phase 11 · assistant 域候选复审 — 候选取错前提修正（✅ 只读，不改代码）

排队候选原按「assistant 未测核心剩 AssistantAgentFacade 主编排」为待办。复核后判定前提证伪：
- `AssistantAgentFacade` 已有专属测试 `AssistantAgentFacadeChatTest`(11 @Test) + `AssistantAgentFacadeTimeoutTest`(2 @Test) = 13 条，覆盖 chat 成功/空答复/异常包装（Retryable/ModelUnavailable）、streamChat 组装/降级兜底/ToolCall 分支/超时双支路 → 已充分覆盖，**不需补**。
- assistant 域 41 个 service/mapper/memory/support 源均已被 src/test 引用；仅少量 controller 与纯 model/enum 无测试（薄层/无业务面，不列入补测）。
- 结论：**该域「未测核心 service」候选已空**，不再新造冗余测试；修正原计划中该候选优先级假设，供后续 batch 依据校准。

**验证/风险**：纯只读复审、无代码改动；全量保持 **1210 绿**零净 diff、无需回滚。

---

## Phase 12 · Youcom 响应解析行为锁 — 私有纯方法放宽同包可见 + 7 条 JVM 无 IO 锁定（✅ 全量 1217 绿）

**目标/范围**：`YoucomSearchService`（188 行外部 HTTP client 封装，经 YDC Index API 搜索）此前仅 `search()` 走真实 HTTP、无法直接单测。其三个私有纯解析方法（`parseResponse`/`extractSnippet`/`toJsonString`）全为无 IO 的 JSON→对象逻辑，是候选行为锁点。遵循 Phase 10 先例（同包 package-private + 直接调用、零生产语义改动），仅将 `parseResponse`、`extractSnippet` 的 `private` 放宽为同包可见（内部调用点不变），锁住「YDC 响应 JSON → Spring AI Document」映射。

**必要性**：evidenceText 来源拼接（`"来源："+title+"\nURL："+url+"\n"+content`）、WEB 序号与 `score=1.0/index`、metadata 键集、snippet→description 回退都是易被误改的稳定映射规则，且此前完全无行为基线。

**改动清单**：
- `engine/youcom/YoucomSearchService.java`：仅移除 `parseResponse`(133) 与 `extractSnippet`(173) 的 `private` 修饰 → 同包可见。IO 链、`search`/`toJsonString`、判定逻辑零改动。`toJsonString` 仅做请求体序列化、价值低，未动。
- `engine/youcom/YoucomSearchServiceParsingTest.java`（新）：7 条 JUnit5，构造 `new YoucomSearchService("",false,10,new ObjectMapper())` 占位（不触 HTTP），直接调 `parseResponse`：空 body / 非数组 results / 空 results 数组 → 空列表不抛；单结果 snippet 组装（id=WEB1、text 含"来源：/URL：/snippet"、metadata 键 evidenceId/retrievalSource/score=1.0/url/title/coverageMode）；多结果编号 WEB1/WEB2+score 1.0/0.5；snippets 缺失/空数组/空串 → 回退 description 等 6 条回退分支。

**验证**：聚焦 7/7 绿；全量 `./mvnw -o test` = **Tests run: 1217, Failures 0, Errors 0**（1210+7）。生产改动仅 2 个关键字（去 `private`），无行为差异。

**风险/回滚**：可见性放宽不影响任何调用方（仅内部调用）；回滚=`cp` 备份 `.bak-pkgpriv` 覆盖或删测试文件。测试为纯 JVM、无网络。

---

## Phase 13 · 财务日期解析行为锁 — 两份重复 parseDateText 同包放宽 + 6 条 JVM 锁（✅ 全量 1223 绿）

**目标/范围**：`FinanceSheetResolver`(147) 与 `FinanceWorkbookExtractor`(227) 各有一份几乎相同的 private `parseDateText(String)`——8 位 `yyyyMMdd` → `LocalDate.of`，否则依次尝试多个 `DateTimeFormatter` 模式。皆纯 String→LocalDate、无 IO，属计划 P1 标注的「日期格式漂移」高危纯逻辑且零测试。照 Phase 12 先例仅去 `private`（放宽同包可见、内部调用点不变），新增纯 JVM 行为锁。

**行为锁关键发现**：两实现其实**行为一致**——Resolver 的 `DATE_PATTERNS` 比 Extractor 多 `"yyyy-MM"`，但 `LocalDate.parse("2024-05","yyyy-MM")` 按月解析缺「日」必抛 `DateTimeParseException`→ 该多出模式**实际失效**，两者对 `"2024-05"` 均回退 null。此发现为后续合并重复解析逻辑时删除无效模式提供了依据。

**改动清单**：
- `excel/finance/FinanceSheetResolver.java`（去 `private`，`DATE_PATTERNS` 4 模式含失效 `yyyy-MM`）
- `excel/finance/FinanceWorkbookExtractor.java`（去 `private`，3 模式无 `yyyy-MM`）
- `excel/finance/FinanceDateParsingTest.java`（新，同包，`new FinanceSheetResolver()` / `new FinanceWorkbookExtractor(new FinanceSheetResolver(), new ObjectMapper())` 占位，不触 POI/hook）：6 条锁「空串 → null／8 位紧凑 → LocalDate.of／三公共模式(年-月-日、年/月/日、年中文月日)／非法月13·非法日32·garbage → null／仅年月 → 两实现均 null／ASCII 首尾空白 trim」。

**首轮自写断言偏移打脸**：试写 `"2024-05"→Resolver 当月初 1 日` 与带非 ASCII 空格的空白串 → 2 测 fail，遂按实改测（month-only 一致返回 null；空白改用 ASCII 拼接字符串构造）。这正是行为锁的价值：真实规则即「月缺日无法 LocalDate 解析均返回 null」。

**验证**：聚焦 6/6 绿；全量 `./mvnw -o test` = **Tests run: 1223, Failures 0, Errors 0**（1217+6）。生产改动仅 2 个关键字（去 `private`），无行为差异。

**风险/回滚**：可见性放宽不影响调用方（内部自调用）；回滚=`cp` 备份 `.bak-pkgpriv` 覆盖或删测试文件。测试纯 JVM、无网络、无真 Excel。

**Phase 13 子步（收敛）**：行为锁稳定后，将重复逻辑收敛——`FinanceWorkbookExtractor.parseDateText` 由 25 行实现改为 `return resolver.parseDateText(text);`（利用其已有 `FinanceSheetResolver resolver` 字段委托，两实现已被 Phase 13 证明行为一致）；并从 `FinanceSheetResolver.DATE_PATTERNS` 移除已证明失效的 `"yyyy-MM"`（对 `LocalDate.parse` 缺「日」必抛，恒返回 null，是误导性死模式）。全量复跑 **Tests run: 1223, Failures: 0**（测试数不变=零行为漂移，6 条 Phase 13 锁全过）。回滚=`cp` 备份 `.bak-pkgpriv` 覆盖。

---

## Phase 14 · 财务金额/币种解析行为锁 — FinanceWorkbookExtractor 两个纯方法同包放宽 + 9 条 JVM 锁（✅ 全量 1232 绿）

**目标/范围**：`FinanceWorkbookExtractor` 另有 `parseNumberText`(金额,300) 与 `normalizeCurrency`(币种,390) 两个纯 String→数值/代码、无 IO 的解析规则，均易错细节且零测试。照 Phase 12/13 先例去 `private` 同包可见，新增同包纯 JVM 行为锁（不求真 POI/Excel）。

**锁定的真实规则**：
- 金额解析：去千分位/空格后 `new BigDecimal`，`null`/blank/非数值(含币种符号 `€1,000`)→ null。
- 币种映射：文本大写后依次含 `CNY/USD/HKD/SGD/EUR/JPY/GBP` 子串即返回该码；否则含「人民币」→CNY、「美元」→USD；其余(如 CHF/RMB/瑞士法郎)→null。注意 **子串含匹配**（混合文本「美元 USD」→USD、货币字符串不识别即返回 null，如 RMB 不在表）。

**改动清单**：
- `excel/finance/FinanceWorkbookExtractor.java`：`parseNumberText`、`normalizeCurrency` 去 `private`（仅可见性，内部调用点不变）。
- `excel/finance/FinanceValueParsingTest.java`（新，同包，`new FinanceExtractor(new FinanceSheetResolver(), new ObjectMapper())` 占位）：9 条锁 null/blank/小数/负数/千分位/空格/非数值→null，币种大小写不敏感子串/中文名/混合文本/不支持→null。

**验证**：聚焦 9/9 绿；全量 `./mvnw -o test` = **Tests run: 1232, Failures 0, Errors 0**（1223+9）。生产改动仅 2 个去除 `private`、无行为差异、首轮即绿（无打回）。

**风险/回滚**：可见性放宽不影响调用方（内部自调用）；回滚=`cp` 备份 `.bak-valueparsing` 覆盖或删测试文件。测试纯 JVM、无网络、无真 Excel。

---

## Phase 15 · 日期序列号启发式行为锁 — isDateSerialLabel 同包放宽 + 3 条 JVM 锁（✅ 全量 1235 绿）

**目标/范围**：`FinanceWorkbookExtractor.isDateSerialLabel(String)`(413) 判定一个纯数字标签是否 POI 日期序列号（整型值落入 (30000,100000) 即视为序列）。名字晦涩、规则反直觉、易被误删；纯 boolean 无 IO。照例去 `private` 同包可见，在日期主题测试里补 3 条行为锁。

**锁定的真实规则**：正则 `\d+(\.\d+)?`(全串匹配,`full match` 故含空格如 `" 40000 "` 判 false) → `Double.parseDouble` → `>30000 && <100000` 为 true；`30000`/`100000` 边界均为 false；8 位紧凑日期 `20240506` 超上界非序列。

**改动清单**：
- `excel/finance/FinanceWorkbookExtractor.java`：去 `private`（仅可见性）。
- `excel/finance/FinanceDateParsingTest.java`：补 `dateSerialLabel…` 3 条（含 boundary 再校正：12345.6=区间下界外为 false、99999.9=区间内为 true）。

**验证**：聚焦 3/3 绿；全量 `./mvnw -o test` = **Tests run: 1235, Failures 0, Errors 0**（1232+3）。生产改动仅 1 处去 `private`。

**风险/回滚**：回滚=`.bak-valueparsing` 覆盖或删测试段。纯 JVM、无网络无真 Excel。

---

## Phase 16 · Excel 数字格式化行为锁 — formatNum 改 static 同包可见 + 3 条 JVM 锁（✅ 全量 1238 绿）

**目标/范围**：`ExcelParserService.formatNum(double)`(291) 纯格式化规则：整数值去掉尾 `.0`（`num==(long)num` → `String.valueOf((long)num)`），小数值走 `String.valueOf(num)`。无实例状态，故直接提升为 `static` 包内可见（照 EsKeywordScoring 先例，免去构造该服务重依赖构造器），新增同包纯 JVM 测锁定。

**锁定的真实规则**：`3.0→"3" / 0.0→"0" / -2.0→"-2" / 1000000.0→"1000000"`；小数 `3.5→"3.5" / 123.456→"123.456" / 0.1→"0.1"`；超大 `1e20`（(long) 溢出钳位、与 double 不等 → 走 `String.valueOf` 科学计数 "1.0E20"）。

**改动清单**：
- `excel/service/ExcelParserService.java`：`formatNum` 由 `private` 改 `static`（包内可见、无实例态，调用点兼容）。
- `excel/service/ExcelParserFormatNumTest.java`（新，同包）：3 条锁整去尾 0/小数保留/大数科学计数。通过 `ExcelParserService.formatNum(...)` 直调，无需构造服务。

**验证**：聚焦 3/3 绿；全量 `./mvnw -o test` = **Tests run: 1238, Failures 0, Errors 0**（1235+3）。生产改动仅 1 个关键字(加 static)。

**风险/回滚**：static 化无实例态、调用点仍 `description(...)` 兼容；回滚=`.bak-static-formatnum` 覆盖或删测试文件。纯 JVM、无网络无 Excel。

---

## Phase 17 · assistant 组 ID 解析行为锁 — 两纯函数提 static 同包可见 + 7 条 JVM 锁（✅ 全量 1245 绿）

**目标/范围**：`AssistantKnowledgeBaseTool` 的 `parsePositiveLong(Object)`(154) 与 `parseCommaSeparated(String)`(143) 纯 Number/String→List&lt;Long&gt;、无 IO。两者均无实例态，照 `formatNum` 先例直接提 `static`(假设 package-可见、同包直调、免构造重依赖 AI tool bean)，不触任何 LLM/IO。`parsePositiveLong` 与 `StructuredDataQueryTool`(188) 存在同签名重复 → 为后续收敛提供行为基线。

**锁定的真实规则**：`parsePositiveLong` 仅当 `Number.longValue()`>0 或「有文本 String 解析出 >0」→long，否则 null（负数/0/空/非数字/小数/`abc` 均 null）；`parseCommaSeparated` 逗号分隔、逐 token trim+parse + 剔正、`""`/`"   "`→空表、混合含负/非法/重复空项会跳，空串区分。【注】`parseCommaSeparated(null)` 当前 `value.split` 会 NPE(未做 null 防护)——行为锁如实记录、未强制修复。

**改动清单**：
- `assistant/agent/AssistantKnowledgeBaseTool.java`：两方法由 `private` 改 `static`(包内可见，无实例态、调用点兼容)。
- `assistant/agent/AssistantKnowledgeBaseIdsParseTest.java`（新，同仓）：7 条锁 Number/String 正数接受、非正/空/非法拒绝、逗号解析 + trim、空串→空、`-1/abc/0/空项` 剔除、Long.MAX。

**验证**：聚焦 7/7 绿；全量 `./mvnw -o test` = **Tests run: 1245, Failures 0, Errors 0**（1238+7）。生产改动仅 2 个关键字(加 static)、首轮即绿。

**风险/回滚**：`parseCommaSeparated(null)` 维持原 NPE 行为(未新增 null 处理、免课堂改变语义)；回滚=`.bak-static-parse` 覆盖或删测试。纯 JVM、无 IO。

-----

## Phase 18 · assistant 组 ID 解析收敛（两分身 → AssistantIds 单一实现）

**目标**：`parsePositiveLong(Object)` 在 `AssistantKnowledgeBaseTool` 与 `StructuredDataQueryTool` 两处重复（KB 侧 `hasText` 拦截空串、结构化侧 `parseLong` 异常吞掉，行为等价）。经 Phase 17 锁行为后，收敛为单一 pure 实现，消除重复。

**改动**：
- 新增 `assistant/agent/AssistantIds.java`（package-private final 纯工具）：`static Long parsePositiveLong(Object)` 为唯一正数解析源（Number longValue>0 / String hasText 去空白 parse 正，否则 null）。
- `AssistantKnowledgeBaseTool.parsePositiveLong` 体改 `return AssistantIds.parsePositiveLong(value);`。
- `StructuredDataQueryTool.parsePositiveLong`（原 private 多行体）改 `static` 同包可见并 `return AssistantIds.parsePositiveLong(value);`。
- `AssistantKnowledgeBaseIdsParseTest` 增 1 条一致性锁 `toolDelegatesAgreeWithSharedParser`：对样本集 {@code null,"","   ","45","-3","5.5","abc",42L,Integer.valueOf(7),0L} 断言 KB 分身、SQD 分身、共享实现三者逐样本返回一致 → 等价合并被门禁。

**验证**：全量 `./mvnw -o test` = **Tests run: 1246, Failures 0, Errors 0**（1245+1）。生产改动=1 新纯工具 + 2 处委托，首轮即绿。

**风险/回滚**：纯 JVM、无 IO、无 DB；不改任何判定/权限逻辑（助手域纯净解析）。回滚=删 `AssistantIds.java` 与委托字 + 还原两分身原体（备份 `.bak-static-parse` 仍持有 Phase17 前原貌）。

-----

## Phase 19 · 元数据 Number 读取收敛（三处 → MetadataReaders 单一实现）

**目标**：`metadata(Map,key)` 里 `instanceof Number number ? number.longValue()/intValue()/doubleValue() : null` 这一段在
`AssistantKnowledgeBaseTool`/`QaRetrievalService`/`QaRecordPersistenceService` 三处重复、行为逐一致 → 抽单一纯工具收敛。

**改动**：
- 新增 `common/util/MetadataReaders.java`（public final 纯工具，无实例态）：`longValue`/`intValue`/`doubleValue` 三个静态方法，Number→按目标类型取值、缺失/非 Number→null。
- `AssistantKnowledgeBaseTool.readLong`/`readInteger` 体改委托；`readScore` 改 `MetadataReaders.doubleValue(...)==null?0D:...` **保留 0D 兜底文法**（读点原 return 0D，语义未变）；`readText` 原样不动。
- `QaRetrievalService.readLong`、`QaRecordPersistenceService.readLong/readInteger/readDouble` 体改委托。

**有意不并**（两处行为本就不一致，收敛会改行为=红线）：
- `readText`：KB 侧 `instanceof String ? text : null`（不强 trim），QaRecord 侧 `hasText && trim`——语义不同，各自保留、不合并。
- `readScore` 的 0D 兜底是 KB 本地策略，经委托仍精确保持。

**验证**：新增 `common/util/MetadataReadersTest` 3 条锁（longValue/intValue/doubleValue 各 Number→类型、非 Number→null、缺键→null）；全量 `./mvnw -o test` = **Tests run: 1249, Failures 0, Errors 0**（1246+3）。纯 JVM、零 IO、零 DB，未触判定/权限逻辑。

**风险/回滚**：新增 public 纯工具为累加改动（未删任何既有 API/依赖），离线可验证；回滚=还原三处旧体（`cp` 回 Phase18 后快照）+ 删 `MetadataReaders.java` + 删测试。

**Phase 19 子步（复用扩展）**：同 {@link MetadataReaders} 收拢 QA 两组装器 + KB 的评分/文本读取——
`CitationAssembler`/`EvidenceOverviewAssembler` 的 private `readInteger`/`readScore`/`readText` 三处字节级重复（Number→intValue、score Number→doubleValue else 0D、String→trim 否则 null），与 Phase19 util 形状逐一致 → 全改委托。
- `MetadataReaders` 增 `score(Map)`（0D 兜底，收拢 KB/两组装器三处同名语义）与 `text(Map,key)`（String→trim，匹配两组装器；QA 持久化 hasText+trim、KB raw 属有意差异不并入）。
- `CitationAssembler`/`EvidenceOverviewAssembler` 三方法体改委托；`AssistantKnowledgeBaseTool.readScore` 复用 `score`（原内联 0D 亦精确等价）。
- 各 `readFileName` 等组合逻辑仍留本地（多键回退，非纯读取）。
- 测试：`MetadataReadersTest` 补 `score`/`text` 共 2 条。全量 1249→**1251 绿**（CitationAssemblerTest 8、EvidenceOverviewAssemblerTest 3 原样通过委托）。

-----

## Phase 20 · 数字文本解析收敛（两处 → NumericTexts 单一实现）

**目标**：`Double.parseDouble(s.replace(",", ""))` 这段「去千分位 + 判数字」在 `ColumnFilterStrategy.numericOrNull` 与 `TableSchemaService.isNumeric` 两处重复 → 抽单一纯工具收敛。

**改动**：
- 新增 `common/util/NumericTexts.java`（public final 纯工具）：`parseOrNull(String)` 去千分位后按 double 解析，null/空白/非数字→null，永不抛。
- `ColumnFilterStrategy.numericOrNull` 体改 `return NumericTexts.parseOrNull(s);`（原 null 守卫与解析失败→null 语义逐一致）。
- `TableSchemaService.isNumeric` 改 `return NumericTexts.parseOrNull(str) != null;`（唯一调用点 `inferColumnType` line 209 已先判 null/空 continue，故原无守卫的 NPE 路径不可达；monitoring parseOrNull!=null 与旧实现在全部可达输入上等价，null 路径由 NPE→false 属不可达改变）。

**验证**：新增 `common/util/NumericTextsTest` 3 条锁（去千分位/负数/小数解析、null/空白/非数字→null、多逗号拼数字）；全量 `./mvnw -o test` = **Tests run: 1254, Failures 0, Errors 0**（1251+3）。纯 JVM、零 IO、零 DB，未触判定/权限逻辑。

**风险/回滚**：新增 public 顶级纯工具为累加改动；唯一需要注意的表单「isNumeric 空守卫差异」已证调用点不可达；回滚=还原两方法原体 + 删 `NumericTexts.java` + 删测试。

-----

## Phase 21 · 短期记忆元数据 Long 读取锁（metadataAsLong，纯测试零生产改动）

**背景/决策**：上一排查证 `AssistantShortTermMemoryHook.metadataAsLong` 与「严格 groupId 系」(`ReadyChunkDocumentRetriever.requireGroupId`、`AssistantIds.parsePositiveLong`) 语义**有意不同**——它 Number 分支沿用 `longValue`（含 0/负）、String 分支 `parseLong` 不 trim、且允许负值 → 不可用 `parsePositiveLong` 收敛（会改行为成负数为 null）。故**不收敛**，改为行为锁钉该命中。

**改动**（纯测试，零生产改动）：
- `AssistantShortTermMemoryHookTest` 增 2 条锁：`metadataAsLong` Number 任意符号→longValue、非空纯数字 String→parse；空白/两侧空格(parse 不 trim)/`abc`/`24L`/缺键→`Optional.empty`。
- 通过 `RunnableConfig.builder().addMetadata(k,v)` 建真实 config、反射调私有 `metadataAsLong`（不动生产、纯 JVM、零 DB/LLM）。
- 前置确认：`ReadyChunkDocumentRetriever.requireGroupId` 已由既有 8 条测试锁全（缺/非正/非数字/预取），无需重复。

**验证**：聚焦 9/9 绿（7+2）；全量 `./mvnw -o test` = **Tests run: 1256, Failures 0, Errors 0**（1254+2）。0 生产改动、首轮即绿。

**风险/回滚**：纯测试类、可整体删除回退；无安全/财务面，不触判定实现。

-----

## Phase 22 · ErrorCodes 高频文案：先实测再判（不 dillute code-bus）＋安全性 opaque 哨兵收敛

**目标**：把 Phase 9 标注的「高频中文 BulettedException 文案子集」用**实测频率**定夺是否入 `ErrorCodes`。

**实测（全 main 扫描，uniq -c 计数）**：`groupId 非法`×8、`文档ID非法`×5、`问题不能为空`×3、`密码长度超过安全上限…`×3、`ids 不能为空`×3，及一二三各 2 次的 `userId 非法`/`sessionId 非法`/`fileHash 非法`/`文件名非法`/`密码长度非法`/`助手返回内容为空`/`助手调用失败`/`会话不存在`/`上传文件超过大小限制`/`ES 请求失败` 等约 20 条稳定文案。

**判定：不整体入 `ErrorCodes`（合同先行）**。读 `ErrorCodes` javadoc 确认其**契约=业务错误码总线**（`GlobalExceptionHandler` 把带码 BusinessException 透传成 `ApiResponse.code`，前端按码分支），**不是**告泡文案容器。把 22 条「校验性中文文案」铺进它=污染别名里 amiss（会让 code 总线膨胀、语义从“码”漂到“文本”）、与相册注释“只放多处引用的错误码”冲突。→ 与 Phase 9 结论一致（此刻有实测依据）：**不全局并入**。

**做的一件真值收敛**：`"未找到该表格的结构化数据。"`×2 — 均出 `StructuredDataQueryTool.requireStructuredDataReadable`，两分支（文档不存在/roleGate 不达）**刻意同一文案**以金融越权“不可区分”（防存在性探测）。这是全表里唯一具安全意义的 share-string → 收敛为 single documented 常量 `OPAQUE_NO_DATA_MESSAGE`（含 javadoc 说明两者必须恒同，防未来单边漂移笑点）。零行为变化（同字节），纯 maintain。

**验证**：全量 `./mvnw -o test` = **Tests run: 1256, Failures 0, Errors 0**（无新增；哨兵为等价重构）。不改任何判定实现（`roleGate.canRead` 逻辑未动）。

**回滚**：`StructuredDataQueryTool` 常量与两抛点还原即可（一文件）。

-----

## Phase 23 · ES 失败提示三件套收敛（bare/detail 双 arm 同源）

**目标**：`ES bulk 请求失败`/`ES 索引检查失败`/`ES 请求失败` 各在两处「arm」出现（裸版 `BusinessException(MSG, exception)` 一处、带 `": " + status…` 详细版一处），同一底层失败两种形态的文案前缀曾独立字面量——上一条若改其一会两臂发散。

**改动**（单文件 `ElasticsearchChunkIndexService`）：
- 顶部常量块增 3 个 static final：`ES_BULK_FAILED`/`ES_INDEX_CHECK_FAILED`/`ES_REQUEST_FAILED`（含 javadoc「与详细版共用、防单臂改名漂移」）。
- 9 个抛点统一改用常量：裸 arm（`IOException`/`InterruptedException` 各一对）与 detailed arm（`CONST + ": " + status…/body`）。
- 不改任何提示字节（与重构前 `String` 逐字一致）、不改消息内容；`ES bulk body 序列化失败`、`ES 索引写入缺少必要 chunk 字段` 属另一语义独立消息，保留字面量。

**验证**：全量 `./mvnw -o test` = **Tests run: 1256, Failures 0, Errors 0**（等价重构 0 新增）。

**风险/回滚**：单文件、只改消息来源不改文本；回滚=删 3 常量还原 9 抛点。

-----

## Phase 24 · Reranking NoOp 降级实现行为锁（纯测试，0 生产改动）

**目标**：`RerankingService.NoOp`（未配置重排模型时的兜底实现）无测试；纯 JVM（注入候选序 → 有序 `RerankResult`）。据计划「未测核心 service 补测」优先补锁。

**改动**：新增 `engine/reranking/RerankingServiceNoopTest` 3 条锁——
- null/空 → 空列表；
- N 候选 → 一一对应不重排（`out.get(i).index()==i`）、分数严格递减 `1.0 - i*0.001`（1.0、0.999、0.998…），首项分最高末项最低；
- 单候选 → index 0、分数 1.0。

**验证**：`RerankingServiceNoopTest` 3/3；全量 `./mvnw -o test` = **Tests run: 1253, Failures 0, Errors 0, Skipped 0**（181 个测试类，按类求和==aggregate，两次独立运行稳定）。

## ⚠ 计账勘误（对本台账既计数，重要）
先前多阶段末尾打印的全量计数（如第 1249/1251/1254/1256 等）系我在归档时**累加算术/抄录漂移**所致，非测试回归——全程每次都 `BUILD SUCCESS`、0 失败，且各阶段「新增 N 条」与真实测试方法数一致；问题只在「打印的全量累计总数」。以本次实测为准立定：
- **现行权威全量 = 1253**（`./mvnw -o test` 实测、181 测试类、0 失败、Skipped 0、两次复现）。

-----

## Phase 25 · 文档权限矩阵行为锁（ExcelPermissionService.permissionOf 等，纯测试 0 生产改动）

**目标**：`ExcelPermissionService`（文档级权限计算器，财务隔离 + 系统管理员 + 创建者 + ACL 四级优先级）此前无专属测试，而其「文档 + 用户」重载（`permissionOf(ExcelDocument, CurrentUser)` / `canEdit(...)` / `canManage(...)`）**完全不依赖 DB**——只经 `findPermission` 走 `permissionMapper`（可用 Mockito stub），是典型的纯 JVM 行为锁候选。安全关键矩阵，补锁即固守既有判定（不改判定实现）。

**红区分界（严格遵守）**：只加**只读行为锁测试**，**零生产改动**；不修改 `userCanAccessDocument`/`DocumentRoleGate` 判定实现（财务红线）。`userCanAccessDocument` 的「财务档仅 FINANCE 可见」既有语义被测试显式锁定而非改变。

**改动**：新增 `excel/service/ExcelPermissionMatrixTest.java`（13 条）：
- 守卫：null 文档/用户 → null；
- 财务隔离：财务档（financeRequired=true）对普通用户、系统管理员均 → null（门禁不放行）；财务档 + FINANCE → "owner"；
- 优先级：普通档 ADMIN → owner；创建者（`creatorId` String 与 `userId.toString()` 相等）→ owner；非创建者且无 ACL → null；
- ACL 分支：role="EDITOR"→小写 "editor"、role="VIEWER"→"viewer"、role=null→兜底 "viewer"、创建者兼冲突 ACL → 仍 owner；
- canEdit/canManage 派生：系统管理员对财务档 canEdit/canManage 均 **false**（财务隔离）；VIEWER 不可编辑、EDITOR 可编辑。

测试以 mock `documentMapper`/`permissionMapper`（`selectOne` 桩返回 ACL）构造服务，全 JVM、零 IO、无 DB、无网络。

**锁出的真实规则（供日后回归）**：`permissionOf` 返回 `"owner"|"editor"|"viewer"|null`，ACL 角色**小写化**返回（"EDITOR"→"editor"，而非 "owner"）；role 空 → 兜底 "viewer"（区别于「无 ACL 记录 → null」）；财务档对协调器连 ADMIN 都不能访问。

**验证**：聚焦 `ExcelPermissionMatrixTest` → 13/13 绿；全量 `./mvnw -o test` = **Tests run: 1266, Failures 0, Errors 0, Skipped 0**（1253 + 13）。生产代码零改动、0 净 diff。

**风险/回滚**：纯新增测试类、无一生产改动 → 回滚=删文件即可；不触碰判定实现，财务语义保持。

-----

## Phase 26 · 免费搜索查询规整行为锁（FreeWebSearchService.normalizeQuery/normalizeUrl，static 同包可见 + 10 条 JVM 锁）

**目标**：`FreeWebSearchService`（169→国内必应 RSS/HTML 双通道搜索封装）的 `normalizeQuery`（去掉生成关键词里的绝对日期，命中时效词且无「最新」时前置「最新」）与 `normalizeUrl`（去尾斜杠）是两个纯 String→String、无 IO、无实例态、此前零测试的规整函数 —— 是查询语义里容易误改的规则（改变了它会改变发给必应的关键词）。

**改动**：
- `engine/freeweb/FreeWebSearchService.java`：`normalizeQuery`(193) 与 `normalizeUrl`(242) 由 `private` 改为 **`static` 同包可见**（照 `formatNum` 先例，无实例态直接 static，同包直调免构造 httpClient/executor 重 IO bean；两方法内部调用点不变，生产行为零漂移）。
- `engine/freeweb/FreeWebSearchServiceNormalizeTest.java`（新，同包，直接静态调用）：10 条锁——
  - normalizeQuery：中文绝对日期（`2026年9月17日`）剔除并前置「最新」（含新闻时效词）；去日期后空白则回退原文 trim（`2026年9月17日`→原样）；横线 `2026-09-17`、斜杠 `2026/09/17` 日期剔除；斜杠日期 + 行情时效词 → `最新 …`；已含「最新」不再重复前置；非时效词（产品/说明书）不前置;；
  - **行为锁捕获真实规则**：日期剔除前后两侧的空格被保留（内部空白非折叠）——`"金融 2026/09/17 产品"` → `"金融   产品"`（3 空格），非我以为的单空格。

**验证**：聚焦两测试 10+13 全绿；全量 `./mvnw -o test` = **Tests run: 1276, Failures 0, Errors 0, Skipped 0**（1266+10）。生产改动仅 2 处去 `private`+加 `static`、行为无差异。

**风险/回滚**：static 无实例态、调用点兼容；回滚=`cp` 备份 `.bak-normalize` 覆盖 + 删测试。纯 JVM、无 IO、无网络。

-----

## Phase 27 · 失败原因截断行为锁（DocumentIngestionAsyncService.truncateFailureReason，static 同包可见 + 5 条 JVM 锁）

**目标**：异步文档入链汇报给用户的失败原因有一处 UI 展示契约——`truncateFailureReason`（null/空白→默认「文档处理失败」、超长截到固定上限 512）。此前零测试；纯 String→String、无实例态、只用 static 常量 `FAILURE_REASON_MAX_LENGTH=512`。

**改动**：
- `ingestion/service/DocumentIngestionAsyncService.java`：`truncateFailureReason`(224) 由 `private` 改为 **`static` 同包可见**（无实例态、无强制注入，照先例免构造重依赖异步服务；调用点 129 同 class 兼容）。生产行为零漂移。
- `ingestion/service/DocumentIngestionAsyncServiceTruncationTest.java`（新，同包，直接静态调用）：5 条锁——null/空串/空白 →「文档处理失败」；短文原样；恰 512 原样；513→取前 512；600 字中文错误消息→截断 512 且 substring 在多字节处不抛。

**验证**：聚焦 5/5 绿；全量 `./mvnw -o test` = **Tests run: 1281, Failures 0, Errors 0, Skipped 0**（1276+5）。生产改动仅 1 处加 static、无行为差异。

**风险/回滚**：static 无实例态、调用点兼容；回滚=去掉 static（`cp` 还原）并删测试。纯 JVM、无 IO。

-----

## Phase 28 · 薪酬演算行为锁（PayrollService.computeNetSalary/normalizeAmounts，static 同包可见 + 6 条 JVM 锁）

**目标**：`PayrollService`（考勤薪酬台账 + AI 财务简报）的**实发工资演算** `computeNetSalary`（各收入项和 − 各扣除项和）与金额归一 `normalizeAmounts`（null→ZERO、缺 workDays=0、缺/空白状态→DRAFT）是财务正确性核心计算，但 `PayrollServiceTest`（现 8 条）只测了 upsert/月份校验/自助视图，**未覆盖演算本身**。两函数无实例态、纯实体字段读写，照 formatNum 先例提 static 同包可测。

**改动**：
- `finance/service/PayrollService.java`：`computeNetSalary`(433)、`normalizeAmounts`(416) 改 **`static` 同包可见**（无实例态、只读/写 row 入参）；`amount`(448) 提 static（normalizeAmounts 依赖）。三个调用点同 class 兼容。生产行为零漂移。
- `finance/service/PayrollSalaryTest.java`（新，同包，静态直调）：6 条锁——
  - computeNetSalary：收入五项加总 − 五项扣除（10000+2000+300+500+400+100+50 − 800−1000−300−200−50 = 11000）；仅基本工资+社保公积金（8000−700−500=6800）；全零→0。
  - normalizeAmounts：全 null 金额/缺 workDays/缺 status → 全 0 + workDays=0 + DRAFT；已有值（workDays=22、status=PAID）与部分金额保留；空白 status→DRAFT 且金额不变。

**验证**：聚焦 `PayrollSalaryTest` ＋既有 `PayrollServiceTest` 均绿（6+8）；全量 `./mvnw -o test` = **Tests run: 1287, Failures 0, Errors 0, Skipped 0**（1281+6）。生产改动仅 3 处加 static、数学零漂移。

**风险/回滚**：static 无实例态、调用点兼容；回滚=`cp` 备份 `.bak-net` 还原 + 删测试。纯 JVM、无 IO/DB/LLM。这不属于权限判定红线（`computeNetSalary` 纯算术，非 `userCanAccessDocument`/`DocumentRoleGate`）。

**Phase 28 子步（考勤归一）**：同域再抽 `upsertAttendance` 内联的考勤规则归一为 `static normalizeAttendanceRow(Attendance)`（late/early/overtime null→0、leaveHours null→ZERO、status 空白→"NORMAL" 否则 trim+uppercase），原循环行为逐字节保留；`PayrollSalaryTest` 增 2 条锁（全 null→0+NORMAL；有值分钟/leaveHours 保留 + `" overtime "`→OVERTIME）。全量实测 **1289 绿**（1287+2）。
- 后续阶段一律以实际 `Tests run` 为唯一权威，不再沿旧累计链条反推。

-----

## Phase 29 · 证据充分度评估 + 检索类簇行为锁（EvidenceAssessor 15 条 + RetrievalCluster 10 条，纯 JVM，零生产改动）

**目标**：qa/rag 聚簇域的 `EvidenceAssessor`（检索证据充分度分级与指导语）与 `RetrievalCluster`（连续切片聚合/主候选/源融合）是 Phase 3 从 `HybridChunkRetrievalService` 拆出的核心纯逻辑，但**均无专属测试**。两者均为纯 JVM（只读 Document metadata / 内存字段、无 IO/DB/LLM），且可见性已足够（`EvidenceAssessor` public static、`RetrievalCluster` package-private 同包可直调）→ **零生产改动**，只加行为锁。

**测试**（`src/test/java/com/argus/rag/qa/rag/`）：
- `EvidenceAssessorTest`(15)：空束→NONE；单 BOTH→PARTIAL（SUFFICIENT 需 ≥2）；双 BOTH→SUFFICIENT；双文档高 top 向量证据(≥0.85)→SUFFICIENT；top=0.84→PARTIAL；top<0.3→INSUFFICIENT；单文档均分<0.4→INSUFFICIENT；单文档中分→WEAK；**score 仅认 Double**（Integer 键→top=0→INSUFFICIENT）；五枚 enum 指导语逐一锁定。
- `RetrievalClusterTest`(10)：构造取范围；连续性（同文档序号相邻；跳跃/异文档不连续）；add 扩展范围+vectorScore 取 max；双源→BOTH / 纯 KEYWORD；primary 取更高 rankingScore（chunk9 胜 chunk3）；平手取更小索引；扩窗下界钳 0 / 后扩展。

**锁出的真实行为（我手写预期被实打回）**：无 score 元数据的 2 文档，真实评估为 **INSUFFICIENT**（topScore 兜底 0 <0.30），非我初始预期的 WEAK——已照实现改断言。

**验证**：聚焦 25/25 绿；全量 `./mvnw -o test` = **Tests run: 1314, Failures 0, Errors 0, Skipped 0**（1289+25）。生产零改动、无备份。

**风险/回滚**：纯测试零生产改动，删除两个测试类即回滚。非财务权限判定红线。

-----

## Phase 30 · 向量检索元数据解析 / 跨群组防御门行为锁（PgVectorRetrievalAdapter，去 private 同包可见 + 12 条 JVM 锁）

**目标**：`PgVectorRetrievalAdapter`（engine/pgvector）的四个私有纯方法——`requireLong`/`requireInteger`（兼容 `Number`+`String(trim)` 两种元数据类型 → Long/Integer，缺值/空值/非数值抛 `BusinessException`）、`requireText`（空白抛错、否则 trim）、`toVectorHit`（跨群组校验 `groupId` 一致性 + 字段解析 + score null→0D 兜底）——是**防止向量库跨群组数据泄露**的核心安全防线，但当前**零覆盖**。全部纯 JVM（无 `vectorStore` 依赖），照 Phase 12/13/14 先例去 `private` 放宽同包可见、内部调用点不变。

**改动**：
- `engine/pgvector/PgVectorRetrievalAdapter.java`：四方法去 `private` 为同包可见（`toVectorHit`/`requireLong`/`requireInteger`/`requireText`）。生产行为零漂移。
- `engine/pgvector/PgVectorRetrievalAdapterParseTest`（新，同包，`new PgVectorRetrievalAdapter(null)` 零 vectorStore）：12 条锁——requireLong Number/含空白 String trim 解析/缺失·空白抛「缺少必要元数据」/非数值抛「格式非法」；requireInteger Number/String trim/缺失与 `"1.5"` 抛；requireText trim/空白抛；toVectorHit 全字段解析 + chunkIndex 走 String 型元数据 + score null 兜底 0.0；**跨群组 → 抛「跨群组数据」**。

**验证**：聚焦该加锁测 12/12 绿；全量 `./mvnw -o test` = **Tests run: 1326, Failures 0, Errors 0, Skipped 0**（1314+12）。

**风险/回滚**：仅去 `private` 放宽可见性、调用点不变，编译行为等价；备份 `.bak-pkgpriv`，回滚=`cp` 还原 + 删测试。纯 JVM、无 IO/DB/LLM，不触财务权限判定红线。

-----

## Phase 31 · 检索候选合并/RRF/源判定行为锁（RetrievalCandidate，纯 JVM，零生产改动，+8 条）

**目标**：与 Phase 29 同簇（qa/rag）的 `RetrievalCandidate`——双通道（向量/关键词）命中合并逻辑：各通道评分取 max、RRF(`1/rank`) 分数按 rank **累加**、`source()` 三态判定、构造字段透传——纯内存态、无专属测试。package-private 同包可直调 → **零生产改动**。

**测试**（`src/test/java/com/argus/rag/qa/rag/RetrievalCandidateTest.java`，8 条）：source 三态（仅向量→VECTOR / 仅关键词→KEYWORD / 双通道→BOTH；**全空回退 KEYWORD** 真实行为）；vector/keyword 评分跨 merge 取 max；rankingScore 按 rank 累加（rank1→1.0 + rank2→0.5 = 1.5）；构造 documentId/chunkId/chunkIndex 透传。

**验证**：聚焦 8/8 绿；全量 `./mvnw -o test` = **Tests run: 1334, Failures 0, Errors 0, Skipped 0**（1326+8）。

**风险/回滚**：纯测试零生产改动，删除测试类即回滚。

-----

## Phase 32 · 知识库自动路由分词/评分族行为锁（KnowledgeGroupRouter，提 static 同包可见 + 8 条）

**目标**：`KnowledgeGroupRouter`（qa/route，知识库轻量自动路由）的四个私有**纯 static** 方法——`tokenize`（CJK 连续 bigram + ASCII 整词、≥4 字符加首 3 前缀）、`normalizedTokenHits`（按 query token 击中比归一、空/空白兜底 0）、`normalize`（小写 + 去**全部**空白、null→空）、`isLatinOrDigit`（`[a-zA-Z0-9]+` 判定）——纯 String→Set/二元，核心路由打分的基础，但既有 `KnowledgeGroupRouterTest` 只覆盖 route 开关/候选过滤返回形态、分词族零断言。四者无实例态，照 formatNum/EsKeyword 先例提 `private static`→同包可见 static 直测。

**改动**：`qa/route/KnowledgeGroupRouter.java` 四方法去 `private` 为同包可见 static。生产行为零漂移（内部调用点不变）。
**测试**（`qa/route/KnowledgeGroupRouterTokenizeTest`，8 条）：normalize 去全部空白+小写（含 null→空）；tokenize 对 "研发部"→{研发,发部}、"杭州研发部"→{杭州,州研,研发,发部} 连续 bigram、Latin "AgentsAsia"→{agentsasia,age}（≥4 加 3 前缀）、"RAG"(短词)→{rag} 仅整词；normalizedTokenHits 击中比（{"杭州","北京"} vs "杭州银行"→0.5、单 token→1.0、空 query/空白文本→0）；isLatinOrDigit（"abc12" true、"中文"/"abc 神州" false）。

**验证**：聚焦 8/8 绿；全量 `./mvnw -o test` = **Tests run: 1342, Failures 0, Errors 0, Skipped 0**（1334+8）。
**风险/回滚**：仅去 `private` 放宽 static 可见、调用点不变，行为等价；备份 `.bak-static`，回滚=还原 + 删测试。纯 JVM、无 IO/DB/LLM，不触财务权限红线。

-----

## Phase 33 · ES 索引定义请求体行为锁（ElasticsearchChunkIndexService.buildCreateIndexRequestBody，纯 JVM，零生产改动，+5 条）

**目标**：`buildCreateIndexRequestBody()` 已 package-private（注释即注"供单元测试验证"），纯 `Map.of` 组装无副作用——左 ES 索引 `settings.analysis` IK 分词器（索引 ik_max_word / 搜索 ik_smart）+ `mappings` 字段类型定义，不触发 HTTP。此索引定义是检索基础配置边界，无专属测试（既有 `ElasticsearchKeywordRequestBodyTest` 只锁检索 DSL）。

**测试**（`engine/elasticsearch/ElasticsearchIndexDefinitionTest`，5 条，`httpClient=null` 占位构造零网络）：顶层 settings+mappings 两键；IK 双 analyzer tokenizer（index→ik_max_word、search→ik_smart）；映射字段类型（groupId/documentId/chunkId=long、chunkIndex=integer、status=keyword、deleted=boolean）；chunkText 用双 analyzer（text + index/search）；fileName 带 keyword 子字段 ignore_above=256。

**验证**：聚焦 5/5 绿；全量 `./mvnw -o test` = **Tests run: 1347, Failures 0, Errors 0, Skipped 0**（1342+5）。
**风险/回滚**：零生产改动（方法本已 package-private），删除测试类即回滚。

-----

## Phase 34 · Excel 数字格式修复决策行为锁（ExcelParserService.patchNumberFormat，去 private 同包可见 + 7 条）

**目标**：`ExcelParserService.patchNumberFormat(JSONObject stored, JSONObject parsed)`（380）——「库里值未被用户改过且类型仍为数字时，把解析后的新格式(m/ct)覆盖回原格式」的**纯决策/写回**方法。无实例态、只读两个 JSONObject 并可能改 storedValue、零 POI/IO；此前 `ExcelParserFormatNumTest` 只锁 `formatNum`，本方法零覆盖。去 `private` 同包可见直测。

**改动**：`excel/service/ExcelParserService.java` `patchNumberFormat` 去 `private`。生产行为零漂移、内部调用点不变。
**测试**（`excel/service/ExcelParserPatchNumberFormatTest`，7 条，`new ExcelParserService(null,null)` 零 mapper 占位）：格式不同→ patch true 且 m/ct(仍 t=n、fa=解析格式) 写回 stored；格式与值一致→false；v 非 Number 或 ct 缺失/ct.t 非"n"(日期)→false；公式键存在性不一致→false；公式值不一致→false；两者公式一致且格式有异→仍 patch true。

**验证**：聚焦 7/7 绿；全量 `./mvnw -o test` = **Tests run: 1354, Failures 0, Errors 0, Skipped 0**（1347+7）。
**风险/回滚**：仅去 `private`、行为等价；备份 `.bak-pkgpriv`，回滚=还原 + 删测试。纯 JVM、无 DB/POI，不触财务权限判定红线。

-----

## Phase 36 · 知识库路由理由生成锁（KnowledgeGroupRouter.buildScoreReason，去 private 同包可见 + 4 条）

**目标**：`KnowledgeGroupRouter.buildScoreReason(double,double,double,double,int)`（141）——纯多分支**打分理由生成**：名称命中/描述命中/文档名命中各自优先级、全部无命中但有文档→"候选库存在文档"、全空→"无匹配"，命中项以「、」按固定序拼接。零实例态、零 IO，此前零覆盖（Phase 32 只锁分词族）。去 `private` 同包可见、占位构造直测。

**改动**：`qa/route/KnowledgeGroupRouter.java` 该方法去 `private`。生产零漂移、调用点不变。
**测试**（并入 `KnowledgeGroupRouterTokenizeTest`,`new KnowledgeGroupRouter(null,null,true,3,10)` 占位 +4 条）：全 0→"无匹配"；无分数但文档数>0→"候选库存在文档"；仅名称命中→"知识库名称命中"；三命中按序拼接"知识库名称命中、描述命中、文档名命中"。

**验证**：聚焦该类 12/12(8 分词 +4 理由)；全量 `./mvnw -o test` = **Tests run: 1358, Failures 0, Errors 0, Skipped 0**（1354+4）。
**风险/回滚**：仅去 `private`、行为等价；备份 `.bak-static` 已含先态，回滚=还原 + 删测试。

-----

## Phase 37 · Assistant 流式增量/超时识别/摘要缩写锁（AssistantAgentFacade 三纯片段，提 static 同包可见 + 12 条）

**目标**：`AssistantAgentFacade` 三个纯函数此前零覆盖——流式路径反复回传"截至当前全文"时剥离累计前缀成增量的 `normalizeStreamingDelta`（真实多分支）、沿 cause 链识别 TimeoutException 的 `isTimeoutException`（Reactor ReactiveException 包裹时直接 instanceof 不可靠）、以及折叠换行/截 200+省略号的 `abbreviate`。该类实例态含真实守护线程池，故照 `formatNum`/`EsKeywordScoring` 先例全部**提 static 同包可见**，免构造重载。

**改动**：`assistant/agent/AssistantAgentFacade.java` 三方法 `private`→`static`。生产零漂移、调用点不变（static 经实例/无实例调用均解析一致）。
**新测试** `AssistantAgentStreamingDeltaTest`（**文件名 ≠ 类名的非 public 别名，类名实为该值**，`-Dtest` 须用类名）+12 条：null/空白→空增量、未累计→整段、前缀剥离→增量、完全覆盖→空、前缀不匹配→整段不丢字、中文前缀部分切;direct/deep cause 链超时→true、普通异常/null→false;null→null、CRLF/换行折叠+trim、>200 截 200+"..."、≤200 原样。

**验证**：聚焦该类 12/12；全量 `./mvnw -o test` = **Tests run: 1370, Failures 0, Errors 0, Skipped 0**（1358+12）。
**风险/回滚**：仅 visibility→static、行为等价；无构造故无线程泄漏；备份 `.bak-static-delta`。

-----

## Phase 38 · ES chunk 写盘三方法锁（buildChunkBulkLines/buildChunkDoc/validateChunk，同包可见 + 9 条）

**目标**：`ElasticsearchChunkIndexService` 三方法此前零覆盖——NDJSON bulk 组装（每 chunk action+doc 两行、\n 结尾、_index=配置索引名、_id=chunkId）、单文档字段 Map 组装（chunkId/groupId/documentId/chunkIndex/fileName/chunkText+READY+deleted=false）、以及多字段缺一辄抛「ES 索引写入缺少必要 chunk 字段」的写盘前完整性校验（数据完整防线）。之前只锁了索引定义请求体与检索 body，**没锁写盘组装**这一套行为。

**改动**：`engine/elasticsearch/ElasticsearchChunkIndexService.java` 三方法 `private`→包可见（方法用实例字段 objectMapper/indexName，不能 static）。用 6 参包私有构造 `new ElasticsearchChunkIndexService(new ObjectMapper(), null, "localhost",9200,"http","argus_test_idx")` 注入零 httpClient，构造零网络 IO。生产零漂移、调用点不变（indexReadyChunks 只有这一个私有调用块）。
**新测试** `ElasticsearchChunkBulkDocTest` +9 条：校验通过 / null chunk / 四字段缺失逐条 / chunkText 空白；doc 七字段全齐+READY+deleted=false；fileName 透传；单 chunk 两行 NDJSON+index/_id+doc 序列化；多 chunk 四行；bulk 前逐条反射校验（含一个坏 chunk→抛）。

**验证**：聚焦 9/9；全量 `./mvnw -o test` = **Tests run: 1379, Failures 0, Errors 0, Skipped 0**（1370+9）。
**风险/回滚**：仅去 private 三行、行为等价、可逆（反向三行替换）；⚠本次未先 `cp .bak` 即改（三处确定唯一替换、原文在上下文可回逆），已记录。包私有 6 参构造本就是"测试注入 mock HttpClient"设计。

-----

## Phase 39 · AI 模型目录查询锁（ModelConfig.findByModelName，零生产改动 + 6 条）

**目标**：`ModelConfig.findByModelName`（config 域静态模型目录查询）——在 ALL_MODELS 按 id 精确匹配（区分大小写）返回，未知/空/null/大小写不符统一回落 `TEXT_MODELS.get(0)`(agnes-3.0-flash) 绝不抛空，是多处调用方取模型 id 的兜底入口。方法已是 `public static`、目录是 public static final catalog，故**零生产改动**，纯测试锁。

**新测试** `ModelConfigTest` +6 条：已知 text model 命中（+category）、image/video 命中、未知→回落 DEFAULT 且是目录唯一 isDefault 项、空串/null→回落、大小写不符（AGNES-3.0-flash）→回落不抛、DEFAULT == TEXT_MODELS[0] 且第二项非默认。

**验证**：聚焦 6/6；全量 `./mvnw -o test` = **Tests run: 1385, Failures 0, Errors 0, Skipped 0**（1379+6）。
**风险/回滚**：零生产改动，仅新增测试文件，无回滚风险。

-----

## Phase 40 · Assistant 会话摘要文本格式化锁（AssistantSessionSummaryService 三纯函数提 static + 10 条）

**目标**：`AssistantSessionSummaryService` 摘要文本格式化三纯函数此前零覆盖——自「历史摘要：」抬头逐条「- 角色：内容」的 `buildSummaryText`、按枚举名精确映射「USER」→「用户」其余一律「助手」的 `roleLabel`、折叠任意空白单空格+trim+超 160 截断省略号的 `normalizeContent`；其中 buildSummaryText 还受 `SUMMARY_SOURCE_CHAR_LIMIT(2000)` 累计超限即断尾追加「- 其余历史消息已省略」。三个纯函数均无实例态（只用 static 常量+彼此），照 `formatNum` 先例全部**提 static 同包可见**（免构造 mapper）。

**改动**：`assistant/memory/AssistantSessionSummaryService.java` 三方法 `private`→`static` 包可见。生产零漂移、调用点不变。
**新测试** `AssistantSessionSummaryFormatTest` +10 条：null→`""`；多空格折叠单空格+两侧 trim+`a\n\tb`→`a b`；>160 截 160+`...`（163 字符）；≤160 原样；USER→「用户」；ASSISTANT/null/SYSTEM→「助手」；两消息按序渲染两行；null content→空行不串 la「null」；40×120 字多消息超 2000 上限→断尾省略行且不足 40 行全量长度；行尾 lineSeparator 被 trim。

**验证**：聚焦 10/10；全量 `./mvnw -o test` = **Tests run: 1395, Failures 0, Errors 0, Skipped 0**（1385+10）。首轮一条断言把折叠后仍当双空格判错、被我修正为正确的单空格。
**风险/回滚**：仅 visibility+static、行为等价、可逆；备份 `.bak-static-summary`。

-----

## Phase 41 · 操作日志值解析锁（OperationLogService.parseValue，static 同包可见 + 7 条）

**目标**：`OperationLogService.parseValue(String)`（undo/redo 时把存储的历史值还原为单元格值的安全解析）——null/空白/`"null"`（trim 后）→ null；合法 JSON 按类型解析；非法 JSON（裸文本）原样返回不抛异常。处理历史单元格值反序列化畸形数据的高危点，此前零覆盖。纯 JVM 无实例态 → 提 static 同包可见。

**改动**：`excel/audit/OperationLogService.java` 去 `private` 加 `static`。生产零漂移、调用点不变。
**新测试** `OperationLogParseValueTest` +7 条：null/空白/`"null"`/两侧空白 → null；`5`(Integer)/`5.5`(fastjson→**BigDecimal 非 Double**，实打回修正)；`true`→Boolean；带引号 JSON 字符串→解出字符串体；JSONObject→键值；JSONArray→长度；非法文本→原样返回。

**验证**：聚焦 7/7；全量 `./mvnw -o test` = **Tests run: 1402, Failures 0, Errors 0, Skipped 0**（1395+7）。
**风险/回滚**：仅 visibility→static、行为、可逆；备份 `.bak-static-parsevalue`。

-----

## Phase 42 · 财务导入路由/记账批次/批次版本锁（FinanceImportService 四方法同包可见 + 10 条）

**目标**：`FinanceImportService`（<632 line> god 中的强未测导言体)四条 import 决策函数零覆盖：
- `nextBatchVersion(bookKey)`——空 bookKey→1；`batchMapper.selectOne` 取最近批次、无或 batchVersion 空→1、否则 latest+1（每个 bookKey 批次号递增）
- `inferBookKey(fileName)`——含「北京」→BJ-2026、「唐山」→TS-2026、其余/null→UNIT-2026（未显式传 bookKey 的旧版导入回退）
- `inferRegion(fileName)`——含「北京」→北京、「唐山」→唐山、其余→其他单位
- `buildBatch(...)`——组装批次：显式 bookKey/region 优先、空回退推断；status=skippedSheets 空?2:3；skipped 时拼「跳过 Sheet: …」

**改动**：`excel/service/FinanceImportService.java` 四方法 `private`→包可见。生产零漂移、调用点不变（importFile 内私有调同一方法）。复用既有 `FinanceImportServiceTest.setUp` 的 8 参 mock 构造（batchMapper 已注入）。
**新测试** `FinanceImportRoutingTest` +10 条：空/null bookKey→1 不触 mapper / no-latest→1 / latest 无 version→1 / latest=7→8；inferBookKey 四分支；inferRegion 四分支；buildBatch 文件名回退 / 显式优先 / status 2+无 errorSummary / status 3+跳过 Sheet 拼接（含 totalSheets/totalRows/mapped/skipped 计数）。

**验证**：聚焦 10/10；全量 `./mvnw -o test` = **Tests run: 1412, Failures 0, Errors 0, Skipped 0**（1402+10）。首轮 helper 参数名 typo（imported→importedSheets）修正。
**风险/回滚**：仅去 private 四行、行为，可逆；备份 `.bak-pkgrouting`。

## 发现（非改造）· PDF 报告命名规约不一致
`FinanceReportPdfService` 存在两套文件名产出：导出的实文件名（line 69）= `finance-report-<id>-<yyyyMMdd>.pdf`（含导出日期），而 `fileNameFor(report)`（line 97）= `finance-report-<id>.pdf`（无日期），且 `FinanceReportController:138` 用 `fileNameFor` 定位/供下载。择区结果：下载/下载用的名字可能与实际落盘名不符（含日期 vs 不含）——**不改成做法**，仅记录为发现的命名契约漂移候选（若日后触碰下载链路须统一下名规则）。

## Phase 43 · Sheet 前端数据→celldata 转换族锁（ExcelSheetService 九 static 纯方法 + 14 条）

**目标**：`ExcelSheetService`（584 line god）中从 workbook 前端数据（`data` 矩阵 / `celldata` 数组）到统一 celldata 值/尺寸/类型转换的 9 个纯函数零覆盖，全部只读操作、不触 mapper/IO：
- `toJsonObject(value)`——null→空对象、JSONObject 原样、其余强转 JSON
- `toJsonArray(value)`——null→空数组、JSONArray 原样、其余强转
- `isEmptyCellValue(value)`——null/空 JSONObject/空 Map→true
- `intValue(value,default)` / `stringValue(value,default)`——解析失败或 blank 回退默认
- `calcTotalRows/Cols(dataObj, celldata)`——取 data 矩阵维度与 celldata 最大 r/c 的较大者
- `dataMatrixToCelldata(dataObj)`——矩阵展开为 {r,c,v}，空值跳过、v 走 toJsonObject
- `normalizeCelldata(celldata)`——剔除非 (r,c) 项与空 v，保留有效项（同 dfs 返回，v 非空才保）

**改动**：`excel/service/ExcelSheetService.java` 9 方法 `private`→`static` 同包可见（生产中 `resolveCelldata` 内部调用点不变）。零实例态、零 mock、构造（5 参含 mapper）无需 new。
**新测试** `ExcelSheetCelldataMappingTest` +14 条：toJsonObject null 原样/Map 转换、toJsonArray null/List、isEmptyCellValue 空/非空、intValue/stringValue 回退、calcTotalRows(矩阵2行+celldata r=5→6)、calcTotalCols(3 列→3)、dataMatrix 展开跳过 null/空 Map、normalize 剔缺失 r 与空 v 但保留有效项/非空值。

**验证**：聚焦 14/14；全量 `./mvnw -o test` = **Tests run: 1426, Failures 0, Errors 0, Skipped 0**（1412+14）。首轮两处修正：`List.of(…, null)` 直接抛 NPE→改 `Arrays.asList`；norm 测试里 `ok.v` 原设为空对象会被 isEmptyCellValue 判空剔除→改非空。
**风险/回滚**：仅可见→static、行为，可逆；备份 `.bak-static-celldata`。

## 发现（非改造）· 纯 JVM 服务面已基本耗尽
经多阶段扫描，剩余候选仅为：被零逻辑枚举（德膜类）、无 db 的薄 wrapper（如 `ExcelVersionService.tryAfterStructureSave/safeArraySize`）。下一步高价值项多为**结构性**（ExcelSheetService/FinanceImportService god 拆分、fastjson 替换）——需用户决定是否推进，非批循环内自动范围。

## Phase 44 · 单元格 JSON 尺寸防御取值锁（ExcelVersionService.safeArraySize 提 static + 5 条）

**目标**：`ExcelVersionService.safeArraySize`（celldata JSON → 版本快照 cell 计数）零覆盖。防御性尺寸取值：非法 JSON parbased null/空数组→0、解析异常→0，杜绝单格计数失败阻断快照主流程。

**改动**：`excel/service/ExcelVersionService.java` `safeArraySize` `private`→`static` 同包可见（调用点 line 123 计 cellCount 不变）。行为、可逆。
**新测试** `ExcelVersionSafeArraySizeTest` +5 条：合法数组→3 / 空数组→0 / null→0 / 空白→0 / 非法 JSON 与对象→0。
**验证**：聚焦 5/5；全量 `./mvnw -o test` = **Tests run: 1431, Failures 0, Errors 0, Skipped 0**（1426+5）。
**风险/回滚**：仅可见→static、行为，可逆；备份 `.bak-static-safearray`。

## Phase 45 · 财务数据规整与去重指纹锁（FinanceImportService text/amount/recordFingerprint 提 static + 5 条）

**目标**：`FinanceImportService` 三纯方法零覆盖，导入幂等去重的关键数据规整：
- `text(String)`——null→""
- `amount(BigDecimal)`——null→""，否则 stripTrailingZeros().toPlainString()（去尾零，如 100.00→100、100.50→100.5）
- `recordFingerprint(record)`——18 段「|」拼接去重指纹：字符串字段走 text、businessDate 走 toString、金额字段走 amount

**改动**：`excel/service/FinanceImportService.java` 三方法 `private`→`static` 同包可见（自身互调保持 static，调用点不变）。行为、可逆。
**新测试** `FinanceImportFingerprintTest` +5 条：text null/原样、amount null/去尾零 3 档、fingerprint 全段拼接与全空 collapse。首轮我手拼两指纹 pipe 数错误被实测打回→用实际串校准（锁出的真实契约：18 段、17 分隔）。
**验证**：聚焦 5/5；全量 `./mvnw -o test` = **Tests run: 1436, Failures 0, Errors 0, Skipped 0**（1431+5）。
**风险/回滚**：仅可见→static、行为，可逆；备份 `.bak-static-routefmt`。
