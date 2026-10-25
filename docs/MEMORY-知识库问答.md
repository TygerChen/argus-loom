# Argus-Loom 知识库问答 · 记忆文档

> 本文档是「知识库问答（RAG）核心」的专属记忆速查，聚焦问答全链路与数据摄入链路，供人快速回忆与 AI 会话冷启动使用。
> Excel 协作 / 财务模块不在本文档范围（全项目速查见根目录 `MEMORY.md`）。
> 项目根目录：`F:\ChatGPT\argus-loom` · 后端端口 10001 · 前端 8081 · Spring profile：`local`

---

## 1. 项目定位与模块边界

**一句话**：团队知识库问答平台 —— 上传 PDF/Word/MD/txt → 自动 ETL 切片向量化 → 混合检索 + LLM 生成带引用的回答；另带一个复用同一检索能力的 AI 助手 Agent。

| 模块 | 路径 | 角色 |
|---|---|---|
| **qa** ★核心 | `backend/src/main/java/com/argus/rag/qa/` | 问答控制器、混合检索、证据评估、引用、跨群路由 |
| **ingestion** ★核心 | `.../rag/ingestion/` | ETL 流水线：解析→清洗→切片→向量+ES 索引 |
| **document** ★核心 | `.../rag/document/` | 上传（直传/分片/秒传）、预览下载、角色门槛 |
| **engine** ★核心 | `.../rag/engine/` | 基础设施适配：pgvector / ES / MinIO / rerank / web 搜索 |
| **assistant**（进阶） | `.../rag/assistant/` | AI 助手 Agent（React 模式）、会话、短期/长期记忆 |
| **prompt** | `.../rag/prompt/` | 提示模板管理（`resources/prompts/*.yml` + `.st`） |
| **group** | `.../rag/group/` | 知识库隔离单元 = 群组（成员/邀请/入组申请） |
| **metrics** | `.../rag/metrics/` | LLM token/费用/延迟计量 |
| excel / finance / auth / user | （忽略） | 在线表格协同、财务工作台、JWT 认证、用户管理 |

---

## 2. AI 技术栈速查

| 项 | 值 | 依据 |
|---|---|---|
| 框架 | **Spring AI BOM 1.1.2 + Spring AI Alibaba BOM 1.1.2.0**（无 LangChain 编排层） | `backend/pom.xml:35-44` |
| Agent 引擎 | `spring-ai-alibaba-agent-framework`（`com.alibaba.cloud.ai.graph` 图执行，ReactAgent） | `pom.xml:229-231` |
| Embedding | **本地 bge-small-zh，512 维**（langchain4j-embeddings 0.36.0，JVM 内 ONNX，离线免 key），`@Primary` 包装成 Spring AI EmbeddingModel | `embedding/LocalEmbeddingConfig.java:26-58` |
| Chat 模型 | `agnes-3.0-flash`，OpenAI 兼容协议（local 指向 `http://127.0.0.1:11435`，dev 指向 `https://api.agnes-ai.cn`） | `application-local.yml` |
| 向量库 | **PostgreSQL + pgvector**：`VECTOR(512)`、HNSW（m=16, ef=200）、cosine_distance | `V1__init_core_schema.sql:444`、`application-local.yml` |
| 关键词检索 | **Elasticsearch 8**，索引 `argus_rag_document_chunks`，IK 中文分词，bool 初排 + rescore 精排 | `engine/elasticsearch/` |
| 对象存储 | MinIO，bucket `argus-rag-documents` | `engine/storage/MinioStorageService.java` |
| ⚠️ Redis | **不存在真实 Redis**。`RedisChunkCacheService` 是进程内有界 LRU+TTL 缓存（excel 模块用），pom 无 redis 依赖 | `excel/service/RedisChunkCacheService.java:19` |
| 异步 | Spring Event + `@Async` 线程池（`common/config/AppThreadPoolConfig`），无 MQ | — |
| 重试 | Spring Retry `@Retryable/@Recover` | — |

---

## 3. 主链路一：文档摄入 ETL（上传 → 可检索）

