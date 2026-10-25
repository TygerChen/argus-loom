/**
 * useEditHistory：服务端撤销/重做 + 编辑历史面板。
 *
 * <p>从 SheetEditor.vue 拆出的第三个 composable。这里的撤销重做是
 * "服务端级"——与编辑器内置 Ctrl+Z 不同：它按批次反向重放历史记录并
 * 广播给协作者，所以执行前必须先把本地修改保存完（ensureSavedForHistory），
 * 且每个目标格的当前值仍等于历史 after 值才允许撤销（防并发覆盖，
 * 服务端校验）。</p>
 *
 * <p>historyChangePreview 等展示函数也在这里：它们只服务历史面板模板，
 * 跟着数据逻辑走，读代码时不用跳回组件找。</p>
 */
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDocumentHistory, undoDocumentEdit, redoDocumentEdit } from '@/api/excel'

export function useEditHistory(deps) {
  const {
    documentId,           // Ref<Long>
    canEdit,              // Ref<boolean>
    saving,               // Ref<boolean>
    hasUnsavedChanges,    // ComputedRef<boolean>
    univerAPI,            // () => UniverAPI | null
    workbookAdapter,      // () => adapter | null
    _getCurrentSheetId,   // () => string | null
    applyRemoteCells,     // (cells) => void（useCollabSync 提供）
    acceptBaselineNow,    // () => void（重置基线 + 清脏标记，组件提供）
    saveChanges,          // () => Promise<void>（组件三级保存编排）
    isCollabSavePending   // () => boolean（实时保存是否还有未确认批次）
  } = deps

  const historyBusy = ref(false)
  const historyDialogVisible = ref(false)
  const historyLoading = ref(false)
  const historyData = ref([])
  const historyPagination = reactive({ current: 1, pageSize: 10, total: 0 })

  /** 分页拉取编辑历史（批次聚合：谁、何时、改了哪些格） */
  async function loadHistory(targetPage = historyPagination.current) {
    if (historyLoading.value) return
    historyLoading.value = true
    try {
      const data = (await getDocumentHistory(
        documentId.value,
        targetPage,
        historyPagination.pageSize
      )) || {}
      historyData.value = Array.isArray(data.records) ? data.records : []
      historyPagination.total = Number(data.total ?? 0)
      historyPagination.current = Number(data.current ?? targetPage)
    } catch (error) {
      ElMessage.error(`历史加载失败：${error.message}`)
    } finally {
      historyLoading.value = false
    }
  }

  function openHistory() {
    historyDialogVisible.value = true
    void loadHistory(1)
  }

  /**
   * 历史操作前置：确保本地修改已落库。
   * saveChanges 后用轮询等"实时保存 ack 全部核销 + saving 关闭"，
   * 上限 25 秒（与实时保存 15 秒降级超时同数量级）。
   */
  async function ensureSavedForHistory() {
    if (!hasUnsavedChanges.value) return true

    await saveChanges()
    const deadline = Date.now() + 25000
    while (
      Date.now() < deadline &&
      (isCollabSavePending() || saving.value)
    ) {
      await new Promise((resolve) => setTimeout(resolve, 150))
    }
    return !hasUnsavedChanges.value
  }

  /** 撤销/重做共用编排：权限 → 先保存 → 调 API → 应用结果 → 刷新面板 */
  async function performHistoryReversal(apiCall, successText) {
    if (!canEdit.value) {
      ElMessage.warning('当前文档为只读')
      return
    }
    if (!univerAPI() || !workbookAdapter()) {
      ElMessage.warning('表格尚未初始化')
      return
    }
    if (historyBusy.value) return

    if (!(await ensureSavedForHistory())) {
      ElMessage.warning('请先保存当前修改，再执行撤销或重做')
      return
    }

    historyBusy.value = true
    try {
      const data = (await apiCall(documentId.value)) || {}
      if (Array.isArray(data.cells) && data.cells.length > 0) {
        applyRemoteCells(data.cells)
      }
      acceptBaselineNow()
      if (historyDialogVisible.value) void loadHistory(historyPagination.current)
      ElMessage.success(successText)
    } catch (error) {
      ElMessage.error(`${error.message || '操作失败'}`)
    } finally {
      historyBusy.value = false
    }
  }

  function undoHistoryChange() {
    void performHistoryReversal(undoDocumentEdit, '撤销成功')
  }

  function redoHistoryChange() {
    void performHistoryReversal(redoDocumentEdit, '重做成功')
  }

  /** 全局快捷键：Ctrl+Z 撤销 / Ctrl+Shift+Z 或 Ctrl+Y 重做（输入框内不抢） */
  function handleHistoryKeydown(event) {
    const modifier = event.ctrlKey || event.metaKey
    if (!modifier) return
    const target = event.target
    if (
      target &&
      (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
    ) {
      return
    }
    const key = String(event.key || '').toLowerCase()
    if (key === 'z') {
      event.preventDefault()
      if (event.shiftKey) redoHistoryChange()
      else undoHistoryChange()
    } else if (key === 'y') {
      event.preventDefault()
      redoHistoryChange()
    }
  }

  // =========================================================
  // 历史面板展示辅助（模板使用）
  // =========================================================

  /** 从批次记录里按优先级取第一个存在的字段（新老记录字段名不一致） */
  function pickHistoryField(record, keys) {
    for (const key of keys) {
      const value = record?.[key]
      if (value !== null && value !== undefined && value !== '') return value
    }
    return '-'
  }

  function formatHistoryTime(value) {
    if (!value) return '-'
    return String(value).replace('T', ' ').substring(0, 19)
  }

  const HISTORY_ACTION_LABELS = {
    CHANGE: '修改',
    UNDO: '撤销',
    REDO: '重做'
  }

  function historyActionLabel(action) {
    return HISTORY_ACTION_LABELS[action] || action || '-'
  }

  function historyActionType(action) {
    if (action === 'UNDO') return 'warning'
    if (action === 'REDO') return 'success'
    return 'primary'
  }

  /**
   * 明细行的坐标文本："第 3 行 第 2 列"；结构批次等没有坐标的明细显示 "-"。
   * （结构保存不产生单元格坐标，历史上这里渲染出 NaN）
   */
  function historyCellPos(change) {
    const rowNo = Number(change?.rowNo)
    const colNo = Number(change?.colNo)
    if (!Number.isFinite(rowNo) || !Number.isFinite(colNo)) return '-'
    return `第 ${rowNo + 1} 行 第 ${colNo + 1} 列`
  }

  /** 值对象 → 人能读懂的文本（空对象/空串 → "（空）"） */
  function formatHistoryValue(value) {
    if (value === null || value === undefined || value === '') return '（空）'
    if (typeof value === 'object') {
      if (typeof value.v === 'string' || typeof value.v === 'number') return String(value.v)
      if (value.v === null) return '（空）'
      return JSON.stringify(value)
    }
    return String(value)
  }

  /** 批次行的摘要文本：显示"改了什么"（来源 + 影响） */
  function historyChangePreview(row) {
    const source = pickHistoryField(row, ['source', 'sourceType'])
    const cellCount = Number(row.cellCount ?? row.cell_count ?? 0)
    const summary = pickHistoryField(row, ['summary'])
    if (summary !== '-') return summary
    return `${historyActionLabel(row.action)} · ${source} · ${cellCount} 格`
  }

  return {
    historyBusy,
    historyDialogVisible,
    historyLoading,
    historyData,
    historyPagination,
    loadHistory,
    openHistory,
    ensureSavedForHistory,
    performHistoryReversal,
    undoHistoryChange,
    redoHistoryChange,
    handleHistoryKeydown,
    pickHistoryField,
    historyCellPos,
    formatHistoryTime,
    historyActionLabel,
    historyActionType,
    formatHistoryValue,
    historyChangePreview
  }
}
