"""LangChain 对照实验：用 LangChain 复刻 Argus-Loom 的检索链。

目的（面试叙事）：
    "LangChain/Dify 我动手跑过——我用 LangChain 把自研 Java 检索链的概念一一复刻：
     splitter/loader/retriever/rerank/拒答，两边概念一一对应。
     也清楚自研在权限、事务、队列语义上的优势。"

概念对照表（LangChain ←→ Argus-Loom Java 实现）：
    TextLoader/DirectoryLoader      ←→  DocumentParserFactory（TXT/MD/PDF/DOCX）
    RecursiveCharacterTextSplitter  ←→  StructureAwareChunkTransformer（240/320/32）
    FastembedEmbeddings(bge-small-zh) ←→ LocalEmbeddingConfig（同一个模型，JVM ONNX）
    InMemoryVectorStore             ←→  PgVectorRetrievalAdapter（生产用 pgvector）
    BM25Retriever                   ←→  ElasticsearchChunkIndexService（关键词召回）
    rrf_fuse()（本文件手写）         ←→  qa/support/RrfFusion（k=0）
    TextCrossEncoder(fastembed)     ←→  RemoteHttpRerankingService（BGE 重排，同一模型）
    证据阈值拒答                     ←→  EvidenceAssessor（RETRIEVAL_NOT_GROUNDED）
    ChatOpenAI(base_url=网关)        ←→  ModelClient → 11435 网关

运行：
    python rag_lab.py                # 检索链全跑（离线，不依赖 LLM）
    python rag_lab.py --llm          # 末尾再调一次 LLM 生成（需要上游可用）
"""
import argparse
import sys
from pathlib import Path

from langchain_community.document_loaders import DirectoryLoader, TextLoader
from langchain_community.retrievers import BM25Retriever
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_text_splitters import RecursiveCharacterTextSplitter

HERE = Path(__file__).resolve().parent
CORPUS_DIR = HERE.parent / "rag_eval" / "eval_docs"
CACHE_DIR = "F:/DevelopmentTools/fastembed-cache"  # 持久化缓存：别用 Temp（会被系统清理）
CHUNK_SIZE = 320
CHUNK_OVERLAP = 32
RECALL_K = 10
RERANK_TOP_N = 5
REFUSAL_THRESHOLD = 0.35   # 精排最高分低于该值视为证据不足（对照 EvidenceAssessor）


class FastembedEmbeddings(Embeddings):
    """fastembed bge-small-zh 适配成 LangChain Embeddings——与 Java 侧同一个模型。"""

    def __init__(self):
        from fastembed import TextEmbedding

        self._model = TextEmbedding("BAAI/bge-small-zh-v1.5", cache_dir=CACHE_DIR)

    def embed_documents(self, texts):
        return [self._to_list(next(self._model.embed([t]))) for t in texts]

    def embed_query(self, text):
        return self._to_list(next(self._model.embed([text])))

    @staticmethod
    def _to_list(vec):
        return [float(x) for x in vec]


def rrf_fuse(ranked_lists, k=0):
    """RRF 融合（k=0 与 Java RrfFusion 同口径）：只看名次，不比分数。"""
    scores = {}
    for ranked in ranked_lists:
        for rank, doc in enumerate(ranked):
            key = id(doc)
            scores.setdefault(key, {"doc": doc, "score": 0.0})
            scores[key]["score"] += 1.0 / (k + rank + 1)
    return [v["doc"] for _, v in sorted(scores.items(), key=lambda kv: -kv[1]["score"])]