```
上传入口 DocumentController  [document/controller/DocumentController.java]
  POST /api/documents/upload            直传 ≤10MB      (:161)
  POST /api/documents/upload/init       分片初始化(SHA-256 秒传) (:92)
  PUT  /api/documents/upload/chunks     分片 PUT        (:110)
  POST /api/documents/upload/{id}/complete  ≤256MB      (:143)
    ▼ DocumentUploadService.uploadDocument (:530)
  UploadInputGuard 校验（仅 txt/md/pdf/docx）→ MinIO 上传
    ▼ UploadedDocumentFinalizer.finalizeUploaded (:49)
  落库 documents(status=PROCESSING) + 发布 DocumentIngestionRequestedEvent
    ▼ DocumentIngestionAsyncListener (:46)  @Async + @TransactionalEventListener(AFTER_COMMIT)
      ——事务提交后才触发，避免读到未提交数据——
    ▼ DocumentIngestionAsyncService (:94)  @Retryable(max=3, 2s/4s/8s) + @Recover 标记 FAILED
    ▼ EtlDocumentIngestionProcessor (:121)  —— ETL 主体 ——
  ① StoredObjectDocumentReader   从 MinIO 下载
  ② DocumentParserFactory (:48)  工厂+策略：Txt/Md（编码探测）/Pdf(PDFBox)/Docx(POI)
  ③ TextCleanupTransformer       清洗控制字符、保护代码块
  ④ StructureAwareChunkTransformer  ★结构感知切片（strategy=structure-aware-token-budget-v1）
       优先级：MD标题(跳过代码块内#) → 空行段落 → 中英句子(。！？；!?) → 硬截断
       相邻贪心合并至 target；重叠=从块首回退 overlapTokens×charsPerToken，不越章节起点
  ⑤ ChunkService.saveChunkDocuments (:69)  切片落 document_chunks 表
  ⑥ VectorIngestionService.ingestChunks  写 pgvector
       幂等：先按 documentId 过滤表达式删旧向量；稳定ID=UUID(documentId:chunkIndex)
       批量 add-batch-size=9；metadata: groupId/documentId/chunkId/chunkIndex/fileName
  ⑦ 同步 ES 索引 → 状态置 READY
```

**切片参数**（`application-local.yml` `ingestion.chunking`）：target **240** / max **320** token / overlap **32** / chars-per-token **2**（约目标 480 汉字、上限 640、重叠 64 字）。

---

## 4. 主链路二：问答（★核心中的核心）

```
POST /api/qa/ask（同步） | /api/qa/stream-ask（SSE 流式）
  QaController (:70 / :82)  @RateLimit 20次/60s
    ▼ QaService.ask / askStream (:86 / :150)
  ① 群组权限：GroupMembershipService.requireGroupReadable（非成员直接拒绝）
  ② 流式 .cache() 防重复订阅重复扣费 (:211)
    ▼ QaChatService.askWithUsage / askStream (:158 / :365)  ——「先检索后生成」预取模式——
    ▼ ReadyChunkDocumentRetriever.retrieveEvidence (:95)  实现 Spring AI DocumentRetriever，topK=5
    ▼ HybridChunkRetrievalService.retrieve (:174)  —— 检索编排 ——
  ③ DocumentRoleGate.allowedDocumentIds  ★红线：角色门槛 fail-closed（无可读→返回空，防存在性泄露）
  ④ QueryPlanningService.plan (:62)  LLM 查询规划，四选一：
       DIRECT / REWRITE(改写) / DECOMPOSE(拆2-3子查询) / GLOBAL(全库概览)
       失败回退 DIRECT；最多 3 条查询
  ⑤ 双通道检索（每条 plannedQuery，CHANNEL_TOP_K=50）：
       向量：PgVectorRetrievalAdapter.search (:73)  VectorStore.similaritySearch
             FilterExpression 强制 groupId + 结果二次校验（防跨组泄露 :104）
             ★未设 similarityThreshold（阈值=0）
       关键词：ElasticsearchChunkIndexService.search  IK 分词、多字段加权、log1p 归一化 [0,1]
             ES 挂了降级返回空，不中断
  ⑥ RrfFusion.rankScore = 1/(k+rank)，★k=0（业界常用 k=60）(:32)
       RetrievalCandidate 累加分数，retrievalSource 标 VECTOR/KEYWORD/BOTH
  ⑦ Rerank：RECALL_K=50 → RerankingService.rerank
       ★当前容器唯一实现是 NoOp（均匀分保序，@ConditionalOnMissingBean 兜底）
       架构在、真 Cross-Encoder 模型未接；重排失败回退 RRF 顺序 (:529)
  ⑧ RetrievalCandidateSelector  命中"每个文档/所有文档"→ DOCUMENT_COVERAGE 模式（每文档保底1条）
  ⑨ RetrievalClusterBuilder 聚类（同文档连续切片聚簇）+ 邻居窗口扩展（前后各1切片）
       EvidenceDocumentAssembler.toDocument (:49)：证据文本="文件名：xxx\n窗口拼接"，
       metadata 写 evidenceId(E1,E2…)/双通道分数/hybridScore
  ⑩ EvidenceAssessor.evaluate (:38)  四级证据：NONE/WEAK/PARTIAL/SUFFICIENT
       BOTH 交叉印证 或 topScore≥0.85 → SUFFICIENT
       ★空/不足 → 直接拒答 code=INSUFFICIENT_EVIDENCE，不调 LLM（省钱+防幻觉）
    ▼ Prompt 拼装（双层模板 + Advisor）
  system.st：只能依据证据回答，严格 JSON {answered, answer, reasonCode, reasonMessage}
  user.st：{question} + {evidenceLevel} + {evidenceGuidance}
  RetrievalAugmentationAdvisor [QaChatClientConfiguration.java:110] + rag-context.st
       证据块按 evidenceId 注入；检索结果经 PREFETCHED_DOCUMENTS_CONTEXT_KEY 预取，不重复检索
    ▼ LLM 调用（qaChatClient，OpenAI 兼容）
  同步：.call() → JSON 解析 KnowledgeAnswerOutput（解析失败回退原文，不二次调 LLM）
  流式：.stream() 逐 token Flux，覆盖为纯文本 system prompt，timeout 45s
    ▼ 引用组装 CitationAssembler.assembleDocuments (:30)  按文件名去重
    ▼ 返回 {answered, answer, reasonCode, citations[], evidenceOverview, recordId}
       SSE 事件序：token* → citations → evidence-overview → record
    ▼ QaRecordPersistenceService.saveCompleted  落 qa_records + qa_record_citations（引用快照）
       + LlmUsageCollector（token/费用/延迟入 metrics）
```

