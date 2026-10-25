# RAG 知识问答 · 超详细分步图

> 面向新手，把「你在群里问一个问题 → 系统先检索证据 → 再让大模型照着证据回答 → 还给你附上引用来源」这条 RAG 主链路，从 HTTP 入口一路走到最深。
> 所有行号都经源码核验（`src/main/java/com/argus/rag/qa/` 下），不含猜测。

---

## 一句话看懂整条链

**提问 → ① 校验你是否有权读这个群 → ② 检索：把大问题拆成几个子查询，同时查"关键词"和"向量语义"，双路命中合并、重排、聚簇 → ③ 凑成几份"证据"(甚至还去网上一并搜，You.com) → ④ 评估证据够不够 → ⑤ 够就把「问题+证据+证据等级」拼成 Prompt 丢给大模型 → ⑥ 解析模型 JSON 回答（解析失败就用原文兜底再解一次）→ ⑦ 把 answer/引用/证据概览打包返回，顺手记一条 QA 记录 + LLM 用量和费用。**

**全程有 4 个"拒答闸门"**：没权限、没检索到证据、模型返回格式错、模型主动拒答。每个闸门都返回一个"礼貌的不可回答"，而不是抛 500。

---

## 第 1 步：HTTP 入口（`qa/controller/QaController.java:70-77`）

```
@PostMapping("/ask")              :70     ← POST /api/qa/ask
@RateLimit(limit=20, window=60)  :71     ← 60 秒最多 20 次，防刷
public AskQuestionResponse askQuestion(@Valid @RequestBody AskQuestionRequest r, HttpServletRequest request) {
    return qaService.ask(request, r);    :76   ← 本类只接电话，业务全丢给 QaService
}
```

同文件还有个 `streamAsk`（`:82`）走 SSE 逐 token 推送，流程不同（改由 `askStream`），本文只讲**同步版** `ask`。

---

## 第 2 步：`QaService.ask`（`qa/service/QaService.java:86`）

QaService 是"入口管家"——**不亲自检索，不亲自调大模型**，只做铁三角：权限 → 委托 → 记账。

```
86  public AskQuestionResponse ask(HttpServletRequest request, AskQuestionRequest askQuestionRequest) {
87      Long groupId = askQuestionRequest.getGroupId();
88      groupMembershipService.requireGroupReadable(groupId);         // ① 读权限：不是群成员就抛
89      Long userId = currentUserService.getRequiredCurrentUser().userId(); // 拿当前登录用户
91      QaChatService.AskResult result = qaChatService.askWithUsage(groupId, askQuestionRequest.getQuestion());
                                                                     // ② 核心：检索 + 生成（见第3步）
93      Long recordId = saveQaRecord(userId, groupId, question, QA_ASK, ...); // ③ 落一条 QA 记录
105     recordUsage(userId, groupId, QA_ASK, result.usage(), true, null);    // ④ 记 LLM 用量+花费
107     return result.response().withRecordId(recordId);                 // ⑤ 返回答案(带 recordId)
}
```

- `requireGroupReadable`（`L88`）：走群组成员表，不是成员直接抛权限异常——**你在知识库外的群问，连检索都不会发生**（省一次白跑）。
- `saveQaRecord` 落盘；`recordUsage`（`219-242`）算 `costAmount` 用 `llmCostCalculator.calculate`，再 `llmUsageCollector.record` 上报用量，失败只打 warn 不影响主流程（`L240`）。

---

## 第 3 步：RAG 主链 `QaChatService.askWithUsage`（`qa/service/QaChatService.java:158`）

这是整条 RAG 的核心。顺序 **检索 → 评估 → 拒答闸口 → 调模型 → 解析 → 组装**：

