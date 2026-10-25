#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Argus-Loom 后端 · 模块级 类→类 调用依赖图（第一版，纯 Python，离线）

只做一件事且保证稳：把 src/main/java/com/argus/rag 下每类的
  import 简单名 + 字段注入类型  解析成「依赖的外部类」，
产出每个模块的 Mermaid 类依赖 flowchart（Controller→Service→Mapper/Engine）。

用法（在 docs/callgraph 下）：
    python3 modflow.py             # 全部模块
    python3 modflow.py AUTH        # 只 auth 模块
    python3 modflow.py AUTH QA EXCEL FINANCE DOCUMENT ASSISTANT GROUP ENGINE
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                    "src", "main", "java", "com", "argus", "rag"))

# 模块白名单（默认全量）
ALL_MODULES = ["auth", "user", "qa", "excel", "finance", "document", "assistant",
               "group", "engine", "ingestion", "common", "config", "metrics",
               "embedding", "eval", "llm", "prompt", "top", "structured", "rag"]


class C:
    __slots__ = ("path", "simple", "full", "module", "pkg", "annos", "imp", "fields", "deps")
    def __init__(self, path):
        self.path = path
        self.simple = ""
        self.full = ""
        self.module = ""
        self.pkg = ""
        self.annos = []   # 类级注解名
        self.imp = {}     # 简单名 -> 全限定名
        self.fields = {}  # 字段名 -> 简单类型
        self.deps = set() # 依赖的其它类(全限定名)


def strip_comments(src: str) -> str:
    """去 // 与 /** */；字符串压成单字符 'x'；注释内的换行保留为 '\n'，
    保证后续行号统计与真实文件一致（否则注释多行会让 { 的位置整体上移）。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c in ('"', "'"):
            q = c; j = i + 1; esc = False
            while j < n:
                ch = src[j]
                if esc: esc = False
                elif ch == '\\': esc = True
                elif ch == q: break
                j += 1
            out.append('x'); i = min(n, j + 1); continue
        if c == '/' and i + 1 < n:
            d = src[i + 1]
            if d == '/':
                j = src.find('\n', i)
                if j == -1:
                    i = n
                else:
                    out.append('\n')   # 只保留换行，丢掉 // 注释文本
                    i = j + 1
                continue
            if d == '*':
                j = src.find('*/', i + 2)
                if j == -1:
                    j = n - 1
                seg = src[i:j + 2]
                out.append(''.join('\n' if ch == '\n' else ' ' for ch in seg))
                i = j + 2
                continue
        out.append(c); i += 1
    return ''.join(out)


def parse_class(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        raw = f.read()
    src = strip_comments(raw)
    c = C(path)
    m = re.search(r"package\s+([\w.]+)\s*;", raw)
    if m:
        c.pkg = m.group(1)
        c.full = c.pkg + "." + os.path.basename(path)[:-5]
        seg = c.pkg.split('.')
        c.module = seg[3] if len(seg) >= 4 else seg[-1]
    c.simple = os.path.basename(path)[:-5]
    # 类级注解（常见交通）
    for am in re.finditer(r"@(\w+)", raw[:3000]):
        c.annos.append(am.group(1))
    # import：简单名 -> 全限定名
    for im in re.finditer(r"import\s+([\w.]+)\.(\w+)\s*;", raw):
        c.imp[im.group(2)] = im.group(1) + "." + im.group(2)
    # 字段注入类型：private Type name ;  /  private final Type name ;
    for fm in re.finditer(r"private\s+(?:final\s+)?([A-Za-z_$][\w$.<>?,\[\] ]*?)\s+([A-Za-z_$]\w*)\s*(?:=|;)", src):
        c.fields[fm.group(2)] = fm.group(1).strip()
    # 依赖：类注解 类型 + 字段类型里出现的「我们项目内类」
    return c


def build_index():
    files = []
    for dp, _, fns in os.walk(ROOT):
        for fn in fns:
            if fn.endswith(".java"):
                try:
                    files.append(parse_class(os.path.join(dp, fn)))
                except Exception:
                    pass
    # 简单名 -> Class 列表；唯一则可用
    by_simple = {}
    for c in files:
        by_simple.setdefault(c.simple, []).append(c)
    # 解析每个类的依赖（仅当简单名在其它类里唯一）
    for c in files:
        type_names = set()
        # 字段类型里的「去掉泛型」简单名
        for t in c.fields.values():
            base = t.split('.')[-1].split('[')[0].split(',')[0].replace(' ', '')
            type_names.add(base)
        # 字段类型可能在 import 里
        for simple_t in type_names:
            cands = by_simple.get(simple_t)
            if cands:
                if len(cands) == 1:
                    tgt = cands[0]
                    if tgt is not c:
                        c.deps.add(tgt)
        # 直接 import 里出现的我们类（Java col import），限字段已涵盖，无序再扩
    return files, by_simple


def fmt_node(c):
    # 显示类型图标
    anno = " ".join(c.annos)
    if "RestController" in anno or "Controller" in anno: role = "Ctl"
    elif "Service" in c.simple or "Service" in anno: role = "Svc"
    elif "Mapper" in c.simple or "Mapper" in anno: role = "MPR"
    elif "Configuration" in anno or "Config" in anno: role = "Cfg"
    else: role = ""
    return f"{c.simple}" + (f" ({role})" if role else "")


def mod_mermaid(files, modules):
    lines = ["flowchart TD"]
    # 给所有类分配节点 id（跨模块引用的对端也要有 id 才能连边）
    idx = {}
    for i, c in enumerate(files):
        idx[id(c)] = f"n{i}"
    # 节点：只声明本模块内的类
    nodes = [c for c in files if c.module in modules]
    idxs = set()
    for c in nodes:
        nid = idx[id(c)]
        idxs.add(id(c))
        lines.append(f'  {nid}["{c.simple}"]')
    # 边：两端都在本模块内的才连
    edges = set()
    for c in nodes:
        for dep in c.deps:
            if dep.module in modules:
                edges.add((idx[id(c)], idx[id(dep)]))
    lines.append("")
    for a, b in sorted(edges):
        lines.append(f"  {a} --> {b}")
    return "\n".join(lines)


def main():
    args = [a.upper() for a in sys.argv[1:]]
    files, _ = build_index()
    if not args:
        # 打印各模块文件数与常用注解分布，作为概览
        from collections import Counter
        cnt = Counter(c.module for c in files)
        print("模块文件数统计:")
        for m, n in sorted(cnt.items()):
            print(f"  {m:12} {n}")
        print("\n(mermaid: 传模块名，例如 AUTH QA)")
        return
    mods = [m.lower() for m in args if m.lower() in ALL_MODULES]
    if not mods:
        print("未知模块。可用: AUTH USER QA ...")
        return
    print(mod_mermaid(files, mods))


if __name__ == "__main__":
    main()