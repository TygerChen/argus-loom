#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 检索与问答质量评估脚本（端到端）。

流程：
    1. 登录获取 accessToken
    2. （--prepare）创建评估专用群组，上传 eval_docs/ 下的测试文档，等待切片向量化完成（READY）
    3. 逐条调用 POST /api/qa/ask 提问
    4. 计算指标：
       - HitRate@K   检索命中率：引用列表前 K 条中命中期望文件的比例
       - MRR         平均倒数排名：第一条相关引用排名的倒数的均值
       - 答案要点覆盖率：answer 中命中 expected_keywords 的比例
       - 问答准确率：正例中「回答且要点覆盖达标」的比例 + 负例正确拒答的比例
    5. 输出 eval_result.json 与 report.md

用法：
    python prepare_testset.py                 # 先生成测试文档与测试集
    python run_eval.py --prepare              # 首次：建群+传文档+等 READY+评估
    python run_eval.py                        # 后续：复用已有群组直接评估
    python run_eval.py --group-id 3           # 指定已有群组
    python run_eval.py --profile local        # 方案验收命令（等价于默认 profile）
    python run_eval.py --dry-run              # 不连后端，演示报告结构

依赖：pip install requests
限流提示：后端问答接口限流 20 次/60 秒，脚本默认每题间隔 3.5 秒，40 题约 3-5 分钟。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("缺少依赖：请先执行  pip install requests")
    sys.exit(1)

HERE = Path(__file__).resolve().parent
DOCS_DIR = HERE / "eval_docs"
TESTSET_PATH = HERE / "testset.json"
STATE_PATH = HERE / "state.json"
RESULT_JSON = HERE / "eval_result.json"
REPORT_MD = HERE / "report.md"

DEFAULT_BASE = "http://localhost:10001"
DEFAULT_USER = "evaluser"
DEFAULT_PASS = "Eval#User2026"
GROUP_NAME = "RAG评估专用组"
ASK_INTERVAL_SECONDS = 3.5
READY_TIMEOUT_SECONDS = 180


class Client:
    """极简 API 客户端：统一带上 Bearer token，统一解包 ApiResponse。"""

    def __init__(self, base: str, token: str | None = None):
        self.base = base.rstrip("/")
        self.session = requests.Session()
        self.token = token
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def request(self, method: str, path: str, **kwargs) -> dict:
        kwargs.setdefault("timeout", 120)
        resp = self.session.request(method, self.base + path, **kwargs)
        resp.raise_for_status()
        body = resp.json()
        if isinstance(body, dict) and body.get("success") is False:
            raise RuntimeError(f"API 业务失败 {path}: {body.get('message')}")
        return body.get("data", body) if isinstance(body, dict) else body

    def login(self, login_id: str, password: str) -> str:
        data = self.request(
            "POST", "/api/auth/login",
            json={"loginId": login_id, "password": password},
        )
        token = data.get("accessToken") or data.get("token")
        if not token:
            raise RuntimeError(f"登录响应中没有 token: {data}")
        return token

    def register(self, username: str, email: str, display_name: str, password: str) -> None:
        """注册评估专用普通用户（管理员账号被禁止访问业务区）。已存在时忽略报错。"""
        try:
            self.request("POST", "/api/auth/register", json={
                "username": username, "email": email,
                "displayName": display_name, "password": password,
            })
        except (RuntimeError, requests.HTTPError) as exc:
            text = str(exc)
            if "已" in text or "存在" in text or "409" in text or "400" in text:
                return  # 大概率是重复注册，直接尝试登录
            raise

    def my_groups(self) -> list[dict]:
        data = self.request("GET", "/api/groups/my") or {}
        groups = list(data.get("ownedGroups") or []) + list(data.get("joinedGroups") or [])
        # API 返回字段为 groupId/groupName，统一补 id/name 别名
        for g in groups:
            g.setdefault("id", g.get("groupId"))
            g.setdefault("name", g.get("groupName"))
        return groups


