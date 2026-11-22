#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
纯检索评测：绕过 LLM 生成层，直接用 evidenceOverview 里检索到的文档
判断检索命中率（HitRate@K / MRR）。解决 run_eval.py 用 citations 算命中、
但模型失败时 citations 为空导致 HitRate 被严重低估的测量假象。

用法：python retrieval_only_eval.py [--group-id 6] [--testset testset_kingdee.json]

安全：本脚本是本地评测工具，只允许访问 localhost 后端（白名单校验），
不发起任何外部请求。
"""
import argparse
import json
import time
import sys
from pathlib import Path
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    print("缺少依赖：pip install requests")
    sys.exit(1)

HERE = Path(__file__).resolve().parent
DEFAULT_BASE = "http://localhost:10001"
USER = "eval_probe"
PASS = "Probe#2026x"
INTERVAL = 3.5


def assert_localhost(base: str) -> str:
    """仅允许 localhost/127.0.0.1/::1，拒绝任何其它主机（本地评测工具的安全边界）。"""
    parsed = urlparse(base)
    host = (parsed.hostname or "").lower()
    if parsed.scheme not in ("http", "https") or host not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError(f"评测工具只允许访问 localhost 后端，拒绝: {base}")
    return base


def login(base: str) -> str:
    r = requests.post(base + "/api/auth/login",
                      json={"loginId": USER, "password": PASS}, timeout=15)
    return (r.json().get("data") or {}).get("accessToken")


def retrieve(base: str, token: str, group_id: int, question: str):
    """调 /api/qa/ask，返回 evidenceOverview 里的召回文件名列表 + reasonCode。"""
    h = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    try:
        r = requests.post(base + "/api/qa/ask",
                          json={"groupId": group_id, "question": question},
                          headers=h, timeout=90)
        d = r.json()
        ov = d.get("evidenceOverview") or {}
        groups = ov.get("groups") or []
        files = [g.get("fileName", "") for g in groups]
        return files, d.get("reasonCode")
    except Exception as e:
        return None, f"REQUEST_ERROR: {type(e).__name__}"


def hit_rank(files, expected_files, k):
    """返回第一条相关召回的排名（1 起）；无命中返回 0。"""
    for rank, name in enumerate(files[:k], start=1):
        for exp in expected_files:
            if exp.lower() in name.lower():
                return rank
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--group-id", type=int, default=6)
    ap.add_argument("--testset", default="testset_kingdee.json")
    ap.add_argument("--k", type=int, default=5)
    args = ap.parse_args()

    base = assert_localhost(args.base)
    ts = json.loads(Path(args.testset).read_text("utf-8"))
    positives = [c for c in ts["cases"] if c.get("should_answer", True)]

    token = login(base)
    print(f"纯检索评测：{len(positives)} 正例，群组 {args.group_id}，K={args.k}\n")

    hits = 0
    ranks = []
    measurable = 0
    unmeasurable = 0
    rc_stat = {}

    for c in positives:
        files, rc = retrieve(base, token, args.group_id, c["question"])
        if files is None:
            unmeasurable += 1
            rc_stat[rc] = rc_stat.get(rc, 0) + 1
            print(f"  [跳过] {c['question'][:22]}... 无检索结果（{rc}）")
            time.sleep(INTERVAL)
            continue
        measurable += 1
        rc_stat[rc or "ANSWERED"] = rc_stat.get(rc or "ANSWERED", 0) + 1
        rank = hit_rank(files, c.get("expected_files") or [], args.k)
        ranks.append(rank)
        if rank > 0:
            hits += 1
        print(f"  {'✓命中' if rank else '✗未中'} 首召回排名={rank or '-'} | {c['question'][:20]} -> {files[:2]}")
        time.sleep(INTERVAL)

    mrr = sum(1.0 / r for r in ranks if r > 0) / max(len(ranks), 1)
    hit_rate = hits / max(measurable, 1)

    print(f"\n===== 纯检索指标（{measurable}/{len(positives)} 题可测，{unmeasurable} 题模型超时跳过）=====")
    print(f"  HitRate@{args.k}: {hit_rate:.1%}")
    print(f"  MRR: {mrr:.4f}")
    print(f"  reasonCode 分布: {rc_stat}")


if __name__ == "__main__":
    main()
