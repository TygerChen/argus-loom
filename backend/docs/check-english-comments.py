#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
英文残留注释扫描器（argus-loom 中文注释周计划的配套工具）。

用途：
    扫描指定 Java 目录/文件，找出「纯英文注释行」（含英文、不含中文的行），
    以及「完全没有任何注释」的源文件，供后续逐条翻译成中文 / 补上中文注释。

用法：
    python docs/check-english-comments.py ["目录或文件路径"]

    不带参数时，默认扫描 src/main/java/com/argus/rag 全部源文件。
    也可只扫某个模块，例如：
        python docs/check-english-comments.py src/main/java/com/argus/rag/engine

判定规则：
    1. 注释行 = 去掉首尾空白后以 //、/* 或 * 开头，且非空行；
    2. "纯英文" = 该行含英文字母 [A-Za-z]，但**不含**任何汉字 [一-鿿]；
    3. "含中文" = 该行含至少一个汉字；
    4. 其它（如纯符号的分隔注释 ----）不计入英文残留。
"""
import os
import re
import sys

# 默认扫描根
DEFAULT_ROOT = "src/main/java/com/argus/rag"

_CN = re.compile(r"[一-鿿]")            # 是否含汉字
_LAT = re.compile(r"[A-Za-z]")                 # 是否含英文字母
# 是注释行：以 //x、//、/* 或 单个/多个 * 开头（* 行常见于 Javadoc 内部）
_COMM = re.compile(r"^\s*(//[^/]|/\*|\*+|//)")


_HTTP_CODE = re.compile(r"\b\d{2,3}\s+[A-Z][a-z]+")   # 如 "401 Unauthorized"、"500 Internal Server Error"
_HTML_TAG = re.compile(r"</?[a-zA-Z][^>]*>")              # 完整 HTML 标签，如 <p>、</ul>、<pre>、</pre>
_CODE_MARK = re.compile(r"(\{@code|<pre>|\{@link)")     # Javadoc 代码块 / 语法链接
_ANNOTATION = re.compile(r"^\*?\s*@[A-Z]")              # 如 * @RateLimit(...) —— 注释里的注解示例行
# Javadoc 元数据标记（@author/@since/@param/@return/@see/@throws/@inheritDoc/@version/@deprecated 等）：
# 这些是文档标记与标识，不是叙述句，无需翻译。
_JAVADOC_TAG = re.compile(r"^@[a-zA-Z][a-zA-Z]*(\s|$)|^\{@")


def _bare(line):
    """去掉注释行前缀记号，返回真正要看的正文。

    把行首的 //、/**、*/、一个或多个 * 剥掉（Javadoc 内部行是 "* 正文"），
    这样后续能干净地判断：剥完后如果只剩排版标签，就判定为无需翻译。
    """
    s = line.strip()
    while True:
        if s.startswith("/**"):
            s = s[3:]
        elif s.startswith("/*"):
            s = s[2:]
        elif s.startswith("//"):
            s = s[2:]
        elif s.startswith("*"):
            s = s[1:]
        else:
            break
        s = s.lstrip()
    return s.strip()


def _ignore(line):
    """一行是否属于『不该翻译』的英文？

    返回 True 表示这不是"英文叙述性注释"，无需翻译成中文：
    - 纯 HTML 标签行（Javadoc 排版用的 <ul>/<p>/<td> 等）；
    - Javadoc 代码块 / 语法链接（<pre>{@code ...}</pre>、@某注解示例）；
    - HTTP 标准状态码注释（如 "401 Unauthorized"）——状态码名是前后端通用标准术语，翻译反而有害。
    """
    body = _bare(line)
    # 去掉 HTML 标签后如果已无任何内容，则纯属排版标签，无需翻译
    if not _HTML_TAG.sub("", body).strip():
        return True
    if _CODE_MARK.search(body) or _ANNOTATION.search(body):
        return True
    if _HTTP_CODE.search(body):
        return True
    if _JAVADOC_TAG.match(body):
        return True
    return False


def describe(line):
    """返回注释行的语言类别：'en'（纯英文）、'zh'（含中文）、'sym'（纯符号/可忽略）。"""
    if _ignore(line):
        return "sym"
    if _LAT.search(line) and not _CN.search(line):
        return "en"
    if _CN.search(line):
        return "zh"
    return "sym"


def scan(path):
    """扫描单个文件，返回 (总注释行, 纯英文行, 含中文行, 纯英文行号列表)。"""
    comm = en = zh = sym = 0
    en_lines = []
    in_pre = False  # 处于 <pre>…</pre> 代码/DSL 演示块内（非叙述，不计入"需翻译"，但计入注释行）
    with open(path, encoding="utf-8") as f:
        for idx, line in enumerate(f, start=1):
            if not line.strip():
                continue
            if not _COMM.match(line):
                continue
            comm += 1
            # <pre> 起始：该行及其后续行直到 </pre> 都视为排版/代码演示，非叙述注释
            if not in_pre and "<pre>" in line:
                in_pre = True
                sym += 1
                continue
            if in_pre:
                if "</pre>" in line:
                    in_pre = False
                sym += 1
                continue
            tag = describe(line)
            if tag == "en":
                en += 1
                en_lines.append(idx)
            elif tag == "zh":
                zh += 1
            else:
                sym += 1
    return comm, en, zh, en_lines


def walk(root):
    """遍历根目录下所有 .java 文件。"""
    for dp, _, fns in os.walk(root):
        for fn in sorted(fns):
            if fn.endswith(".java"):
                yield os.path.join(dp, fn)


def main(argv=None):
    argv = argv or sys.argv[1:]
    targets = argv or [DEFAULT_ROOT]
    files = []
    for t in targets:
        if os.path.isdir(t):
            files += list(walk(t))
        elif os.path.isfile(t):
            files.append(t)

    # 汇总：模块名 -> 统计
    from collections import defaultdict
    stats = defaultdict(lambda: dict(files=0, comm=0, en=0, zh=0))
    detail = []  # (文件, 注释行数, 纯英文行数, 纯英文行号列表)
    no_comment = []

    seen = set()
    for p in files:
        key = os.path.normpath(os.path.abspath(p))
        if key in seen:
            continue
        seen.add(key)
        comm, en, zh, en_lines = scan(p)
        # 模块 = 相对 src/main/java/com/argus/rag 的顶级目录名（根下散落的单文件记为 "top"）
        rel = os.path.relpath(p, DEFAULT_ROOT)
        mod = rel.split(os.sep)[0] if os.sep in rel else "top"
        stats[mod]["files"] += 1
        stats[mod]["comm"] += comm
        stats[mod]["en"] += en
        stats[mod]["zh"] += zh
        if en:
            detail.append((rel, comm, en, en_lines))
        if comm == 0:
            no_comment.append(rel)

    # --- 输出汇总表 ---
    print(f"{'模块':<12}{'文件':>6}{'注释行':>8}{'纯英文':>7}{'含中文':>7}")
    total_f = total_c = total_e = total_z = 0
    for k in sorted(stats):
        d = stats[k]
        print(f"{k:<30}{d['files']:>6}{d['comm']:>8}{d['en']:>7}{d['zh']:>7}")
        total_f += d["files"]
        total_c += d["comm"]
        total_e += d["en"]
        total_z += d["zh"]
    print("-" * 58)
    print(f"{'合计':<30}{total_f:>6}{total_c:>8}{total_e:>7}{total_z:>7}")

    # --- 2. 纯英文注释文件清单（按残留行数降序）---
    print("\n=== 需要翻译的纯英文注释（按行数降序）===")
    detail.sort(key=lambda x: (-x[2], x[0]))
    if not detail:
        print("  (无)")
    for rel, comm, en, en_lines in detail:
        print(f"  纯英文 {en:>3} 行  {rel}")

    # --- 3. 完全无注释的文件 ---
    print("\n=== 完全没有注释、需要补上中文说明的文件 ===")
    if not no_comment:
        print("  (无)")
    for rel in sorted(no_comment):
        print(f"  {rel}")


if __name__ == "__main__":
    main()