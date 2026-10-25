/* eslint-disable @typescript-eslint/no-explicit-any -- finance batches mirror arbitrary workbook JSON */

import http from '@/api/http'

function unwrap<T>(response: any): T {
  if (response?.data?.success === false) {
    throw new Error(response.data.message || '请求失败')
  }
  return response?.data?.data as T
}

export function importFinanceFile(file: File, params: Record<string, string> = {}) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/finance/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    params,
    timeout: 300000,
  }).then((res) => unwrap<Record<string, any>>(res))
}

export function getFinanceOverview(params: Record<string, any> = {}) {
  return http.get('/finance/overview', { params }).then((res) => unwrap<Record<string, any>>(res))
}

export function getAccountSummaries(params: Record<string, any> = {}) {
  return http.get('/finance/summary/accounts', { params }).then((res) => unwrap<any[]>(res))
}

export function getDailySummaries(params: Record<string, any> = {}) {
  return http.get('/finance/summary/daily', { params }).then((res) => unwrap<any[]>(res))
}

export function getFinanceRecords(params: Record<string, any> = {}) {
  return http.get('/finance/records', { params }).then((res) => unwrap<Record<string, any>>(res))
}

export function getFinanceBatchList(params: Record<string, any> = {}) {
  return http.get('/finance/import-batches', { params }).then((res) => unwrap<Record<string, any>>(res))
}

export function updateFinanceRecords(records: Record<string, any>[]) {
  return http.put('/finance/records/batch', records).then((res) => unwrap<Record<string, any>>(res))
}

export function getBatchVersions(bookKey: string) {
  return http.get('/finance/batch-versions', { params: { bookKey } }).then((res) => unwrap<any[]>(res))
}

export function activateBatchVersion(batchId: number) {
  return http.post(`/finance/batches/${batchId}/activate`).then((res) => unwrap<Record<string, any>>(res))
}

// ─────────────────────────────────────────────
// 考勤薪酬台账
// ─────────────────────────────────────────────

export interface PayrollRow {
  id?: number
  userId: number
  payrollMonth: string
  workDays?: number
  baseSalary?: number
  performanceBonus?: number
  attendanceBonus?: number
  overtimePay?: number
  allowance?: number
  subsidy?: number
  otherBonus?: number
  socialSecurity?: number
  housingFund?: number
  personalTax?: number
  deduction?: number
  otherDeduction?: number
  netSalary?: number
  payStatus?: string
  payDate?: string | null
  remark?: string
}

export function getPayrollPage(month: string, pageNum = 1, pageSize = 100) {
  return http.get('/finance/payroll', { params: { month, pageNum, pageSize } })
    .then((res) => unwrap<Record<string, any>>(res))
}

export function savePayrollRows(rows: Array<Record<string, any>>) {
  return http.put('/finance/payroll/batch', rows).then((res) => unwrap<Record<string, any>>(res))
}

export function saveAttendanceRows(rows: Array<Record<string, any>>) {
  return http.put('/finance/attendance/batch', rows).then((res) => unwrap<Record<string, any>>(res))
}

export function getMyPayroll(month?: string) {
  return http.get('/finance/hr/self/payroll', { params: { month } })
    .then((res) => unwrap<Record<string, any>>(res))
}

export function getMyAttendance(year?: number, month?: number) {
  return http.get('/finance/hr/self/attendance', { params: { year, month } })
    .then((res) => unwrap<Record<string, any>>(res))
}

export function getMyAttendanceSummary(month?: string) {
  return http.get('/finance/hr/self/attendance/summary', { params: { month } })
    .then((res) => unwrap<Record<string, any>>(res))
}

export function getFinanceCompactSummary() {
  return http.get('/finance/summary/compact').then((res) => unwrap<Record<string, any>>(res))
}

export function generateFinanceReport(month?: string) {
  return http.post('/finance/report/generate', null, { params: { month }, timeout: 120000 })
    .then((res) => unwrap<Record<string, any>>(res))
}

export function getFinanceReports(pageNum = 1, pageSize = 20) {
  return http.get('/finance/reports', { params: { pageNum, pageSize } })
    .then((res) => unwrap<Record<string, any>>(res))
}

export function getReportPdf(reportId: number) {
  return http.post(`/finance/reports/${reportId}/pdf`, null, { responseType: 'blob', timeout: 120000 })
}
