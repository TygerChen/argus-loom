# 核心链路 · 陪读手册（RAG 问答 + AI 助手记忆）

> 这份是给你（新手）**逐行陪读**用的。每条链路我都把「入口 Controller → Service → 内部步骤」落到**真实类名 + 真实方法 + 真实行号**。
> 读法：先看最上面一张「一句话流程」，再对照下面的「每一步 → 去哪个文件哪一行看」。不用背，按图点开源码跟着走几遍就会了。

---

## 先装一个心智模型

整个系统有**两条完全不同的大脑**：

| 大脑 | 干嘛的 | 入口 | 灵魂方法 |
|---|---|---|---|
| **RAG 问答** | 你对**某个知识库提问**，它检索文档证据再让大模型作答 | `QaController` | `QaChatService.askWithUsage` |
| **AI 助手** | 你和**助手多轮聊**，它带工具、带记忆 | `AssistantAgentFacade.chat` | `AssistantAgentFacade.chat` |

加粗那条记忆线（问题3）是**长在一号 AI 助手之上的**：助手每次说话前，都会先去掏**短期记忆**和**长期记忆**，把它们拼进对话里再回答。所以记忆不是独立系统，是「助手链路上的一个钩子」。

---

# 链 1 · RAG 知识问答主链路

## 一句话流程

```
你问一句
  → QaController.ask (HTTP 入口)
  → QaChatService.askWithUsage
      → ① 检索证据: HybridChunkRetrievalService.retrieve   ← 最核心
      → ② 证据不够? → 直接回「查无相关」
      → ③ 拼 prompt → 调大模型 → 解析成结构化答案
      → ④ 拼引用(哪份文档哪一段) + 证据总览
  → 返回给前端
```

**记住关键词：检索 → 拼证据 → 让模型答 → 带出处返回。**

## 1.0 入口：谁接了你的提问

文件 `qa/service/QaController.java`

- 你调 `/api/qa/ask` → 方法 `ask` 收到问题
- 它转给 `qaService.ask(...)`，把 HTTP 请求变成真正干活的调用

## 1.1 编排老手：QaChatService

文件 `qa/service/QaChatService.java`

- **方法 `ask`**：入口，把 `(groupId, question)` 往下交。
- **方法 `askWithUsage(groupId, question)`**（第 158~221 行）：整条 RAG 链的总指挥，顺序是——
  1. `documentRetriever.retrieveEvidence(groupId, question)` → 拿到 `RetrievedEvidenceBundle`（证据包）
  2. 若证据包为空 / 证据等级是 `INSUFFICIENT`（资料不足）→ 直接返回「无法回答」
  3. 否则 `getStructuredAnswer(...)` → 拼 prompt、调大模型、解析出结构化答案
  4. 成功 → `AskQuestionResponse.answered(answer, citations).withEvidenceOverview(...)`
     - `citations`（引用）由 `CitationAssembler.assembleDocuments(documents)` 生成
     - `evidenceOverview`（证据总览，如 H/VH/…）由 `EvidenceOverviewAssembler` 生成

> 新手标记：你只要看 `askWithUsage` 的 158–221 行怎么写，就看到了整条链子的骨架。它像目录，后面跟着展开。

## 1.2 检索的开关：检索证据怎么来

这里有一个「包装」和「真身」：

- 包装：`qa/rag/ReadyChunkDocumentRetriever.retrieveEvidence(groupId, question)`
  → 它自己没查库，只做了**安全校验 + 命中阈值**，然后把活丢给下面这个真身。
- **真身：`qa/rag/HybridChunkRetrievalService.retrieve(groupId, question, topK)`**（源文件 `.../paired/` 第 174 行）
  → 双路信号：**向量 + 关键词**

### ① 先校验 + 检索层角色门槛（这条是权限，关联你之前读的红线）

`retrieve` 一开始：
- `RetrievalRowGuard.requirePositiveGroupId/requireQuestion`：groupId 非法直接挡住，别浪费计算。
- 用给定角色解析该群组可读的文档集合（`effectiveAllowedDocumentIds`）。
  - 空集合 → 直接返回「知识库没这个信息」**抱住 fail-closed 防泄漏**（这块把"无权"当成"查无此文"）。

### ② 三通道并行检索

方法内第 203–206 行，把 `queryPlan.queries()`（问题改写出的多句话）逐句送进两条苏州市——

```java
mergeVectorHits(candidates, groupId, plannedQuery);   // 向量通道 → 语义
mergeKeywordHits(candidates, groupId, plannedQuery);  // 关键词通道 → 字面
```

- 向量 `search` → 命中 "语义相似" 的片段（你在图解里常看到的 embedding）。
- 关键词命中 → 含有问题字词的片段。
- 两边的命中都会收进 `candidates`（一个按 chunk 维度合并的候选池），并用 **RRF 融合**把两条路的名次合并起来（不会偏科任何一路）。

