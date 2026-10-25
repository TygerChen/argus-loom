#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 评估测试集生成器。

生成两样东西：
1. eval_docs/*.md      —— 3 篇内容完全已知的测试文档（事实只存在于这些文档里，标准答案因此可控）
2. testset.json        —— 40 条测试用例：36 条正例（应回答）+ 4 条负例（应拒答）

用法：
    python prepare_testset.py            # 文件不存在时生成
    python prepare_testset.py --force    # 强制覆盖重新生成

之后把 eval_docs/ 里的文档上传到某个群组知识库（run_eval.py --prepare 可自动完成），
再运行 run_eval.py 即可得到真实指标。
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DOCS_DIR = HERE / "eval_docs"
TESTSET = HERE / "testset.json"

# ---------------------------------------------------------------------------
# 测试文档：所有"事实"只写在这里。评估用例的 expected_answer_keywords
# 必须能从这些文档中找到依据；负例问题则刻意不包含任何文档事实。
# ---------------------------------------------------------------------------
DOCS = {
    "公司差旅报销制度.md": """# 公司差旅报销制度

## 住宿标准
员工出差住宿报销标准如下：一线城市（北京、上海、广州、深圳）每晚不超过 500 元，
二线城市每晚不超过 350 元，其他城市每晚不超过 280 元。
超支部分需事先经部门总监邮件批准，否则不予报销。

## 交通标准
跨城市出行原则上乘坐高铁二等座，单程超过 8 小时的高铁线路可以申请一等座。
乘坐飞机需提前 3 个工作日申请，经济舱报销，选座费与行李费自理。
市内交通凭发票实报实销，单日上限 100 元。

## 餐费补贴
出差期间餐费补贴为每天 100 元，无需提供发票。
当天往返且出差时间超过 8 小时的，按半天补贴 50 元发放。

## 报销流程与期限
差旅结束后 15 个工作日内，员工需在财务系统中提交报销单，
并上传注明公司全称抬头的增值税发票。超过期限的报销单需要总经理特批。
报销款在财务审核通过后 5 个工作日内打入工资卡。

## 违规处理
虚报行程或伪造发票属于严重违纪，一经查实全额追回报销款，
并按公司员工手册给予警告直至解除劳动合同的处分。
""",
    "员工考勤与请假制度.md": """# 员工考勤与请假制度

## 工作时间
公司实行每周五天工作制，工作日上班时间为 9:00 至 18:00，
午休 12:00 至 13:30。研发岗位可申请弹性上班，但不得晚于 10:00 到岗。

## 迟到与早退
单月迟到 3 次以内且每次不超过 10 分钟的不作处理；
第 4 次起每次扣款 50 元。迟到超过 30 分钟按半天事假计算。
早退参照迟到规则执行。

## 请假类型
年假：入职满 1 年不满 10 年的每年 5 天，满 10 年不满 20 年的每年 10 天，
满 20 年的每年 15 天。年假可拆分使用，当年未休完可顺延至次年 3 月底。

病假：需提供三级甲等医院开具的诊断证明，病假期间工资按当地最低工资标准的 80% 发放。

事假：全年累计不超过 15 天，事假期间不带薪。连续事假超过 5 天需提前一周申请。

婚假：法定婚假 3 天，符合晚婚条件的另加 7 天，需在登记后 6 个月内一次性使用。

## 加班
工作日加班按 1.5 倍工资计算或选择 1:1 调休，法定节假日加班按 3 倍工资计算。
加班需事先在考勤系统中提交申请并经直属主管审批。
""",
    "智能知识库平台操作手册.md": """# 智能知识库平台操作手册

## 登录与账号
平台使用企业统一账号登录，首次登录后请立即修改初始密码。
密码必须包含大小写字母、数字且长度不少于 8 位。
连续 5 次输错密码账号将被锁定 30 分钟。

## 上传知识文档
进入"文档管理"页面后点击上传按钮，支持 txt、md、pdf、docx 四种格式，
单个文件不超过 10MB，更大文件请使用分片上传，上限 256MB。
同一群组内重复上传相同文件时系统会秒传去重。
文档上传后系统自动进行解析、清洗、切片并向量化，一般 1 分钟内完成，
状态变为 READY 后即可被问答检索到。

## 知识问答
在"知识问答"页面选择群组后即可提问，回答附带引用来源，
点击引用可跳转到原文档对应位置。当知识库中没有足够依据时，
系统会明确拒答而不是编造答案。

## 在线表格
在线表格支持多人实时协同编辑，单元格级冲突按"后写入优先"解决。
系统每 5 分钟自动保存版本快照，最多保留 10 个版本，可随时回滚。
表格可导出为 xlsx 文件，大文件的导出任务在后台异步执行，完成后在导出中心下载。

## 权限说明
知识库按群组隔离，用户只能检索到自己所在群组的文档。
财务类文档仅对财务角色可见，普通管理员也无法查看。
""",
}

# ---------------------------------------------------------------------------
# 测试用例。
# expected_files      —— 引用命中的判定：引用文件名包含其中任意一个即算检索命中
# expected_keywords   —— 答案要点：answer 文本中需覆盖的关键词（按覆盖比例计分）
# should_answer       —— False 表示知识库中无依据，系统应当拒答（answered=false）
# ---------------------------------------------------------------------------
CASES = [
    # ---------- 差旅报销制度（12 正例） ----------
    dict(id=1, question="去北京出差住酒店一晚最多能报销多少钱？",
         expected_files=["公司差旅报销制度"], expected_keywords=["500", "一线"]),
    dict(id=2, question="出差餐费补贴一天是多少？",
         expected_files=["公司差旅报销制度"], expected_keywords=["100"]),
    dict(id=3, question="差旅报销需要在出差结束后多少天内提交？",
         expected_files=["公司差旅报销制度"], expected_keywords=["15"]),
    dict(id=4, question="报销款审核通过后多久到账？",
         expected_files=["公司差旅报销制度"], expected_keywords=["5"]),
    dict(id=5, question="坐飞机出差需要提前多久申请？",
         expected_files=["公司差旅报销制度"], expected_keywords=["3"]),
    dict(id=6, question="二线城市的住宿报销标准是多少？",
         expected_files=["公司差旅报销制度"], expected_keywords=["350"]),
    dict(id=7, question="高铁什么时候可以坐一等座？",
         expected_files=["公司差旅报销制度"], expected_keywords=["8", "一等座"]),
    dict(id=8, question="当天往返出差有餐补吗？",
         expected_files=["公司差旅报销制度"], expected_keywords=["50", "半天"]),
    dict(id=9, question="市内交通报销有上限吗？",
         expected_files=["公司差旅报销制度"], expected_keywords=["100"]),
    dict(id=10, question="虚报差旅发票会怎么处理？",
         expected_files=["公司差旅报销制度"], expected_keywords=["追回", "违纪"]),
    dict(id=11, question="住宿超标了还能报销吗？",
         expected_files=["公司差旅报销制度"], expected_keywords=["总监", "批准"]),
    dict(id=12, question="飞机的选座费和行李费公司给报吗？",
         expected_files=["公司差旅报销制度"], expected_keywords=["自理"]),

    # ---------- 考勤与请假制度（14 正例） ----------
    dict(id=13, question="年假有几天？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["5", "10", "15"]),
    dict(id=14, question="入职满十年年假是多少天？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["10"]),
    dict(id=15, question="迟到会扣钱吗？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["50", "3"]),
    dict(id=16, question="请病假需要什么证明？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["三甲", "诊断"]),
    dict(id=17, question="事假一年最多可以请多少天？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["15"]),
    dict(id=18, question="婚假有几天？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["3", "7"]),
    dict(id=19, question="法定节假日加班工资怎么算？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["3", "倍"]),
    dict(id=20, question="工作日加班可以调休吗？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["调休", "1.5"]),
    dict(id=21, question="病假期间工资怎么发？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["80%", "最低工资"]),
    dict(id=22, question="公司几点上班？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["9:00", "18:00"]),
    dict(id=23, question="年假没休完可以顺延吗？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["次年", "3"]),
    dict(id=24, question="连续请事假超过 5 天要注意什么？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["一周", "提前"]),
    dict(id=25, question="研发岗位可以弹性上班吗？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["10:00", "弹性"]),
    dict(id=26, question="加班需要审批吗？",
         expected_files=["员工考勤与请假制度"], expected_keywords=["审批", "主管"]),

    # ---------- 操作手册（10 正例） ----------
    dict(id=27, question="平台支持上传哪些格式的文档？",
         expected_files=["操作手册"], expected_keywords=["txt", "pdf", "docx"]),
    dict(id=28, question="直接上传的文档最大不能超过多少？更大的文件怎么办？",
         expected_files=["操作手册"], expected_keywords=["10MB", "分片", "256"]),
    dict(id=29, question="文档上传后多久可以被问答检索到？",
         expected_files=["操作手册"], expected_keywords=["1 分钟", "READY"]),
    dict(id=30, question="重复上传同一个文件会怎样？",
         expected_files=["操作手册"], expected_keywords=["秒传"]),
    dict(id=31, question="系统什么时候会拒答？",
         expected_files=["操作手册"], expected_keywords=["拒答", "依据"]),
    dict(id=32, question="在线表格多人同时编辑冲突了怎么办？",
         expected_files=["操作手册"], expected_keywords=["后写入"]),
    dict(id=33, question="表格版本快照多久保存一次？保留多少个版本？",
         expected_files=["操作手册"], expected_keywords=["5 分钟", "10"]),
    dict(id=34, question="密码有什么要求？",
         expected_files=["操作手册"], expected_keywords=["8", "大小写"]),
    dict(id=35, question="财务类文档谁能看到？",
         expected_files=["操作手册"], expected_keywords=["财务"]),
    dict(id=36, question="表格导出是大文件时要怎么处理？",
         expected_files=["操作手册"], expected_keywords=["异步", "导出"]),

    # ---------- 负例（4 条，知识库中不存在，应当拒答） ----------
    dict(id=37, question="公司年会定在哪个酒店举办？",
         expected_files=[], expected_keywords=[], should_answer=False),
    dict(id=38, question="公司股票代码是什么？",
         expected_files=[], expected_keywords=[], should_answer=False),
    dict(id=39, question="年度体检安排在几月份？",
         expected_files=[], expected_keywords=[], should_answer=False),
    dict(id=40, question="公司食堂的午餐价格是多少？",
         expected_files=[], expected_keywords=[], should_answer=False),
]


def main() -> None:
    force = "--force" in sys.argv
    DOCS_DIR.mkdir(parents=True, exist_ok=True)

    for name, body in DOCS.items():
        path = DOCS_DIR / name
        if path.exists() and not force:
            print(f"已存在，跳过：{path}")
            continue
        path.write_text(body, encoding="utf-8")
        print(f"已生成测试文档：{path}")

    payload = {
        "note": "由 prepare_testset.py 生成；expected_keywords 应与 eval_docs 中事实一一对应",
        "hit_rate_k": 5,
        "answer_coverage_threshold": 0.6,
        "cases": CASES,
    }
    if TESTSET.exists() and not force:
        print(f"已存在，跳过：{TESTSET}")
    else:
        TESTSET.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"已生成测试集（{len(CASES)} 条用例）：{TESTSET}")


if __name__ == "__main__":
    main()