```
158   public AskResult askWithUsage(Long groupId, String question) {
159       Timer.Sample dialogue = dialogueMetrics.start();        // 计时埋点（新老不依赖业务）
162       long startNano = System.nanoTime();
165       RetrievedEvidenceBundle evidenceBundle = documentRetriever.retrieveEvidence(groupId, question);
           //   ↑ ① 检索证据（见第4步）
169       if (documents.isEmpty() || evidenceLevel == INSUFFICIENT) {
               // ② 没检索到任何证据，或证据强度判定为"不足" → 直接拒答，不调大模型
173           return new AskResult(unanswered("INSUFFICIENT_EVIDENCE","检索到的有效证据不足，暂不回答。"), ...);
179       LlmCallResult result = getStructuredAnswer(groupId, question, evidenceBundle);
           //   ↑ ③ 调大模型出结构化回答（见第5步）
181       if (result.output() == null) {                            // 格式解析彻底失败
183           return ...unanswered("ANSWER_FORMAT_ERROR","模型返回格式错误，无法解析回答。")...;
191       if (!output.answered() || 答案空) {                     // 模型主动拒答
195           return ...unanswered(output.reasonCode(), output.reasonMessage())...;
206       return new AskResult(
208           AskQuestionResponse.answered(
209               output.answer().trim(),
210               citationAssembler.assembleDocuments(documents))      // ⑥ 组装引用
                   .withEvidenceOverview(evidenceOverviewAssembler.assemble(documents)),
                   ... , documents);                                     // ⑦ 证据概览
      } catch (RuntimeException e) { failed = true; throw e; }           // 硬失败
      finally { dialogueMetrics.finish(...); }                          // 计时收尾
  }
```

**4 个状态一字排开**（都返回"礼貌拒答"而不是异常）：
| 状态 | 触发 | 返回 code |
|---|---|---|
| 无证据 | `documents` 空 或 `EvidenceLevel.INSUFFICIENT` | `INSUFFICIENT_EVIDENCE` |
| 格式错 | LLM 输出两个解析入口都失败 → `output==null` | `ANSWER_FORMAT_ERROR` |
| 模型拒答 | 模型 `answered()=false` 或答案为空 | 模型给的理由码 |
| 成功 | 全通过 | 正常 answer + references |

> **为什么"没证据"也算一个完整返回而不是报错？** 对一个知识库问答系统，检索肩证不足是**正常业务结果**，不是故障。返回一个带 `INSUFFICIENT_EVIDENCE` code 的空回答，前端就能据此提示"库里没找到相关资料"，而不是弹 500。

---

## 第 4 步：检索证据 —— `ReadyChunkDocumentRetriever` → `HybridChunkRetrievalService`

`retrieveEvidence`（`qa/rag/ReadyChunkDocumentRetriever.java:95`）只做一行：委托
`hybridChunkRetrievalService.retrieve(groupId, question, topK)`（`:96`）。
真正干活的在 **`HybridChunkRetrievalService.retrieve`（`qa/rag/HybridChunkRetrievalService.java:174-296`）**，是一段很长但节奏清晰的大链：

```
:176  validate groupId/question           (正数 / 非空)
:184  检索层角色门槛: 当前角色无可读文档? 是→ 直接返回"无可检索" (防存在性泄露)
:192  queryPlanningService.plan(question)      ← 让浅层先大模型把"大问题"拆成多条子查询
:199  如果 strategy == GLOBAL → retrieveGlobal(整库概览)，不走混合检索
:204~209  for 子查询 in queries:
            mergeVectorHits(...)    ← 向量语义通道(Embedding 相似度)      :207
            mergeKeywordHits(...)   ← 关键词通道(BM25/ES)               :208
            （两个通道命中合并进 candidates，同 chunk 去重取分）
:217  检索层角色过滤: 剔除未授权文档切片               :217-224
:226  候选空 → 返回空 evidence
:232  按 rankingScore 倒序排序
:238  applyReranking(question, sortedCandidates)     ← Cross-Encoder 二次重排打分
:239  selectRankedCandidates(...) 挑 topK（文档覆盖 or 纯相关性）
:245  RetrievalClusterBuilder.buildClusters(rankedCandidates)   ← 同一文档的切片聚成簇
:255~266 对每个簇组装成 Document（带 evidenceId=E1,E2…，读取 READY 切片）
:274  searchWebQueries(queryPlan.queries())   ← 并行去 You.com 搜网页补证据
:277  网页结果也追加为 E 编号的证据
:291  evidenceLevel = EvidenceAssessor.evaluate(documents)   ← 评估证据强度(第4.5)
:295  return RetrievalEvidenceBundle(documents, evidenceLevel, EvidenceAssessor.guidanceFor(evidenceLevel))
```

