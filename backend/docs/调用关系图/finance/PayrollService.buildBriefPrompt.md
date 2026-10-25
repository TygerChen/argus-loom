# finance · PayrollService.buildBriefPrompt 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["PayrollService.buildBriefPrompt :341"]
  m2["PayrollService.validateMonth :396"]
  m3["PayrollService.financeOverviewForPrompt :359"]
  m4["PayrollService.payrollRowsByMonth :285"]
  m5["PayrollService.financeSummary :205"]
  m6["PayrollService.zeroLedgerMap :373"]
  m7["PayrollService.latestPayrollMonthOrCurrent :323"]
  m8["PayrollService.latestPayrollMonth :472"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m3 --> m6
  m5 --> m7
  m7 --> m8
```
