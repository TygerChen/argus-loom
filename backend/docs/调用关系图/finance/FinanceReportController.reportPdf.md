# finance · FinanceReportController.reportPdf 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["FinanceReportController.reportPdf :127"]
  m2["PayrollService.payrollRowsByMonth :285"]
  m3["PayrollService.latestPayrollMonthOrCurrent :323"]
  m4["FinanceReportPdfService.exportReportPdf :70"]
  m5["PayrollService.financeSummary :205"]
  m6["FinanceReportPdfService.readPdf :94"]
  m7["FinanceReportPdfService.fileNameFor :104"]
  m8["PayrollService.validateMonth :396"]
  m9["PayrollService.latestPayrollMonth :472"]
  m10["FinanceReportPdfService.uploads :268"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m2 --> m8
  m3 --> m9
  m4 --> m10
```
