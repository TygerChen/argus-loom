# 文档上传 + 后台切分（Ingestion）· 超详细分步图

> 面向新手，把「你把一个文档传上去 → 系统存好 → 后台异步把它切成一小块一小块(chunk) → 再灌进向量库和 ES 索引」这条链路打通。
> **这条链分两段**：前段是**同步上传**（你等响应），后段是**异步切分 ETL**（不阻塞你，后台偷偷做）。
> **所有行号都经源码核验**（`src/main/java/com/argus/rag/document/` 与 `ingestion/` 下），不含猜测。

---

## 一句话看懂整条链

**上传**（可分片或直接）→ 文件存进对象存储(MinIO) → 往 `document` 表插一条"处理中(PROCESSING)"记录 → **发布一个异步事件** → 同步接口立刻返回 documentId → 后台监听器在事务提交后接手 → **异步 ETL**：去对象存贮把文件读回来 + 按类型解析出文本 → 清洗噪音 → 存预览 → 按结构切成若干 chunk → chunk 落库 → 向量化写进向量库 → 同步到 ES 搜索索引 → 文档状态置 **READY(就绪)**。任何一步失败 → 重试 3 次 → 仍失败 → 置 `FAILED` 并记失败原因。

> **为什么上传和切分要拆开？** 切分可能很慢（大文件几十秒）。如果让上传接口一直等到切分完，请求早超时了。所以上传只"存文件 + 发事件"，真正的解析切分在后台跑，用户马上拿到返回，前端靠刷新看状态。

---

## 上集 L · 同步上传（前端会一直等）

一共两个入口，最终都落到同一个 `UploadedDocumentFinalizer.finalizeUploaded`。

### A. 大文件分片上传（`document/service/DocumentUploadService.java`）

三次握手：**init → 传每个分片 → complete**。

**①② initUpload —— 初始化会话（`:127`）** 按优先级做三件事之一：
```
127  initUpload(...) {
128      NormalizedInitRequest r = validateInitRequest(uploadRequest);     // 校验: 文件类型/大小
130      requireGroupOwner(groupId);                                        // 必须是群组管理员
131      existingDocument = selectByGroupIdAndFileHash(groupId, fileHash);
132      if (存在 && 状态==READY) {                                          // ③ 秒传去重
135          return createInstantUploadedDocument(...) → :141 UploadInitResponse.instant(documentId)
142      existingSession = selectLatestReusableSession(groupId,userId,fileHash);   // 断点续传
148      if (存在) → 返回已传分片 index + 会话参数（:155-160）
162      session = buildUploadSession(...);  :163 insert;                   // 新建会话
167      return UploadInitResponse.uploadSession(uploadId, chunkSize, chunkCount);  // 告诉前端: 分几片每片多大
    }
```
- 上限：块 10MB（`MAX_CHUNK_SIZE :53`）；文件类型/总数在 `validateInitRequest` 里把关。
- 哈希是前端算好传上来的，服务器据此做**秒传去重**和**续传承接**。

**② 上传每个分片 —— `uploadChunk`（`:183`）**
```
183: uploadChunk(request, uploadRequest) {
184:   session = requireOwnedActiveSession(...)          // 会话有效且属于自己
185:   chunk = requireChunk(...)                          // 序号/大小/非空
187:   objectKey = chunkKey(...)                          // 算分片对象路径
193:   chunkData = chunk.getBytes();
197:   computedHash = FileHasher.sha256Hex(chunkData);    // 重算哈希
198:   if (!computedHash.equals(chunkHash))               // 与前端给的比对
           → throw "分片校验失败，数据可能已损坏" (:199)
202+   fileStore.putChunk(bucket, objectKey, chunkData)   // 分片存进 MinIO
225:   更新会话状态 = UPLOADING（:225-230）
238:   return 已上传分片 index 列表
     // upsert 幂等（:220）：传两遍同一分片不会重复
```

**②③ completeUpload —— 合并完成（`:260`）**
```
260: completeUpload(request, uploadId) {
261:      session = requireOwnedActiveSession(...)
262-264: chunks = selectByUploadId + 按 chunkIndex 排序
265:      ensureAllChunksPresent(session, chunks)          // 分片数量必须匹配
     status UPLOADING→COMPLETING（:270-279）              // 标记"正在合并"
281:      fileStore.compose(bucket, objectKey, chunkKey 列表, contentType)  // 拼成一个完整文件
284:      finalizeUploadedDocument(...)  → 见"第三段"
295-300: status→COMPLETED（:297 置 COMPLETED），回 documentId
304-312: 合并失败 → 删掉已拼的临时对象 + 删分片 → 抛异常
}
```

