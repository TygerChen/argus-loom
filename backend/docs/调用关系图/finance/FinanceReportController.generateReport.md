# finance · FinanceReportController.generateReport 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["FinanceReportController.generateReport :113"]
  m2["ApiResponse.success :24"]
  m3["PayrollService.generateReport :233"]
  m4["PayrollService.validateMonth :396"]
  m5["PayrollService.buildBriefPrompt :341"]
  m6["PayrollService.financeReportInstructions :335"]
  m7["PayrollService.cut :467"]
  m8["PayrollService.financeOverviewForPrompt :359"]
  m9["PayrollService.payrollRowsByMonth :285"]
  m10["PayrollService.financeSummary :205"]
  m11["PayrollService.zeroLedgerMap :373"]
  m12["PayrollService.latestPayrollMonthOrCurrent :323"]
  m13["PayrollService.latestPayrollMonth :472"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m3 --> m6
  m3 --> m7
  m5 --> m8
  m5 --> m9
  m5 --> m10
  m8 --> m11
  m10 --> m12
  m12 --> m13
```