def wait_documents_ready(client: Client, group_id: int, doc_ids: list[int]) -> None:
    """轮询直到所有文档状态变为 READY 或 FAILED。"""
    deadline = time.time() + READY_TIMEOUT_SECONDS
    pending = set(doc_ids)
    while pending and time.time() < deadline:
        docs = client.request("GET", "/api/documents", params={"groupId": group_id}) or []
        by_id = {d.get("id"): d for d in docs}
        for doc_id in list(pending):
            status = (by_id.get(doc_id) or {}).get("status")
            if status == "READY":
                pending.discard(doc_id)
                print(f"  文档 {doc_id} 已 READY")
            elif status == "FAILED":
                pending.discard(doc_id)
                reason = (by_id.get(doc_id) or {}).get("failReason") or "未知原因"
                print(f"  警告：文档 {doc_id} 摄取失败：{reason}")
        if pending:
            time.sleep(3)
    if pending:
        print(f"  警告：{len(pending)} 个文档超时未就绪：{sorted(pending)}")


def prepare_group(client: Client, args) -> int:
    """注册评估用户、创建（或复用）评估群组并上传测试文档。"""
    state = json.loads(STATE_PATH.read_text("utf-8")) if STATE_PATH.exists() else {}

    group_id = state.get("group_id")
    if not group_id:
        # 先看是否已经存在同名群组（在名下群组里找）
        for g in client.my_groups():
            if g.get("name") == GROUP_NAME:
                group_id = g.get("id")
                break
        if not group_id:
            group_id = client.request(
                "POST", "/api/groups", json={"name": GROUP_NAME,
                                              "description": "RAG 自动化评估用，可随时删除"}
            )
        state["group_id"] = group_id
        STATE_PATH.write_text(json.dumps(state), "utf-8")
    print(f"评估群组 groupId = {group_id}")

    doc_ids = []
    for path in sorted(DOCS_DIR.glob("*.md")):
        with path.open("rb") as fh:
            doc_id = client.request(
                "POST", "/api/documents/upload",
                data={"groupId": group_id},
                files={"file": (path.name, fh, "text/markdown")},
            )
        doc_ids.append(int(doc_id))
        print(f"  已上传 {path.name} -> documentId={doc_id}")

    print("等待文档切片向量化完成 ...")
    wait_documents_ready(client, group_id, doc_ids)
    return group_id


# ---------------------------------------------------------------------------
# 指标计算
# ---------------------------------------------------------------------------

def citation_hit(citations: list[dict], expected_files: list[str]) -> int:
    """返回第一条相关引用的排名（1 起）；无命中返回 0。"""
    for rank, c in enumerate(citations[:5], start=1):
        name = (c.get("fileName") or "")
        for expected in expected_files:
            if expected in name:
                return rank
    return 0


def keyword_coverage(answer: str, keywords: list[str]) -> float:
    if not keywords:
        return 1.0
    hits = sum(1 for k in keywords if k in (answer or ""))
    return hits / len(keywords)


def evaluate_case(case: dict, resp: dict, threshold: float, k: int) -> dict:
    answered = bool(resp.get("answered"))
    citations = resp.get("citations") or []
    answer = resp.get("answer") or ""

    expected_files = case.get("expected_files") or []
    expect_answer = case.get("should_answer", True)
    first_hit_rank = citation_hit(citations, expected_files) if expected_files else 0
    coverage = keyword_coverage(answer, case.get("expected_keywords") or [])

    if expect_answer:
        retrieval_hit = first_hit_rank > 0
        answer_ok = answered and coverage >= threshold
    else:
        retrieval_hit = None          # 负例不参与检索指标
        answer_ok = not answered      # 正确拒答才算对

    return {
        "id": case["id"],
        "question": case["question"],
        "expected_files": expected_files,
        "should_answer": expect_answer,
        "answered": answered,
        "reasonCode": resp.get("reasonCode"),
        "first_hit_rank": first_hit_rank,
        "retrieval_hit": retrieval_hit,
        "keyword_coverage": round(coverage, 4),
        "answer_ok": answer_ok,
        "n_citations": len(citations),
        "answer_excerpt": (answer or "")[:120],
    }