**多知识库路由**（AI 助手用）：`QaRetrievalService.retrieveEvidence(List<groupId>)` 逐组检索（每组 top3）→ 跨组公平轮询合并（总 top12）→ 证据等级就低聚合。`KnowledgeGroupRouter` 基于 词汇+CJK bigram 匹配知识库名/描述/文件名打分选库（最多 3 群，不调 LLM）。

**注意**：qa 模块**无会话态**——每次 ask 独立，不携带历史；历史只是 `qa_records` 记录（`QaRecordController` /api/qa/records 分页/详情/评分/删除）。多轮对话能力在 assistant 模块。

---

## 5. 核心表速查（Flyway `V1__init_core_schema.sql`）

| 表 | 行号 | 用途 | 关键字段 |
|---|---|---|---|
| `documents` | :206 | 知识文档 | group_id、file_hash(SHA-256 查重)、storage_bucket/object_key(MinIO)、**status**(UPLOADED/PROCESSING/READY/FAILED)、failure_reason、required_role |
| `document_chunks` | :352 | 切片（检索最小单元） | document_id、group_id(冗余)、chunk_index、chunk_text、char_start/end、metadata_json |
| `vector_store` | :444 | pgvector 向量 | embedding VECTOR(512)、HNSW cosine(m=16,ef=200)、metadata 含 document_id/chunk_index |
| `qa_records` | :625 | 问答记录 | question/answer、evidence_level、citation_count、token 统计、latency、model_name、success |
| `qa_record_citations` | :651 | 引用快照 | qa_record_id、document_id、chunk_id、chunk_index 区间、file_name |
| `ingestion_jobs` | :393 | 摄入任务状态机 | PENDING→…→READY/FAILED、retry |
| `groups` / `group_memberships` | :80 / :109 | 知识库隔离群组 | 检索一律强制 groupId 过滤 |
| `assistant_sessions` / `_messages` / `_contexts` | :469/:499/:532 | 助手会话（进阶） | 短期上下文摘要 |

---

## 6. 关键配置速查

