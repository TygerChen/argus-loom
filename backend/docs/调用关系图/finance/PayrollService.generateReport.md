# finance · PayrollService.generateReport 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["PayrollService.generateReport :233"]
  m2["PayrollService.validateMonth :396"]
  m3["PayrollService.buildBriefPrompt :341"]
  m4["PayrollService.financeReportInstructions :335"]
  m5["PayrollService.cut :467"]
  m6["PayrollService.financeOverviewForPrompt :359"]
  m7["PayrollService.payrollRowsByMonth :285"]
  m8["PayrollService.financeSummary :205"]
  m9["PayrollService.zeroLedgerMap :373"]
  m10["PayrollService.latestPayrollMonthOrCurrent :323"]
  m11["PayrollService.latestPayrollMonth :472"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m6 --> m9
  m8 --> m10
  m10 --> m11
```