### ③ 再次用角色门槛过滤 candidate（第 213–215 行）

把「该角色读不到的那部分文档切片」从 `candidates` 里**剔除**，确保受限片段完全不会进上下文、不会进大模型。

### ④ 排序 + 重排（第 229–246 行）

按融合得分排序，接着 **`applyReranking`** 用更精的 Cross-Encoder（一个给「问题 vs 片段」打相关分的模型）给最靠前的一小撮**重新打分**，让真正相关的排前面。

### ⑤ 聚类分组 → 查库拼出文档（第 229–263 行）

`RetrievalClusterBuilder.buildClusters(...)` 把同段落的候选归成组。对每个聚类，再用 `groupDocMapper` 取到完整片段，组合成一条 `Document`（带 `evidenceId`：E1、E2…）、以及按聚类把一个「邻居窗口」扩进去（让上下文更完整）。

### ⑥ 可选的网页结果追加（第 265-284 行） + 证据等级评估（第 288-292 行）

- 补充几条 **You.com 网页检索**并把它们也编号进文档列表。
- 最后 `EvidenceAssessor.evaluate(documents)` 给整批证据打一个**等级**（`SUFFICIENT` / `WEAK` / `INSUFFICIENT`），
  并生成 `guidanceFor(…)** 一句给模型/前端看的「资料够不够」提示。
- 返回 `RetrievedEvidenceBundle(documents, evidenceLevel, guidance)`。

> 停下来对照：**证据包的三个成员（文档、等级、提示）** 恰好是链 1 后面要用的全部。

## 1.3 证据不足怎么办

回到 `QaChatService.askWithUsage`：

- 若 `bundle = empty` 或 `evidenceLevel == INSUFFICIENT`
  → 走 `AskQuestionResponse.unanswered(INSUFFICIENT_CODE, …)`
  → 前端显示「知识库暂时没找到相关的」。

> 设计意图：**不硬编答案**，资料不够就老实说不够，而不是瞎编。

## 1.4 调用大模型 + 解析

证据足够时，`getStructuredAnswer(...)` 做：

1. `createUserPrompt`：把证据拼成一段 prompt（模型看得懂的上下文）。
2. 调 `qaChatClient.prompt(...)`（Spring AI 的 ChatClient）。
3. 大模型回话 → 解析出 JSON → `KnowledgeAnswerOutput`（结构化字段：直接答案 + 置信度等）。
4. 兜底：JSON 解析坏了 → `fallbackParse`，把模型原文直接返回，别让前端白屏。

模型本身不可用 → 抛 `BusinessException(MODEL_UNAVAILABLE)`（这块在 common 里，你有课学过）。

---

## 链 2：AI 助手 + 记忆

## 一句话

助手说话前的每一步，都要**先喂记忆**：短期记忆（这个会话刚才聊过什么）+ 长期记忆（用户过去提过的喜好/背景）。记忆不来自神秘，就藏在一个每次「模型生成之前」就插入上下文的钩子里。

## 2.0 入口：AssistantAgentFacade

```
assistant/agent/AssistantAgentFacade.java
  chat(...)  →  造一个 ReactAgent(Agent 图,带工具)
            →  agent.chat(message, config)   ← 走整个图