def summarize(results: list[dict], threshold: float, k: int) -> dict:
    positives = [r for r in results if r["should_answer"]]
    negatives = [r for r in results if not r["should_answer"]]

    ranks = [r["first_hit_rank"] for r in positives]
    hit = [1 if r > 0 else 0 for r in ranks]
    mrr_values = [1.0 / r if r > 0 else 0.0 for r in ranks]

    answer_acc_pos = sum(1 for r in positives if r["answer_ok"]) / max(len(positives), 1)
    refusal_acc = sum(1 for r in negatives if r["answer_ok"]) / max(len(negatives), 1)
    overall = (sum(1 for r in positives if r["answer_ok"]) + sum(1 for r in negatives if r["answer_ok"])) / max(len(results), 1)

    return {
        "total_cases": len(results),
        "hit_rate_at_k": round(sum(hit) / max(len(hit), 1), 4),
        "k": k,
        "mrr": round(sum(mrr_values) / max(len(mrr_values), 1), 4),
        "avg_keyword_coverage": round(
            sum(r["keyword_coverage"] for r in positives) / max(len(positives), 1), 4),
        "answer_accuracy_positive": round(answer_acc_pos, 4),
        "refusal_accuracy_negative": round(refusal_acc, 4),
        "overall_accuracy": round(overall, 4),
        "answer_coverage_threshold": threshold,
    }


def write_report(summary: dict, results: list[dict], dry_run: bool) -> None:
    flag = "（DRY-RUN 演示数据，非真实测量）" if dry_run else ""
    lines = [
        "# RAG 评估报告",
        "",
        f"> 指标{flag}",
        "",
        f"- 用例总数：{summary['total_cases']}",
        f"- 检索命中率 HitRate@{summary['k']}：**{summary['hit_rate_at_k']:.1%}**",
        f"- 平均倒数排名 MRR：**{summary['mrr']:.4f}**",
        f"- 答案要点平均覆盖率：**{summary['avg_keyword_coverage']:.1%}**",
        f"- 正例问答准确率（要点覆盖 ≥ {summary['answer_coverage_threshold']:.0%}）：**{summary['answer_accuracy_positive']:.1%}**",
        f"- 负例拒答准确率：**{summary['refusal_accuracy_negative']:.1%}**",
        f"- 综合准确率：**{summary['overall_accuracy']:.1%}**",
        "",
        "## 逐题明细",
        "",
        "| # | 问题 | 应答 | 实答 | 首命中排名 | 要点覆盖 | 判定 |",
        "|---|------|------|------|-----------|---------|------|",
    ]
    for r in results:
        lines.append(
            f"| {r['id']} | {r['question'][:28]} | {'答' if r['should_answer'] else '拒'} "
            f"| {'答' if r['answered'] else '拒'} | {r['first_hit_rank'] or '-'} "
            f"| {r['keyword_coverage']:.0%} | {'✅' if r['answer_ok'] else '❌'} |"
        )
    REPORT_MD.write_text("\n".join(lines), encoding="utf-8")
    print(f"报告已写入：{REPORT_MD}")


