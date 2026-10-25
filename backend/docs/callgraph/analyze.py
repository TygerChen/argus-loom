#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Argus-Loom 后端 · 方法调用关系静态分析器（纯 Python，零第三方依赖，离线可跑）

职责：
  1) 解析 src/main/java/com/argus/rag 下全部 .java 的 包/类/import/字段/方法/调用点。
  2) 按 import + 字段类型把 `xu.xxx()` 的接收者解析到目标类；裸调用解析到本类方法。
  3) 产 Mermaid：
     - 模块级：一类(module)内 Class→Class 的调用依赖图。
     - 方法级：从某个入口方法按调用链 BFS 下挖的详细流程图，节点带 文件:行号。

用法（在 docs/callgraph 下运行）：
    python3 analyze.py modules             # 全模块类级调用图（概览）
    python3 analyze.py modules AUTH        # 只看 auth 模块
    python3 analyze.py flow auth AuthService login
"""
import os
import re
import sys

# 相对本文件( docs/callgraph ) → backend/src/main/java/com/argus/rag
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                    "src", "main", "java", "com", "argus", "rag"))

CALL = re.compile(r"(?<![\w$.])(?P<a>[A-Za-z_$]\w*)\s*(?:\.\s*(?P<b>[A-Za-z_$]\w*?)\s*)?(?<![\w$])\(")
# 过滤伪调用点（控制流／关键字／声明）
KEYWORDS = {"if","for","while","switch","catch","return","new","throw","assert",
            "synchronized","instanceof","case","do","try","else","finally","void",
            "process","set","get"}
# 跳过声明/控制行（不把这些当方法调用）
SKIP_A = KEYWORDS


class M:      # 一个方法
    __slots__ = ("name","line","vis","kind","start","end")
    def __init__(s, name, line, vis, kind, start, end):
        s.name, s.line, s.vis, s.kind = name, line, vis, kind
        s.start, s.end = start, end           # 方法体行范围（含边界, 1 基）

class C:      # 一个类
    __slots__ = ("path","simple","full","module","annos","imp","fields","methods","calls")
    def __init__(s, path):
        s.path=path; s.simple=s.full=s.module=""; s.pkg=""
        s.annos=[]; s.imp={}; s.fields={}; s.methods=[]; s.calls=[]

def strip(src):
    """去 // 与 /* */ ；字符串压成 'x' ；保留换行/列号位置。"""
    n=len(src); out=[]; i=0
    while i<n:
        c=src[i]
        if c=='"' or c=="'":
            q=c; j=i+1; esc=False
            while j<n:
                ch=src[j]
                if esc: esc=False
                elif ch=='\\': esc=True
                elif ch==q: break
                j+=1
            out.append('x'); i=min(n,j+1); continue
        if c=='/' and i+1<n:
            d=src[i+1]
            if d=='/':
                j=src.find('\n',i); i=n if j==-1 else j; continue
            if d=='*':
                j=src.find('*/',i+2); i=n if j==-1 else j+2; continue
        out.append(c); i+=1
    return ''.join(out)

