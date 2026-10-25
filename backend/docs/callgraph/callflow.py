#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
方法级调用链分析器（第二版）—— 复用 modflow.py 已跑通的类解析地基，
只补「方法体范围提取 + 方法体内调用点 BFS」，从入口方法下挖一条调用链，
输出 Mermaid 方法级流程图（节点带 方法名 + 文件:行号）。

用法（在 docs/callgraph 下，用真解释器 `python`）：
  python callflow.py auth AuthService login
  python callflow.py auth AuthController login
"""
import re
import sys

import modflow as M

KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "throw",
            "assert", "synchronized", "instanceof", "case", "do", "try", "else",
            "finally", "void", "record", "var", "default", "this", "super",
            "public", "private", "protected", "static", "final", "class"}


def method_body_ranges(src):
    """返回 [(name, start_line, end_line), ...]，行号均为 1 基。

    start_line = 方法签名 '{' 所在行；end_line = 方法体收尾 '}' 所在行。
    """
    res = []
    i = 0
    n = len(src)
    while i < n:
        if src[i] == '{':
            # 前导束：往左扫到上一个 ; } { 之间的文本
            j = i - 1
            while j >= 0 and src[j] not in ';}{':
                j -= 1
            head = src[j + 1:i]
            name = _method_name_from_head(head)
            if name:
                # 大括号配平（顶层闭合）
                depth = 1
                k = i + 1
                while k < n and depth:
                    if src[k] == '{':
                        depth += 1
                    elif src[k] == '}':
                        depth -= 1
                    k += 1
                start = src.count('\n', 0, i) + 1
                end = src.count('\n', 0, k) + 1
                res.append((name, start, end))
                i = k
                continue
        i += 1
    return res


def _method_name_from_head(head):
    """从签名字符串取方法名；无则回 None。

    方法签名通常以访问修饰符 / static / final 开头，据此与
    `new String(...) {` 这类构造/表达式 `{` 区分。
    不依赖剥离注解（注解剥离对参数内 `@Xxx(...)` 会贪婪吞掉方法名），
    而是取「最后一个修饰符 → head 末尾」一段作有效签名再提方法名，
    天然兼容多行参数、Controller 注解、构造器。
    """
    if re.search(r"\b(?:public\s+|abstract\s+|final\s+)*(?:class|interface|enum|record)\b", head):
        return None
    # 取最后一个修饰符字出现处，其后即为有效签名
    hits = list(re.finditer(r"\b(?:public|private|protected|static|final)\b", head))
    if not hits:
        return None
    sig = head[hits[-1].end():]
    m = re.search(r"([A-Za-z_$][\w$]*)\s*\([^{};]*\)\s*$", sig)
    if not m:
        return None
    name = m.group(1)
    if name in KEYWORDS:
        return None
    return name


def _is_record_projection(callname):
    """常见 bean 投影 getter/setter：getXxx/setXxx/isXxx/hasXxx。

    要求前缀后紧跟**大写字母**（Java bean 命名习惯），避免把 `issueToken`
    这类以 `is` 开头却是真业务的方法误杀。这些是叶子，不往下钻。"""
    if len(callname) <= 3:
        return False
    for stem in ("get", "set", "is", "has"):
        if callname.startswith(stem) and len(callname) > len(stem):
            nxt = callname[len(stem)]
            if nxt.isupper():
                return True
    return False


def find_entry(files, module, cls, mtd):
    for c in files:
        if c.module == module and c.simple == cls:
            return c
    return None


def call_sites(src_lines, start, end):
    """在 [start,end] 行区间（1 基含）里找 `recv.name(` / `name(`；
    返回 [(recv, fname, line), ...]。recv 为 None 表示本类裸调用。"""
    seg = src_lines[start - 1:end]
    text = '\n'.join(seg)
    CALL = re.compile(r"(?<![\w$.])()([A-Za-z_$][\w$]*)\s*(?:\.\s*([A-Za-z_$][\w$]*))?\s*\(")
    out = []
    for m in CALL.finditer(text):
        first = m.group(2)
        second = m.group(3)
        if second:
            recv, fname = first, second
        else:
            recv, fname = None, first
        line = text.count('\n', 0, m.start()) + start
        out.append((recv, fname, line))
    return out


def resolve_target(cur, recv, files, by_simple):
    """把调用点接收者解析到目标类；无法解析（局部变量/复杂链）返回 None。"""
    if recv is None:
        return cur
    t = cur.fields.get(recv)          # 字段类型
    if t:
        cands = by_simple.get(t)
        if cands and len(cands) == 1:
            return cands[0]
        return None
    if recv in cur.imp:               # import 的类名（静态工厂等）
        full = cur.imp[recv]
        for c in files:
            if c.full == full:
                return c
        return None
    return None


def mermaid_for(module, cls, mtd):
    """从某类某方法做 BFS 下钻，返回 Mermaid 字符串（节点图 + 边）。"""
    files, by_simple = M.build_index()
    entry = find_entry(files, module, cls, mtd)
    if entry is None:
        return f"%% 未找到类 {cls} 于模块 {module}"

    load = {}

    def bodies_of(c):
        """返回 (方法体字典 name->(s,e), 源行列表)；读文件一次并缓存。"""
        key = id(c)
        if key not in load:
            raw = open(c.path, encoding='utf-8', errors='replace').read()
            src = M.strip_comments(raw)
            d = {}
            for name, s, e in method_body_ranges(src):
                d.setdefault(name, (s, e))
            load[key] = (d, src.split('\n'))
        return load[key]

    entry_bodies, _ = bodies_of(entry)
    if mtd not in entry_bodies:
        return f"%% 类 {cls} 无方法 {mtd}（现有 {sorted(entry_bodies)}）"

    from collections import deque
    ctr = [0]

    def nid():
        ctr[0] += 1
        return f"m{ctr[0]}"

    root = nid()
    label = {root: f"{cls}.{mtd} :{entry_bodies[mtd][0]}"}
    owner = {root: (entry, mtd)}
    edges = []
    order = [root]
    seen = {(id(entry), mtd)}
    dq = deque([root])

    while dq:
        cur = dq.popleft()
        cc, mm = owner[cur]
        bodies, lines = bodies_of(cc)
        s, e = bodies[mm]
        for recv, name, line in call_sites(lines, s, e):
            if _is_record_projection(name):
                continue
            tgt = resolve_target(cc, recv, files, by_simple)
            if tgt is None:
                continue
            tb, _ = bodies_of(tgt)
            if name not in tb:
                continue
            key = (id(tgt), name)
            if key in seen:
                continue
            seen.add(key)
            nn = nid()
            label[nn] = f"{tgt.simple}.{name} :{tb[name][0]}"
            owner[nn] = (tgt, name)
            order.append(nn)
            edges.append((cur, nn))
            dq.append(nn)

    out = ["flowchart TD"]
    for k in order:
        out.append(f'  {k}["{label[k]}"]')
    out.append("")
    for a, b in edges:
        out.append(f"  {a} --> {b}")
    return "\n".join(out)


def main():
    if len(sys.argv) < 3:
        print("用法: python callflow.py <module> <ClassName> <method>")
        return
    module, cls, mtd = sys.argv[1].lower(), sys.argv[2], sys.argv[3]
    print(mermaid_for(module, cls, mtd))


if __name__ == "__main__":
    main()