```yaml
# application-local.yml 摘要
spring.ai.model.chat: openai        # OpenAI 兼容协议
spring.ai.model.embedding: local    # 本地 bge-small-zh 512维
spring.ai.openai.base-url: http://127.0.0.1:11435   # local；dev 为 api.agnes-ai.cn
spring.ai.openai.model.chat: agnes-3.0-flash
spring.ai.vectorstore.pgvector: { dimensions: 512, index-type: hnsw, distance-type: cosine_distance }
ingestion.chunking: { target-tokens: 240, max-tokens: 320, overlap-tokens: 32, chars-per-token: 2 }
ingestion.vector.add-batch-size: 9
argus.rag.llm: { timeout-seconds: 45, max-retries: 3 }   # 退避 1s×2^n 最大 8s
argus.rag.qa.model-name: agnes-3.0-flash
kb-routing: { enabled: true, max-groups: 3, per-group-top-k: 3, overall-top-k: 12 }
youcom.search.enabled: false        # 付费网络搜索通道，默认关
web-search.enabled: true            # Bing 免 key 元搜索（assistant WebSearchTool 用）
spring.ai.dashscope.enabled: false  # 阿里原生接入保留但关闭
```

---

## 7. 事实澄清与易错点（面试/答疑必备）

1. **没有真实 Redis**：`RedisChunkCacheService` 名字有迷惑性，实为进程内有界 LRU+TTL（excel 分块缓存）。pom.xml 无 redis 依赖。根目录 MEMORY.md 旧版描述已修正。
2. **重排是"空转"的**：`RerankingService` 接口 + 责任链位置都在，但唯一实现是 `RerankingService.NoOp`（均匀分保序）。接真模型的正确姿势：实现接口注册 Bean，`@ConditionalOnMissingBean` 自动让位（见教程实验一）。
3. **RRF k=0**：公式 `1/(k+rank)` 中 k=0，即纯排名倒数和；业界论文常用 k=60 抑制头部通吃。本项目通道分数已归一化，k=0 是有意选择。
4. **无相似度阈值**：pgvector 检索未配 `similarityThreshold`（=0），低质量召回交给证据评估层兜底拒答，而不是检索层截断。
5. **qa 无会话态**：每问独立；别把 assistant 的会话/记忆体系和 qa 混为一谈。
6. **拒答发生在调 LLM 之前**：证据 NONE/不足 → 直接 `INSUFFICIENT_EVIDENCE`，省 token 且防幻觉——这是证据分级的设计意义。
7. **异步时序**：ETL 由 `@TransactionalEventListener(AFTER_COMMIT)` 触发——事件监听必须等事务提交，否则监听器读不到文档行。
8. **引用不在答案文本里**：答案正文不写 [E1] 角标；引用由后端 `CitationAssembler` 从证据 metadata 独立组装返回前端。
9. **双保险防跨组泄露**：pgvector FilterExpression 强制 groupId + Java 层结果二次校验。

---

## 8. 红线（不可违反）

- `DocumentRoleGate.allowedDocumentIds` 权限判定**不得改动**（fail-closed，有测试锁定）。
- 所有响应包 `ApiResponse{success,data,message}`；SSE 流除外。
- 新增异步任务复用 `AppThreadPoolConfig` 现有池（`retrievalExecutor` 等），禁用公共 ForkJoinPool。
- Vite 代理对 SSE 已去缓冲，新增 SSE 接口保持该配置。
- LLM/超时/WS 配置统一挂 `argus.rag.*` 键层级（`ApplicationConfigBindingTest` 锁定）。

---

## 9. 学习资源索引

**教程文章**（本项目学习路径的两大依据）：
- [AI 时代学习开源项目的正确姿势](https://www.codefather.cn/post/2103044523826475009) —— 方法论：Architecture First、SOP B 核心链路追踪、What-If 反事实推演
- [我完成了一个最新的 RAG 项目](https://www.codefather.cn/post/2056275623579504642) —— **本项目自身的开发史**：V1 认证 → V2 文档 ETL → V3 RAG 问答 → V4 Agent+记忆

**项目内文档**：
- `backend/docs/核心链路-陪读-RAG与AI助手.md` —— 逐行陪读手册（真实类名+行号）
- `backend/docs/新手指南-读代码路线图.md` —— 零基础读码地图
- `backend/docs/权限红线速记-新手向.md` —— 安全红线速记
- `docs/architecture/` —— drawio 架构图（rag-qa-flow、document-ingestion-flow 等）
- `docs/知识库问答-项目学习教程.html` —— ★配套交互式学习教程（方法论+源码+实验+自测）

**企业级 RAG 外部参考**：Atlan《12 Advanced RAG Techniques》、Neo4j《Advanced RAG for high-performance LLM apps》、Dify《Hybrid Search and Rerank》、Prompt Engineering Guide《RAG Guide》