def parse(path):
    with open(path,encoding='utf-8',errors='replace') as f: raw=f.read()
    src=strip(raw)
    c=C(path)
    m=re.search(r"package\s+([\w.]+)\s*;",raw)
    if m:
        c.pkg=m.group(1); c.full=m.group(1)+"."+os.path.basename(path)[:-5]
        seg=m.group(1).split('.')
        # 模块 = com.argus.rag.<MODULE> 的第 4 段
        c.module=seg[3] if len(seg)>=4 else (seg[-1])
        c.simple=os.path.basename(path)[:-5]
    c.annos=re.findall(r"@(\w+)",out_str(raw[:4000]&raw[:4000]))
    for im in re.finditer(r"import\s*(?:static\s+)?([\w.]+)\.(\w+)\s*;",raw):
        c.imp[im.group(2)]=im.group(1)+"."+im.group(2)
    for fm in re.finditer(r"(?:private|protected|public)?\s*(?:final\s+)?([A-Za-z_$][\w$.<>?,\[\] ]*?)\s+([A-Za-z_$]\w*)\s*(?:=|;)",src):
        t=fm.group(1).strip().split('.')[-1].replace(' ','')
        c.fields[fm.group(2)]=t
    # --- 方法体区间（配对 ++count）---
    # 扫描 "签名（ ）{"，得到方法名/行/体区间
    scan(c,src)
    # --- 调用点：全文件找 裸名(...) 或 recv.name(...) ---
    for mm in CALL.finditer(src):
        a=mm.group('a').strip(); b=mm.group('b')
        if not a or a in KEYWORD_A:
            continue
        line=src.count('\n',0,mm.start())+1
        c.calls.append(Call(mm.start(),line,a,b))
    return c

SKIP_A = {"if","for","while","switch","catch","return","new","throw","assert","synchronized",
          "instanceof","case","cast","try","final","record","var","def","default","void","package"}
END_B = None  # placeholder

def scan(c,src):
    """定位方法体区间：匹配 '{'..'}' 顶层大括号，判断其前导是否为方法签名。"""
    n=len(src); i=0; paren=0; last_bound=0
    # 记录每个 '{' filler：前导文本（同一括号层），判断是否方法
    # 简化：遇 '{' 时，往前找最近 ';' 或 '}' 或 '{' 之间的文本 -> head
    while i<n:
        ch=src[i]
        if ch=='{':
            head=src[last_bound:i]
            # head 形如  "...name(...)(" 采样右端
            hm=re.findall(r"([A-Za-z_$]\w*)\s*\([^()]*\)\s*$",head)
            # head 尾部是否方法签名：匹配  return? name(params)  且不会以 'new' 结尾
            mm=re.search(r"(?:public|protected|private|static|final|\b[\w<>.,\[\] ]*?)\s*([A-Za-z_$]\w*)\s*\([^()]*\)\s*$",head)
            if mm and not(head.rstrip().endswith('new')):
                name=mm.group(1)
                j=i+1; bal=1
                while j<n:
                    chj=src[j]
                    if chj=='{': bal+=1
                    elif chj=='}':
                        bal-=1
                        if bal==0: break
                    j+=1
                start=src.count('\n',0,i)+1
                end=src.count('\n',0,j)+1
                vis='pub' if re.match(r"^\s*public\b",head) else ('pri' if re.match(r"^\s*private\b",head) else ('pro' if re.match(r"^\s*protected\b",head) else 'pkg'))
                c.methods.append(M(name,start,vis,'method',start,end))
                i=j+1; last_boundary=j+1; continue
            last_boundary=i+1; i+=1;
            continue
        if ch in ';}':
            last_boundary=i+1
        i+=1

# ================= 索引 =================
def build():
    files=[]
    for dp,_,fns in os.walk(ROOT):
        for fn in fns:
            if fn.endswith('.java'):
                try: files.append(parse(os.path.join(dp,fn)))
                except Exception: pass
    by_simple={}
    for c in files:
        by_simple.setdefault(c.simple,[]).append(c)
    return files,by_simple

def find_class(files,module,name):
    for c in files:
        if c.module==module and c.simple==name: return c
    return None

def resolve_recv(files,by_simple,ci,recv):
    """接收者 recv -> 目标 Class 或 None。可：字段类型 / import / 包类。"""
    if not recv: return None
    # 字段类型
    ft=ci.fields.get(recv)
    if ft:
        cands=by_simple.get(ft)
        if cands and len(cands)==1: return cands[0]
        return None
    # import 的直接对应（如 ApiResponse.success 静态工厂, recv=ApiResponse）
    if recv in ci.imp:
        full=ci.imp[recv]
        for c in CI:
            if c.full==full: return c
    return None