### B. 小文件直接上传 `uploadDocument`（`:530`，`/upload` 单文件，≤10MB）
```
530: uploadDocument(userId, uploadRequest) {
531:      groupId = requireGroupId(...)
532:      requireGroupOwner(groupId)                        // 权限
533:      file = requireValidFile(...)                   // 非空 + ≤MAX_DIRECT_FILE_SIZE
536:      fileExt = extractFileExt(fileName)
538:      fileHash = calculateSha256(file)               // 算整文件哈希
542:      uploadDirectFile(bucket(objectKey), stream)    // 直接存 MinIO
545:      document = persistAndFinalizeUploadedDocument(...)  // ↓ 到 finalizer
549:      return document.getId()
550:      失败 → compensateExternalIndexes + deleteQuietly → 抛
}
```
> `uploadDirectFile`（`:624`）用 try-with-resources 读流并调用 `fileStore.putDirect`（`:626`）。

### 落库 + 发事件 —— `UploadedDocumentFinalizer.finalizeUploaded`（`document/service/UploadedDocumentFinalizer.java:49`）

分片 or 直接**都汇聚到这**：
```
49:  finalizeUploaded(NewFileInput input) {
51:      documentMapper.insert(document);              // 插一条 document 记录
54:      发布: publishEvent(
55:          new DocumentIngestionRequestedEvent(documentId, groupId));  // ← 异步分水岭
58:      return document;
     }
61:  buildDocument(...) → 73: 文档状态 = PROCESSING(处理中)
```
**这里是"同步→异步"的边界**：`publishEvent` 只宣称"事件已发"，方法立即返回；真正的处理等下一段事件。

---

## 下集·异步 ETL（后台做完）

### ④ 事件被谁接走 —— `DocumentIngestionAsyncListener.handle`（`document/service/DocumentIngestionAsyncListener.java:48`）
```
@Async :46                        // 线程池里异步跑，不阻塞
@TransactionalEventListener(phase = AFTER_COMMIT) :47  // 上传事务提交成功后才触发
48: handle(DocumentIngestionRequestedEvent event) {
50:     documentIngestionAsyncService.ingestDocument(event.documentId(), event.groupId());
}
```
> `AFTER_COMMIT` 保证：如果上传那笔事务回滚了，事件根本不会触发——**不会出现"文档没存上却去解析"** 的错误。这是"事务提交后再动"的经典玩法。

### ⑤ 异步 ETL 编排 —— `DocumentIngestionAsyncService.ingestDocument`（`ingestion/service/DocumentIngestionAsyncService.java:100`）
```
@Retryable(maxAttempts=3, backoff=delay 2000·×2) :94   // 失败自动重试 3 次
@Transactional :99
101: ingestDocument(documentId, groupId) {
101:     requireDocument(documentId, groupId)           // 文档必须存在
103:     cleanupProcessingArtifacts(documentId)         // 先到旧 chunk/向量/ES（幂等）
104:     documentIngestionProcessor.process(documentId, groupId)  // ↓ 真活儿，见下
105:     syncSearchIndex(document)                      // 把 chunk 同步到 ES 索引
106:     markDocumentStatus(... READY ...)          // 成功 → 状态置 READY
}
```
> 上面行号 `:101`/`:103`/`:104`/`:105`/`:106` 都是方法体里的真实行（`requireDocument :101`、`cleanup :103`、`process :104`、`syncSearchIndex :105`、`markDocumentStatus READY :106`）。

全部失败兜底：
```
120: @Recover  recover(e, documentId, groupId) {     // 三主审失败自动兜住
121:    cleanupProcessingArtifacts(...)             // 清残留
122-131: markDocumentStatus(. FAILED, truncateFailureReason(e.getMessage()))  // 状态置 FAILED
}
// truncateFailureReason(:225)：超长原因截成 ≤512 存
// truncateFailureReason(:225)：超长原因截成 ≤512 存
```

### ⑥ 切分引擎本体 —— `EtlDocumentIngestionProcessor.process`（`ingestion/service/EtlDocumentIngestionProcessor.java:121`）
```
121: process(documentId, groupId) {
       // Extract
123:     documentEntity = findDocument(documentId, groupId)
124-125: reader = new StoredObjectDocumentReader(storageService, parserFactory, documentEntity)
126:     rawDocuments = reader.get()                       // 去 MinIO 读原文件 → 按扩展名解析文本
   // Transform
129:     cleanedDocuments = textCleanupTransformer.apply(rawDocuments)  // 剔除空行/噪音
132:     persistPreviewText(docId, groupId, cleanedDocuments)  // 存预览(截前200字)，见下方 helper
133:     chunkDocuments = chunkTransformer.apply(cleanedDocuments)     // 结构感知切片
   // Load
137:     saveChunkDocuments(documentId, groupId, chunkDocuments)      // chunk 落库
140:     vectorService.ingestChunks(chunks)                          // 灌向量库(Embedding)
}
```
- `persistPreviewText`（`:132` 调用 / 方法体 `:172-188`）：把清洗后的各段文本拼成一句，**截前 `PREVIEW_MAX_LENGTH` 个字**（截断逻辑在 `:196-200`）写给 `preview_text` 字段，做列表预览。
- `reader`（`StoredObjectDocumentReader`）：去对象存储把文件拉下来，交给 `DocumentParserFactory` 按**扩展名**选出解析器（`.pdf`/`.docx`/`.xlsx` 每格式一个）抽纯文本。
- `chunkTransformer`（`StructureAwareChunkTransformer`）：不按固定字数硬砍，**优先在标题/段落/空行这些结构边界**切，保证每块语义完整。
- `vectorService.ingestChunks`：把每块喂给 embedding 模型，之后问答才能按语义召回到这。