def build_pipeline():
    print("[1/5] 加载语料（eval_docs/）...")
    loader = DirectoryLoader(str(CORPUS_DIR), glob="**/*.md", loader_cls=TextLoader,
                             loader_kwargs={"encoding": "utf-8"}, show_progress=False)
    docs = loader.load()
    print(f"      文档 {len(docs)} 份")

    print("[2/5] 结构感知切片（对照 StructureAwareChunkTransformer：320/32）...")
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=CHUNK_SIZE, chunk_overlap=CHUNK_OVERLAP,
        separators=["\n## ", "\n### ", "\n\n", "\n", "。"])
    chunks = splitter.split_documents(docs)
    for c in chunks:
        c.page_content = f"{c.metadata.get('source', '')}\n{c.page_content}"
    print(f"      切片 {len(chunks)} 块")

    print("[3/5] 向量化（bge-small-zh，与 Java 侧同模型）...")
    embeddings = FastembedEmbeddings()
    vector_store = InMemoryVectorStore(embedding=embeddings)  # 生产对照：pgvector
    vector_store.add_documents(chunks)

    print("[4/5] BM25 关键词索引（对照 ES 召回）...")
    bm25 = BM25Retriever.from_documents(chunks, k=RECALL_K)
    return vector_store, bm25, embeddings


def retrieve(vector_store, bm25, embeddings, question):
    """双路召回 → RRF → 精排 → 证据门。返回 (最终候选, 是否拒答, 明细)。"""
    vec_hits = vector_store.similarity_search_with_score(question, k=RECALL_K)
    vec_docs = [d for d, _ in vec_hits]
    kw_docs = [d for d in bm25.invoke(question)]

    fused = rrf_fuse([vec_docs, kw_docs])[:RECALL_K]
    if not fused:
        return [], True, {"reason": "无候选"}

    print("[5/5] BGE 精排 top%d（对照 RemoteHttpRerankingService）..." % len(fused))
    from fastembed.rerank.cross_encoder import TextCrossEncoder
    reranker = TextCrossEncoder("BAAI/bge-reranker-base", cache_dir=CACHE_DIR)
    scores = [float(s) for s in reranker.rerank(question, [d.page_content for d in fused])]
    scored = sorted(zip(fused, scores), key=lambda p: -p[1])

    top_score = scored[0][1]
    refuse = top_score < REFUSAL_THRESHOLD  # 证据门：不足则拒答，不编造
    return scored, refuse, {"top_score": top_score}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--llm", action="store_true", help="末尾调用本地网关 LLM 生成（上游可用时）")
    parser.add_argument("--question", default="去北京出差住酒店一晚最多能报销多少钱？")
    args = parser.parse_args()

    vector_store, bm25, embeddings = build_pipeline()
    print(f"\n问题：{args.question}\n")
    scored, refuse, detail = retrieve(vector_store, bm25, embeddings, args.question)

    print("\n===== 检索结果 =====")
    for rank, (doc, score) in enumerate(scored[:3], 1):
        src = Path(doc.metadata.get("source", "?")).name
        preview = doc.page_content.replace("\n", " ")[:60]
        print(f"  #{rank} score={score:.3f} [{src}] {preview}...")

    if refuse:
        print("\n==> 拒答：证据不足（RETRIEVAL_NOT_GROUNDED 同语义），不调 LLM。")
        return

    print(f"\n==> 证据充足（top={detail['top_score']:.3f}），进入生成。")
    context = "\n\n".join(f"[{i + 1}] {d.page_content[:200]}" for i, (d, _) in enumerate(scored[:3]))
    prompt = f"仅根据以下证据回答问题，并标注引用编号；证据不足请回答不知道。\n\n证据：\n{context}\n\n问题：{args.question}"
    print("----- 生成的 Prompt（对照 Java 侧 PromptBuilder）-----")
    print(prompt[:500])

    if not args.llm:
        print("\n(--llm 未开启：跳过 LLM 调用。上游恢复后加 --llm 生成完整回答。)")
        return
    from langchain_openai import ChatOpenAI
    llm = ChatOpenAI(model="agnes-3.0-flash", base_url="http://127.0.0.1:11435/v1",
                     api_key="local-gateway", timeout=60)
    print("\n----- LLM 回答 -----")
    print(llm.invoke(prompt).content)


if __name__ == "__main__":
    main()
