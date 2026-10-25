import http from '@/api/http'

// =========================================================
// ① 文件上传
// =========================================================

/** 上传 Excel 文件（支持 .xlsx / .xls），后端 POI 流式解析后分块入库 */
export function uploadExcel(file: File, onProgress?: (pct: number) => void) {
  const formData = new FormData()
  formData.append('file', file)

  return http.post('/excel/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
}

// =========================================================
// ② 文档查询
// =========================================================

export function getDocumentList(pageNum = 1, pageSize = 20) {
  return http.get('/excel/document/list', { params: { pageNum, pageSize } })
}

export function getDocument(id: number) {
  return http.get(`/excel/document/${id}`)
}

// =========================================================
// ③ 数据加载
// =========================================================

export function loadAllCelldata(docId: number, sheetId: number) {
  return http.get(`/excel/document/${docId}/sheet/${sheetId}/all`)
}

/** 按分块索引范围加载 Sheet 数据（start/end 均为 chunkIndex，含端点） */
export function loadSheetChunks(docId: number, sheetId: number, start = 0, end = Number.MAX_SAFE_INTEGER) {
  return http.get(`/excel/document/${docId}/sheet/${sheetId}/chunks`, {
    params: { start, end },
  })
}

/** 上传工作簿图片，图片本体落本地/MinIO，库里保存公开访问 URL */
export function uploadImageAsset(file: File, documentId?: number, onProgress?: (pct: number) => void) {
  const formData = new FormData()
  formData.append('file', file)
  if (documentId != null) formData.append('documentId', String(documentId))
  return http.post('/excel/assets', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
}

// =========================================================
// ④ 数据写入
// =========================================================

export function batchUpdateCells(docId: number, updates: Record<string, unknown>[]) {
  return http.put(`/excel/document/${docId}/cells/batch`, updates)
}

export function saveWorkbook(docId: number, sheets: Record<string, unknown>[], baseVersion?: number) {
  return http.put(`/excel/document/${docId}/workbook`, {
    sheets,
    ...(baseVersion != null ? { baseVersion } : {}),
  })
}

// =========================================================
// ⑤ 文档管理
// =========================================================

export function renameDocument(id: number, name: string) {
  return http.put(`/excel/document/${id}/name`, { name })
}

export function deleteDocument(id: number) {
  return http.delete(`/excel/document/${id}`)
}

// =========================================================
// ⑥ 版本管理 & 回收站
// =========================================================

export function createDocumentVersion(docId: number, summary?: string) {
  return http.post(`/excel/document/${docId}/versions`, summary ? { summary } : {})
}
export function listDocumentVersions(docId: number, pageNum = 1, pageSize = 20) {
  return http.get(`/excel/document/${docId}/versions`, { params: { pageNum, pageSize } })
}
export function previewDocumentVersion(docId: number, versionId: number) {
  return http.get(`/excel/document/${docId}/versions/${versionId}/preview`)
}
export function restoreDocumentVersion(docId: number, versionId: number) {
  return http.post(`/excel/document/${docId}/versions/${versionId}/restore`)
}
export function clearRecycleBin() {
  return http.post('/excel/document/recycle/clear')
}

// ⑦ 审计 & 撤销
export async function getDocumentHistory(docId: number) {
  const res = await http.get(`/excel/${docId}/audit/logs`)
  const records = res.data?.data ?? []
  return { records, total: Array.isArray(records) ? records.length : 0, current: 1 }
}
export function undoDocumentEdit(docId: number) { return http.post(`/excel/${docId}/audit/undo`) }
export function redoDocumentEdit(docId: number) { return http.post(`/excel/${docId}/audit/redo`) }

// ⑧ 分享 & 回收站
export function createShare(docId: number, role: string) {
  return http.post(`/excel/document/${docId}/share`, null, { params: { role } })
}
export function revokeShare(docId: number, token: string) {
  return http.delete(`/excel/document/${docId}/share/${token}`)
}
export function getRecycleList(page = 1, size = 20, keyword = '') {
  return http.get('/excel/document/recycle', { params: { pageNum: page, pageSize: size, keyword: keyword || undefined } })
}
export function purgeDocuments(ids: number[]) {
  return http.post('/excel/document/batch-purge', { ids })
}
export function restoreDocuments(ids: number[]) {
  return http.post('/excel/document/batch-restore', { ids })
}
export function batchDeleteDocuments(ids: number[]) {
  return http.post('/excel/document/batch-delete', { ids })
}

// ⑨ ACL 分享
export function listDocumentAcl(docId: number) {
  return http.get(`/excel/document/${docId}/acl`)
}
export function shareDocument(docId: number, userId: number | null, username: string | undefined, role: string) {
  return http.put(`/excel/document/${docId}/acl/share`, { userId, username, role })
}
export function revokeDocumentAcl(docId: number, userId: number) {
  return http.delete(`/excel/document/${docId}/acl/${userId}`)
}
export function transferDocumentOwner(docId: number, userId: number) {
  return http.put(`/excel/document/${docId}/transfer`, { userId })
}

// ⑩ 异步导出
export function createExportTask(docId: number) {
  return http.post(`/excel/export/document/${docId}`)
}
export function listExportTasks(pageNum = 1, pageSize = 20, keyword = '') {
  return http.get('/excel/export/tasks', { params: { pageNum, pageSize, keyword: keyword || undefined } })
}
export function getExportTask(taskId: number) {
  return http.get(`/excel/export/tasks/${taskId}`)
}
export function cancelExportTask(taskId: number) {
  return http.delete(`/excel/export/tasks/${taskId}`)
}
export function retryExportTask(taskId: number) {
  return http.post(`/excel/export/tasks/${taskId}/retry`)
}
export function downloadExportUrl(taskId: number) {
  return `/api/excel/export/tasks/${taskId}/download`
}

export function downloadExportTask(taskId: number) {
  return http.get(`/excel/export/tasks/${taskId}/download`, { responseType: 'blob' })
}
