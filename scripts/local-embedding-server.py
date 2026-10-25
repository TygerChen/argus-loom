"""Local OpenAI-compatible endpoint for Argus RAG.

Serves /v1/embeddings locally with the ONNX BGE small Chinese model (512 dims)
and proxies /v1/chat/completions to the Agnes OpenAI-compatible API, so the
whole Spring AI client can use one base URL.
"""

import os
import json
import sys

import numpy as np
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from fastembed import TextEmbedding

# 上游令牌只从环境变量读取（.env 经 start-all.ps1 注入）；源码不允许出现真实 key，
# 该文件曾被跟踪并推送远程，历史中的硬编码 key 视为已泄露，轮换前不要再写回。
AGNES_KEY = os.environ.get("AGNES_API_KEY", "").strip()
if not AGNES_KEY:
    sys.exit("缺少 AGNES_API_KEY 环境变量：请在根目录 .env 配置后启动，不要写回源码。")
AGNES_BASE = os.environ.get("AGNES_BASE_URL", "https://api.agnes-ai.cn")
client = httpx.Client(timeout=120)

app = FastAPI(title="argus-local-embedding")
model = TextEmbedding("BAAI/bge-small-zh-v1.5")


@app.post("/v1/embeddings")
async def embeddings(request: Request):
    raw = await request.body()
    try:
        body = await request.json()
    except Exception:
        body = json.loads(raw)
    inputs = body.get("input", [])
    if isinstance(inputs, str):
        inputs = [inputs]
    if not isinstance(inputs, list):
        raise ValueError("input must be a string or list of strings")

    vectors = []
    for raw in inputs:
        if isinstance(raw, dict):
            raw = raw.get("text", "")
        embedding = next(model.embed([str(raw)]))
        vectors.append(np.asarray(embedding, dtype=np.float32).tolist())

    return {
        "object": "list",
        "data": [{"object": "embedding", "index": i, "embedding": v} for i, v in enumerate(vectors)],
        "model": body.get("model", "BAAI/bge-small-zh-v1.5"),
        "usage": {"prompt_tokens": len(inputs), "total_tokens": len(inputs)},
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