> **"双路"为什么？** 纯关键词查不到同义词/口语，纯向量查不到精确编号。两条都查再合并，客户更大。"方案"（预取）那份文档：`ReadyChunkDocumentRetriever` 还支持"上下文里已有预取文档就不重查"（`ReadyChunk...:70-74`）。

---

## 第 4.5 步：证据强度评估 —— `EvidenceAssessor`

（`qa/rag/` 包，方法在 `HybridChunkRetrievalService` 调用 `EvidenceAssessor.evaluate(documents)` :291）
根据捞到的文档数量/质量给出 `EvidenceLevel`（`SUFFICIENT / WEAK / INSUFFICIENT / NONE` 等）。

它输出的 `guidanceFor(evidenceLevel)` 会作为 Prompt 里的"证据指导"（第5步里的 `evidenceGuidance` 槽位）——把"证据够了"翻译成模型能听懂的"可以答"，把"证据弱"翻译成"要说明资料有限、不要瞎编"。

---

## 第 5 步：调大模型拿结构化回答 —— `getStructuredAnswer`（`QaChatService.java:230`）

```
230  private LlmCallResult getStructuredAnswer(groupId, question, evidenceBundle) {
234      Prompt userPrompt = createUserPrompt(question, evidenceBundle);   // 建 Prompt
237-244    chatResponse = qaChatClient.prompt(userPrompt)
                .advisors(advisor-> advisor.param("groupId", groupId)
                                          .param(PREFETCHED_DOCUMENTS_CONTEXT_KEY, evidenceBundle.documents()))
                                          ↑  把检索到的证据"喂"给大模型
                          .call().chatResponse();        // 同步调用
245-252  模型调用抛异常 → 归一为 MODEL_UNAVAILABLE 抛 BusinessException
254      usageInfo = extractUsageInfo(usage, false);
256-258   text = response.getText();
          output = objectMapper.readValue(QaAnswerParser.sanitizeJson(text), KnowledgeAnswerOutput.class);
              ↑ 先把模型原始文本"洗"成标准 JSON(sanitizeJson)，再反序列化成结构化对象
260-267  解析失败 → 走 fallbackParse：用首次 LLM 原文再做一次"原文解析"兜底，
         不再额外打一次模型（省一次钱），仍失败则 output=null（上游就返回 FORMAT_ERROR）
```

`createUserPrompt`（`QaChatService.java:481`）：把「`question` 用户问题 + `evidenceLevel` 证据等级 + `evidenceGuidance` 等级对应指令」三个变量填进 `qaUserPromptTemplate` 模板。分工：问题是你问的；等级让模型判断"够不够答"；指令把等级翻译成"绝不超出证据臆测"这类硬约束。