def dry_run(testset: dict) -> None:
    """不连后端：用确定性伪随机造一份演示结果，展示报告结构。"""
    results = []
    for i, case in enumerate(testset["cases"]):
        expect_answer = case.get("should_answer", True)
        rank = (i % 3) + 1 if expect_answer else 0
        coverage = [1.0, 0.75, 0.5][i % 3] if expect_answer else 1.0
        answered = expect_answer if (i % 7) else False
        results.append({
            "id": case["id"], "question": case["question"],
            "expected_files": case.get("expected_files") or [],
            "should_answer": expect_answer,
            "answered": answered, "reasonCode": None,
            "first_hit_rank": rank,
            "retrieval_hit": (rank > 0) if expect_answer else None,
            "keyword_coverage": coverage,
            "answer_ok": (answered and coverage >= testset["answer_coverage_threshold"]) if expect_answer else (not answered),
            "n_citations": 3, "answer_excerpt": "（演示数据）",
        })
    summary = summarize(results, testset["answer_coverage_threshold"], testset.get("hit_rate_k", 5))
    RESULT_JSON.write_text(
        json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2),
        encoding="utf-8")
    print(f"演示结果已写入：{RESULT_JSON}")
    write_report(summary, results, dry_run=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="RAG 检索与问答质量评估")
    parser.add_argument("--base", default=DEFAULT_BASE, help=f"后端地址，默认 {DEFAULT_BASE}")
    parser.add_argument("--user", default=DEFAULT_USER)
    parser.add_argument("--password", default=DEFAULT_PASS)
    parser.add_argument("--register", action="store_true",
                        help="先注册评估用户（已存在则忽略），管理员账号无业务区权限时使用")
    parser.add_argument("--group-id", type=int, help="直接使用已有群组，跳过 --prepare")
    parser.add_argument("--testset", default=str(TESTSET_PATH), help="测试集 JSON 路径（默认正式 40 题集）")
    parser.add_argument("--profile", default="local", choices=["local"],
                        help="评测画像；local = 连本机后端（当前唯一画像，保留参数位便于扩展）")
    parser.add_argument("--fresh", action="store_true", help="忽略断点，全部重测")
    parser.add_argument("--prepare", action="store_true", help="创建群组并上传测试文档")
    parser.add_argument("--dry-run", action="store_true", help="不连后端，演示报告结构")
    args = parser.parse_args()

    testset = json.loads(Path(args.testset).read_text("utf-8"))
    cases = testset["cases"]
    threshold = testset.get("answer_coverage_threshold", 0.6)
    top_k = testset.get("hit_rate_k", 5)

    if args.dry_run:
        dry_run(testset)
        return

    client = Client(args.base)
    if args.register:
        client.register(args.user, f"{args.user}@eval.local", "RAG评估用户", args.password)
        print(f"评估用户就绪：{args.user}")
    token = client.login(args.user, args.password)
    client = Client(args.base, token)
    print(f"登录成功：{args.user}")

    if args.prepare:
        group_id = prepare_group(client, args)
    elif args.group_id:
        group_id = args.group_id
    else:
        state = json.loads(STATE_PATH.read_text("utf-8")) if STATE_PATH.exists() else {}
        group_id = state.get("group_id")
        if not group_id:
            print("未找到评估群组：请先运行  python run_eval.py --prepare  或指定 --group-id")
            sys.exit(1)

    print(f"开始评估：{len(cases)} 条用例，群组 {group_id}，每题间隔 {ASK_INTERVAL_SECONDS}s")

    # 断点续跑：每题落盘，进程被杀后重跑只补未完成的题
    ckpt_path = HERE / f"eval_checkpoint_{Path(args.testset).stem}.json"
    signature = f"{Path(args.testset).name}|{group_id}"
    ckpt: dict = {}
    if ckpt_path.exists():
        try:
            saved = json.loads(ckpt_path.read_text("utf-8"))
            ckpt = saved if saved.get("signature") == signature else {}
        except Exception:
            ckpt = {}
    done: dict[int, dict] = {} if args.fresh else {
        int(k): v for k, v in (ckpt.get("results") or {}).items()}
    if done:
        print(f"  断点续跑：已有 {len(done)} 题结果，只补剩余 {len(cases) - len(done)} 题")

    results = []
    for i, case in enumerate(cases, start=1):
        if case["id"] in done:
            results.append(done[case["id"]])
            continue
        resp = None
        for attempt in (1, 2):  # access token 30 分钟过期，401 时自动重登一次
            try:
                resp = client.request(
                    "POST", "/api/qa/ask",
                    json={"groupId": group_id, "question": case["question"]},
                ) or {}
                break
            except requests.HTTPError as exc:
                if attempt == 1 and "401" in str(exc):
                    print(f"  [i] token 过期，自动重新登录")
                    client = Client(args.base, client.login(args.user, args.password))
                    continue
                print(f"  [{i}/{len(cases)}] {case['question'][:20]}... 请求失败：{exc}")
                break
            except Exception as exc:  # 单题失败不中断整体评估
                print(f"  [{i}/{len(cases)}] {case['question'][:20]}... 请求失败：{exc}")
                break
        if resp is None:
            resp = {"answered": False, "answer": "", "reasonCode": "REQUEST_ERROR"}
        row = evaluate_case(case, resp, threshold, top_k)
        results.append(row)
        done[case["id"]] = row
        ckpt_path.write_text(json.dumps({"signature": signature, "results": done},
                                        ensure_ascii=False), encoding="utf-8")
        print(f"  [{i}/{len(cases)}] {'✅' if row['answer_ok'] else '❌'} "
              f"{'拒答' if not row['answered'] else '回答'} 覆盖{row['keyword_coverage']:.0%} "
              f"首命中{row['first_hit_rank'] or '-'} | {case['question'][:24]}")
        time.sleep(ASK_INTERVAL_SECONDS)

    summary = summarize(results, threshold, top_k)
    RESULT_JSON.write_text(
        json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2),
        encoding="utf-8")
    print(f"结果已写入：{RESULT_JSON}")
    write_report(summary, results, dry_run=False)

    print("\n===== 指标汇总 =====")
    for key, value in summary.items():
        print(f"  {key}: {value:.1%}" if isinstance(value, float) else f"  {key}: {value}")


if __name__ == "__main__":
    main()