---

## 完整超细图（两段 ÷ 判定）

```mermaid
flowchart TD
  %% ---------- 上集: 同步上传 ----------
  R0["HTTP 上传入口"] --> A2
  A2{"文件大小?"}
  A2 -->|≤10MB 直接| DT["DocumentUploadService.uploadDocument :530"]
  A2 -->|大文件分片| CH["三阶段: init→chunk→complete"]
  CH --> SEC{"findByFileHash READY?"}
  SEC -->|是| INST["即时复用[...documentId] :141"]
  SEC -->|否| SESS["建新会话 :127"]
  DT -->|"utf"| UP["uploadChunk :183 / shard"]
  CH --> CM["completeUpload :260<br/>合并分片"]
  CM --> FIN
  DT --> FIN
  FIN["UploadedDocumentFinalizer.finalizeUploaded :49"]

  FIN --> DB["insert document<br/>状态 PROCESSING :73"]
  FIN --> EV["publishEvent(DocumentIngestionRequestedEvent) :54"]
  EV --> RET["HTTP 返回 documentId"]

  %% ---------- 下卷: 异步 ETL ----------
  subgraph ASYNC["异步(不阻塞)"]
    EV --> L["DocumentIngestionAsyncListener.handle :48<br/>@Async + AFTER_COMMIT"]
    L --> ING["DocumentIngestionAsyncService.ingestDocument :100<br/>@Retryable ×3"]
    ING --> CLN["cleanupProcessingArtifacts"]
    ING --> P["EtlDocumentIngestionProcessor.process :121"]
    P --> RD["StoredObjectDocumentReader.get :124<br/>读原文件→按扩展名解析"]
    P --> TX["textCleanupTransformer :129"]
    P --> PV["persistPreviewText 存预览 :172/183<br/>(截前200字)"]
    P --> CT["StructureAwareChunkTransformer 切片 :133"]
    P --> SV["chunkService.saveChunkDocuments 落库 :137"]
    P --> VEC["vectorService.ingestChunks 写向量库 :140"]
    ING --> ES["syncSearchIndex → ES :105"]
    ING --> MD["markReady → 状态 READY :106"]
    P -->|3次重试均失败| REC["@Recover :120 / recover :122 → 状态 FAILED+截断原因"]
  end

  RET -->|前端显示"处理中"| MD
  MD --> DONE["前端显示 已就绪 READY"]
  REC --> FAIL["前端显示 失败可看原因"]
```

> ⚠️ 教学展开（把判定/异步支线拆成独立框），箭头 = 调用/数据流，非严格 1:1 嵌套。更接近真实代码的简版图见同目录 `DocumentController.initUpload.md` / `DocumentController.uploadChunk.md` / `DocumentController.completeUpload.md` / `DocumentController.uploadDocument.md`。

---

## 新手三问，问完即懂文档上传

1. **"上传就秒传了(auto？"** 服务器用「同 group + 同 fileHash 且 READY」去重。你在别处传过同一份文件，库里已有 READY 就不重传一堆分片，直接给你已有 documentId（`initUpload :131-142`）。
2. **"为什么切分在后台？"** 大文件解析成文本、切成 chunk、灌向量库都要花时间。如果同步做，上传接口要挂几分钟，用户等不了。所以"存完文件马上还响应"，解析/切分/向量化丢给异步监听器慢慢跑，用户看到状态 PROCESSING→READY。
3. **"一个文档切成那么多 chunk 要干嘛？"** RAG 问答查的"片段"，不是整篇。把长文切成语义完整的短文，才能在检索时按语义召回最相关的几段，再喂给大模型作答。**切块质量直接决定问答质量**。

- **可靠性**：异步 ETL 带 `@Retryable` 重试 3 次 + `@Recover` 兜底置 FAILED；每次重试前先 `cleanupProcessingArtifacts` 清掉半成品，避免脏数据残留。

---

_行号核对时间点：2026-09-21 · 与源码冲突以源码为准。按分片步骤拆的简版图见同目录 `DocumentController.initUpload.md` / `uploadChunk.md` / `completeUpload.md` / `DocumentController.uploadDocument.md`。_