import http from '@/api/http'

// =========================================================
// 任务进度日报（通用日报引擎实例 #2：在线协作表数据源）
// 后端：/api/reports/task-daily（登录即可用，数据按 owner 隔离）
// =========================================================

/** 任务轨迹/日报执行记录（后端 ReportExecution 实体） */
export interface TaskReportExecution {
  id: number
  definitionId: number
  bizDate: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  resultJson: string | null
  aiBriefText: string | null
  htmlContent: string | null
  pushStatus: 'PENDING' | 'SENT' | 'SKIPPED' | 'FAILED' | null
  pushDetail: string | null
  errorMessage: string | null
  durationMs: number | null
}

/** 绑定信息（source 段：type/documentId/sheetId/documentName/sheetName） */
export type TaskReportBinding = Record<string, unknown>

/** 当前绑定（未绑定时 data 为空对象） */
export function getBinding() {
  return http.get('/reports/task-daily/binding')
}

/** 绑定任务表（取文档首个活跃 Sheet） */
export function bindTaskTable(documentId: number) {
  return http.post('/reports/task-daily/bind', { documentId })
}

/** 生成/重跑指定日日报（缺省今天） */
export function generateTaskReport(date?: string) {
  return http.post('/reports/task-daily/generate', null, { params: date ? { date } : {} })
}

/** 单日日报 */
export function getTaskReport(date?: string) {
  return http.get('/reports/task-daily', { params: date ? { date } : {} })
}

/** 历史列表（缺省最近 30 天） */
export function listTaskReports(from?: string, to?: string) {
  return http.get('/reports/task-daily/list', { params: { from, to } })
}
