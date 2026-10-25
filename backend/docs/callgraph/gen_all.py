#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量生成「方法调用关系流程图」并落盘 docs/调用关系图/<module>/。

- PLAN 每模块列「入口类」；对每个类自动枚举其全部真实业务方法
  （跳过构造器：方法名==类名；跳过 getter/简单投影），逐方法调用
  callflow.mermaid_for(...) 生成方法级 Mermaid 图。
- 每模块再产一张「类→类」依赖总览图（复用 modflow.mod_mermaid）。
- 落盘根目录：docs/调用关系图/<module>/，每图一个 .md。

用真解释器跑（本机为 `python`）：
    python gen_all.py            # 生成全部模块
    python gen_all.py auth excel # 只生成指定模块
"""
import os
import re
import sys

import callflow
import modflow as M

# 本文件位于 docs/callgraph/ → 落盘根目录 = backend/docs/调用关系图
OUT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "调用关系图"))

# 每模块的「入口类」清单（覆盖全部模块；以 Controller 与核心 Service 为主入口）
PLAN = {
    "auth": ["AuthController", "AuthService"],
    "user": ["AccountController", "AdminUserController", "AccountService"],
    "qa": ["QaController", "QaRecordController", "QaChatService"],
    "assistant": ["AssistantChatController", "AssistantConversationController",
                  "AssistantSessionController", "AssistantAgentFacade"],
    "document": ["DocumentController", "DocumentIngestionAsyncService"],
    "excel": ["ExcelImportController", "ExcelDocumentController", "StructuredDataController",
              "ExcelFileController", "FinanceImportController", "ExcelVersionController",
              "ExcelAssetController", "OperationLogController", "ShareController",
              "CollabController", "ExcelRecycleBinController"],
    "finance": ["FinanceReportController", "PayrollService"],
    "group": ["GroupManagementController", "GroupJoinRequestController",
              "GroupQueryController", "InvitationDecisionController", "GroupService"],
    "metrics": ["AdminMetricsController", "LlmUsageStatisticsService", "LlmUsageCollectorImpl"],
    "config": ["ModelController", "GenerationController"],
    "ingestion": ["DocumentIngestionAsyncService", "DocumentIngestionProcessor",
                  "ChunkService", "DocumentParserFactory"],
}


def list_methods(module, cls, files):
    """返回某类全部业务方法名（跳过构造器、类名、getter/投影）。"""
    simple = cls.rsplit(".", 1)[-1]
    target = None
    for c in files:
        if c.module == module and c.simple == simple:
            target = c
            break
    if target is None:
        return []
    raw = open(target.path, encoding="utf-8", errors="replace").read()
    src = M.strip_comments(raw)
    out = []
    for name, s, e in callflow.method_body_ranges(src):
        # 构造器：方法名==类简单名
        if name == target.simple:
            continue
        # 入口为 getter/简单投影没意义
        if callflow._is_record_projection(name):
            continue
        out.append(name)
    return out


def main():
    inc = {a.lower() for a in sys.argv[1:]} or None
    files, _ = M.build_index()
    done = skip = mrk = 0
    for module, classes in PLAN.items():
        if inc and module not in inc:
            continue
        for ckey in classes:
            simple = ckey.rsplit(".", 1)[-1]
            for mtd in list_methods(module, simple, files):
                try:
                    g = callflow.mermaid_for(module, simple, mtd)
                except Exception as e:  # noqa: BLE001
                    mrk += 1
                    continue
                if g.startswith("%%"):
                    skip += 1
                    continue
                p = _write_md(module, simple, mtd, g)
                done += 1
        _class_overview(module, files)
        print(f"[{module}] 方法级 {done_scope(module)} · 已生成类总览")
    print(f"\n完成. ✔ {done} 图 / 跳过 {skip} / 异常 {mrk}")


def done_scope(module):
    import glob, os
    d = os.path.join(OUT_ROOT, module)
    if not os.path.isdir(d):
        return "(无)"
    return f"{len([f for f in glob.glob(os.path.join(d, '*.md')) if '总览' not in f])} 个方法"


def _write_md(module, cls, mtd, mermaid):
    d = os.path.join(OUT_ROOT, module)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, f"{cls}.{mtd}.md")
    body = (
        f"# {module} · {cls}.{mtd} 调用链\n\n"
        "> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。\n"
        "> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；"
        "getter/简单投影已省略。\n\n"
        f"```mermaid\n{mermaid}\n```\n"
    )
    with open(p, "w", encoding="utf-8") as f:
        f.write(body)
    return p


def _class_overview(module, files):
    mm = M.mod_mermaid(files, [module])
    d = os.path.join(OUT_ROOT, module)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "00_类级总览.md")
    body = (
        f"# {module} 模块 · 类级调用总览\n\n"
        "> 本图展示模块内 Class→Class 依赖总览（Controller→Service→Mapper/Engine），不带方法名；"
        "想看方法名请打开同目录下对应的方法级图。由 `docs/callgraph/modflow.py` 生成。\n\n"
        f"```mermaid\n{mm}\n```\n"
    )
    with open(p, "w", encoding="utf-8") as f:
        f.write(body)


if __name__ == "__main__":
    main()