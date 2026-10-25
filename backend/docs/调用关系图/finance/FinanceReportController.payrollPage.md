# finance · FinanceReportController.payrollPage 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["FinanceReportController.payrollPage :57"]
  m2["ApiResponse.success :24"]
  m3["PayrollService.pagePayrollByMonth :72"]
  m4["PayrollService.validateMonth :396"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
```
