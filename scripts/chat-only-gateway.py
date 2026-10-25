"""Chat-only OpenAI 兼容网关（替代 local-embedding-server.py）。

当前工程 embedding 已由 JVM 进程内 bge-small-zh 承载（spring.ai.model.embedding=local），
本机网关只需要把 /v1/chat/completions 代理到 Agnes API。
embedding 端点保留空桩，避免无关探测报 404。

上游令牌只从环境变量 AGNES_API_KEY 读取（本地由 .env 提供，start-all.ps1 会先加载）；
源码内不允许出现真实 key——2026-10-24 审计发现硬编码 key 已随 git 历史暴露到远程仓库。
"""
import json
import os
import sys
import threading

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse

AGNES_KEY = os.environ.get("AGNES_API_KEY", "").strip()
if not AGNES_KEY:
    sys.exit(
        "缺少 AGNES_API_KEY 环境变量：请在根目录 .env（已 gitignore）配置后经 "
        "start-all.ps1 启动，或手动 export。不要把真实 key 写回源码。"
    )
AGNES_BASE = os.environ.get("AGNES_BASE_URL", "https://api.agnes-ai.cn")
client = httpx.Client(timeout=120)

app = FastAPI(title="argus-chat-only-gateway")

# ---- rerank（P1-1）：BGE cross-encoder，懒加载 + 双检锁，避免导入即拉起 1GB 模型 ----
RERANK_MODEL = os.environ.get("RERANK_MODEL", "BAAI/bge-reranker-base")
_cross_encoder = None
_cross_encoder_lock = threading.Lock()


def _get_cross_encoder():
    global _cross_encoder
    if _cross_encoder is None:
        with _cross_encoder_lock:
            if _cross_encoder is None:
                from fastembed.rerank.cross_encoder import TextCrossEncoder

                _cross_encoder = TextCrossEncoder(model_name=RERANK_MODEL)
    return _cross_encoder


@app.post("/rerank")
async def rerank(request: Request):
    body = await request.json()
    query = body.get("query") or ""
    documents = body.get("documents") or []
    if not isinstance(documents, list) or not documents:
        return {"model": RERANK_MODEL, "results": []}
    model = _get_cross_encoder()
    scores = [float(s) for s in model.rerank(query, [str(d) for d in documents])]
    results = sorted(
        ({"index": i, "score": float(s)} for i, s in enumerate(scores)),
        key=lambda r: r["score"],
        reverse=True,
    )
    return {"model": RERANK_MODEL, "results": results}


@app.post("/v1/embeddings")
async def embeddings(request: Request):
    body = await request.json()
    inputs = body.get("input", [])
    n = len(inputs) if isinstance(inputs, list) else 1
    return {
        "object": "list",
        "data": [],
        "model": body.get("model", "BAAI/bge-small-zh-v1.5"),
        "usage": {"prompt_tokens": n, "total_tokens": n},
    }


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    body = await request.json()
    headers = {"Authorization": f"Bearer {AGNES_KEY}"}
    url = f"{AGNES_BASE}/v1/chat/completions"

    if body.get("stream"):
        def upstream_stream():
            with client.stream("POST", url, json=body, headers=headers) as upstream:
                if upstream.status_code >= 400:
                    error = json.dumps({
                        "error": {"message": upstream.text, "type": "upstream_error"}
                    })
                    yield f"data: {error}\n\n"
                    return
                for line in upstream.iter_lines():
                    yield line + "\n"

        return StreamingResponse(
            upstream_stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    upstream = client.post(url, json=body, headers=headers)
    upstream.raise_for_status()
    return upstream.json()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=11435, log_level="warning")