`sanitizeJson`（`qa/support/QaAnswerParser.java:53`）：
```
53  static String sanitizeJson(String) {
54    去掉 ``` 代码围栏(normalize)；          :54
56-60  取第一个 "{" 到最后一个 "}" 之间的子串，剥掉模型前后的附言
```
所以在 `parse`（`:41`，非主链备胎）里也是先 sanitizeJson 再 readValue。

---

## 完整超细图（判定分支全部展开）

```mermaid
flowchart TD
  R0["HTTP POST /api/qa/ask"] --> C1
  C1["QaController.askQuestion :72<br/>RateLimit/@Valid"] --> S1["QaService.ask :86"]
  S1 --> G1{"群组可读?"} -->|否| EL["throw 权限异常(不检索)"]
  S1 --> U1["获取当前用户 :89"]

  S1 --> RAG["QaChatService.askWithUsage :158"]
  RAG --> RET{"QaChatService.askWithUsage :158<br/>计时埋点"}
  RET --> EV0["ReadyChunkDocumentRetriever.retrieveEvidence :95"]
  EV0 --> H1["HybridChunkRetrievalService.retrieve :174"]
  H1 --> H2["角色门槛(fail-closed)"]
  H1 --> H3["QueryPlan 拆子查询 :192"]
  H1 --> H4["每个子查询<br/>mergeVectorHits :207 + mergeKeywordHits :208"]
  H4 --> H5["同 chunk 合并"])
  H5 --> H5b["候选空?"] --> Hc["ReturnedEvidenceBundle.empty :229"]
  H4 --> H6["按 rankingScore 排序 :232"]
  H6 --> H7["applyReranking :238"]
  H7 --> H8["selectRankedCandidates :241"]
  H8 --> H9["buildClusters 跨簇 :245"]
  H9 --> H10["selectQaReadyChunksByIds + 组装 Document :255"]
  H10 --> H11["searchWebQueries(You.com) :274"]
  H11 --> H12["EvidenceAssessor.evaluate :291"]

  RET --> B{"documents 空 或 INSUFFICIENT?"}
  B -->|是| X1["拒答 INSUFFICIENT_EVIDENCE<br/>证：169-178"]
  B -->|否| LLM["getLlmAnswer :230"]
  LLM --> P1["createUserPrompt :481<br/>问题+证据等级+指令"]
  P1 --> P2["ChatClient.prompt().call()<br/>喂 evidence"]
  P2 --> P3{"LLM 调用异常?"}
  P3 -->|是| X2["throw MODEL_UNAVAILABLE :250"]
  P3 --> P4["sanitizeJson :53 → readValue :258"]
  P4 --> P5{"解析失败?"}
  P5 -->|是| P6["fallbackParse: 原文围栏解析 :274"]
  P6 --> P7{"仍失败?"}
  P7 -->|是| X5["output=null → ANSWER_FORMAT_ERROR :182"]
  P4 --> P8{"模型拒答或空?"}
  P8 -->|是| X6["unanswered(reason) :195"]
  P8 -->|否| P9["组装引用+概览 :207"]
  LLM --> P10["记录记录用量 :219"]

  P9 --> OK["AskResult(response, usage, level, evidence)"]
  OK --> S2["saveQaRecord :93"]
  S2 --> S3["recordUsage(用量+费用) :105"]
  S3 --> S4["response.withRecordId(recordId) :107"]
  S4 --> RET2["ApiResponse.success → 前端"]
```

> ⚠️ 图为教学展开（把判定、子步骤拆成独立框，箭头 = 调用/数据流，非严格 1:1 源码嵌套）。真实代码就集中在 `QaService` / `QaChatService` / `HybridChunkRetrievalService` 三个类。简化版结构图见同目录 `QaController.askQuestion.md` 与 `QaChatService.askWithUsage.md`。

---

## 新手三连问，问完即懂 RAG

1. **"为什么先检索，不直接问大模型？"** 因为要"**有据可查**"。模型没读过你私有知识库，直接问=胡编。检索是在你库的 chunk 里找到实打实的原文，模型照原文回答且你能点回引用。
2. **"两个通道为什么要合并？"** 关键词通道(BM25)抓精确术语、向量通道(embedding)抓语义相近；单用任何一个都会漏。合并去重后再经过重排(Cross-Encoder)打分，挑最相关的那几条。
3. **"证据等级有什么意义？"** 不是所有检索都成功。证据弱时就不再硬调大模型(避免模型编造)，直接返回"证据不足、暂不回答"。等级还会写进 Prompt，让模型知道自己该"直接答"还是"说明资料有限"。

- **安全设计**：检索层有角色门槛(fail-closed)，无权文档永远进不了大模型上下文；同时对外统一返回"无可检索信息"而非"无权访问"，杜绝用报错差异打探知识库存在性。

---

_行号核对时间点：2026-09-21。冲突与源码为准。简版结构图见同目录 `QaController.askQuestion.md` / `QaChatService.askWithUsage.md` / `ReadyChunkDocumentRetriever*.md`。_