```

- 方法 `chat`（第 107 行）：同步非流式对话；`streamChat`（第 200 行）流式同理。
- 它先 `assistantReactAgentFactory.createAgent(...)` 创建一个 **React Agent**——这个 agent 是一个**有向图**（Agent Graph），
  节点之间有「用户消息 → 上下文（记忆） → 模型（可能调工具） → 工具返回 → 再模型 → 输出」的一圈。
- `runnableConfig` 把 `userId / sessionId / toolMode / groupIds` 传下去，因为**记忆钩子正是从这些元数据里读身份**。

## 2.1 记忆在哪发生：AssistantShortTermMemoryHook

文件：`assistant/memory/AssistantShortTermMemoryHook.java`
类继承自 `MessagesHooks`，**它挂在图里 BEFOR_MODEL**——也就是每次让大模型说话前执行。

它的 `beforeModel(...)`（第 60 行）主要做三件事：

1. **判断要不要插手**（第 60-75 行）：如果是空会话 / 缺 userId/sessionId 配置 → 直接透传不动，不画蛇添足。
2. **抽取当前问题** → `currentQuestion`（第 76 行）。
3. **重构上下文**：`assembleBeforeModelMessages(userId, sessionId, ...)`（这是核心，见下节）。

辅助逻辑：
- `hasNonBlankToolResponse(...)`（第 118 行）：如果是工具调用后的那一轮（模型刚让工具跑完），保持标准 tool message 结构原样透传——**不要**自作主张去记忆中重组，免得把"工具给模型的结果"弄丢。

### assembleBeforeModelMessages：到底把什么喂给模型？

它在构造函数里注入了两样记忆原料：
- `MemoryRetriever`（长期记忆检索器）→ 从 `UserMemory` 表按用户检索“长期/印象”
- `MemoryContextBuilder`（把检索结果 + 另一份记忆目录拼成一段合法的文本）

组装顺序（`assembleBeforeModelMessages` 的 Javadoc 明写）：
1. `compact summary`「紧凑摘要」——用户很多次对话的压减版（存表）
2. `session memory`「会话记忆」——这次会话（sessionId）相关的记忆
3. `recent messages`「近期消息」——刚聊过的最近几句（不是全部，省 token）
4. `runtime tool messages`——工具返回的消息
5. `current question`——当前这句提问

这三段记忆会被装进 `system message`（系统提示）里，因为**它要让模型把它当着"这个人是谁、之前聊过什么背景"来用**。

> 关键:记忆一直不是由助手自己凭空想起来，而是每次在“图要走 model 时”先把记忆从库里读了出来、排好、注入到上下文。

## 2.2 长期记忆从哪来：MemoryRetriever.search

文件：`assistant/memory/longterm/MemoryRetriever.java`

- 方法 `search(userId, tenantId, query, limit)`（第 38 行）
- 用 `UserMemoryMapper.selectActiveByUser(...)` 从用户记忆表把该用户**处于 ACTIVE** 的记忆全拿出来（只取还生效的）。
- 对每条记忆用 `lexicalScore` 算一下与当前问题的文字相似度；再结合
  - `importance`（多重要）、`confidence`（多可信）、`recency`（多9999近）加权求和 → `score`
- 按分排序取前 N 条。

也就是说长期记忆= `用户 × 记忆 → 四因子打分 → 取最相关`。这就是"助手"记得你偏好的真相——不是魔法，是 score。

## 2.3 长期记忆怎么拼进输入：MemoryContextBuilder.build

文件：`assistant/memory/longterm/MemoryContextBuilder.java`

- 方法 `build(List<ScoredMemory>)`（第 29 行）：
  - 空 → 返回空字符串（不喂额外记忆）。
  - 否则用固定中文前缀 `"以下是你用户的长期记忆，已按权限过滤"` + 每条一行打分记忆 + 尾部加固定指引，
  - 总长度超过 `MAX_MEMORY_CHARS` 就截断，防止把自己撑爆上下文。

所以你看：**短期记忆包"刚聊的"，长期记忆包"一贯的"**，两个都会在 `beforeModel` 阶段**打包好、排好序，注进系统消息**，然后再让大模型开口。

---

## 一张对照表（记 / 记不住都行）

### 链 1：RAG 问答
| 步骤 | 真实节点 |
|---|---|
| HTTP 入口 | `QaService` `ask` |
| 总编排 | `QaChatService.askWithUsage` |
| 检索入口(包装) | `ReadyChunkDocumentRetriever.retrieveEvidence` |
| 检索真身三通道 | `HybridChunkRetrievalService.retrieve` |
| 向量 | `PgVectorAdapter.search` |
| 关键词 | `ElasticsearchKeywordIndexService.search` |
| 角色门槛(防泄漏) | `DocumentRoleGate` |
| 证据分级 | `EvidenceAssessor.evaluate` |
| answer | `ChatClient` + `KnowledgeAnswerOutput` |

### 链 2：助手记忆
| 步 | 真实节点 |
|---|---|
| 助手入口 | `AssistantAgentFacade.chat` / `streamChat` |
| 建的图 | `AssistantReactAgentFactory.createAgent` |
| 记忆 injection | `AssistantShortTermMemoryHook.beforeModel` |
| 长期记忆检索 | `MemoryRetriever.search` |
| 拼进系统消息 | `MemoryContextBuilder.build` |

---

## 新手两步上手建议

1. **先跑一遍 RAG**：`QaChatService.askWithUsage` 当目录，它调 retrieve → 查 `HybridChunkRetrievalService.retrieve` 里的注释（全是中文），一路跟着「检索→拼证据→模型→引用」走，就能摸清"问一个知识库问题，系统到底做了什么"。
2. **再看看记忆**：`AssistantAgentFacade.chat(197)` 慢慢进 `Agent`，找到 hook 挂在 `BEFORE_MODEL` 的地方，看 `assembleBeforeModelMessages` 的顺序，再顺藤摸到 `MemoryRetriever.search`——你就懂"助手它为什么会记得你"。

> 记住底线：这两条链里的**所有角色/权限判定**（`DocumentRoleGate`、`userCanAccessDocument`）都是**红线**——只读、只加注释，**不要改逻辑**。