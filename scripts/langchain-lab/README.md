# LangChain 对照实验（rag_lab.py）

用 LangChain 复刻 Argus-Loom（Java 自研）检索链的可运行对照实现。
**用途**：证明"两个都动手过，概念一一对应"——AI 应用岗 JD 高频要求。

## 运行

```bash
cd scripts/langchain-lab
set HF_ENDPOINT=https://hf-mirror.com   # 国内网络必设
set HF_HUB_DISABLE_XET=1                # 绕过 xet 401
python rag_lab.py                       # 检索链全跑（离线，不调 LLM）
python rag_lab.py --llm                 # 末尾调 LLM 生成（需上游可用）
```

## 概念对照表（面试讲这张表）

| LangChain | Argus-Loom（Java 自研） |
| --- | --- |
| DirectoryLoader + TextLoader | DocumentParserFactory（TXT/MD/PDF/DOCX） |
| RecursiveCharacterTextSplitter(320/32) | StructureAwareChunkTransformer（240/320/32） |
| FastembedEmbeddings(bge-small-zh) | LocalEmbeddingConfig（同模型，JVM ONNX） |
| InMemoryVectorStore | PgVectorRetrievalAdapter（生产 pgvector+HNSW） |
| BM25Retriever | ElasticsearchChunkIndexService |
| rrf_fuse()（手写，k=0） | qa/support/RrfFusion |
| TextCrossEncoder(bge-reranker-base) | RemoteHttpRerankingService（三级降级） |
| 阈值拒答 | EvidenceAssessor（RETRIEVAL_NOT_GROUNDED） |
| ChatOpenAI(base_url=网关) | ModelClient → 本地网关 |

## 与自研版的差异（面试加分句）

- 对照版无权限体系（自研有群组/文档角色/子资源三层 fail-closed）
- 对照版无任务队列（自研 ETL 有幂等/租约/退避/死信）
- 对照版无评测闭环（自研 40+170 题集持续回归）
- 结论："LangChain 解决了快速搭管线的问题；生产级的权限、可靠性、质量闭环才是自研的价值。两边我都动手过，选型可以按场景讲。"

## 已验证

- 2026-10-24：检索链全流程跑通（语料 6 块、双路召回、RRF、BGE 精排、证据门），测试问题命中差旅报销制度文档。
- 模型缓存在 `F:/DevelopmentTools/fastembed-cache`（持久化，勿用 Temp）。
