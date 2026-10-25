/**
 * useRemotePresence：远端协作者的"可见存在"——选区高亮 + 光标徽章层。
 *
 * <p>从 SheetEditor.vue 拆出的第一个 composable。职责：收到他人的
 * selection-updated 消息后画高亮框与头像徽章，本端选区变化时把消息发出去
 * （120ms 节流），并在滚动/缩放时重排徽章位置。</p>
 *
 * <p>依赖全部显式注入：univerAPI/workbookAdapter 用 getter（实例会被整册
 * 重建），applyingRemoteCells 用 getter（应用远端单元格时暂停本端广播）。
 * 本模块不持有 Univer 实例，组件销毁时调用 disposePresence() 释放全部
 * 定时器、监听器与高亮。</p>
 */
import { ref } from 'vue'
import { normalizeSelectionRange, avatarColor } from '@/utils/collabHelpers'

export function useRemotePresence(deps) {
  const {
    univerAPI,            // () => UniverAPI | null
    workbookAdapter,      // () => adapter | null
    myUserId,             // Ref<number|string|null>
    applyingRemoteCells,  // () => boolean
    isOnline,             // () => boolean（协同通道在线）
    send                  // (message) => boolean（协同消息发送）
  } = deps

  // 每个远端用户一份高亮句柄 + 一个"最后已知位置"（徽章定位用）
  const remoteHighlights = new Map()
  const remoteCursorRanges = new Map()
  /** 模板渲染的徽章列表 [{userId, username, nickname, left, top, color}] */
  const remoteBadges = ref([])

  let selectionDisposable = null
  let selectionSendTimer = null
  let lastSelectionPayload = null
  let cursorLayoutTimer = null
  let cursorScrollDisposable = null

  // =========================================================
  // 本端选区 → 广播
  // =========================================================

  /** 挂载本端选区监听：只在"不是在应用远端消息"时广播，避免回声循环 */
  function attachSelectionBroadcast() {
    const workbook = univerAPI()?.getActiveWorkbook?.()
    if (!workbook?.onSelectionChange) return
    selectionDisposable = workbook.onSelectionChange((selections) => {
      if (applyingRemoteCells()) return
      scheduleSelectionSend(selections)
    })
  }

  /** 120ms 节流发送选区（高频拖选时避免消息风暴） */
  function scheduleSelectionSend(selections) {
    if (!isOnline()) return
    const payload = buildSelectionPayload(selections)
    if (!payload) return
    lastSelectionPayload = payload
    if (selectionSendTimer) return
    selectionSendTimer = window.setTimeout(() => {
      selectionSendTimer = null
      if (!lastSelectionPayload) return
      send({
        type: 'selection',
        sheetId: lastSelectionPayload.sheetId,
        ranges: lastSelectionPayload.ranges
      })
    }, 120)
  }

  /** 本端选区 → {sheetId(服务端 ID), ranges[]}；隐藏 Sheet 或无选区返回 null */
  function buildSelectionPayload(selections) {
    const workbook = univerAPI()?.getActiveWorkbook?.()
    const sheet = workbook?.getActiveSheet?.()
    if (!workbookAdapter() || !sheet) return null
    const serverSheetId = workbookAdapter().getServerSheetId(sheet.getSheetId())
    if (serverSheetId == null) return null

    const ranges = (Array.isArray(selections) ? selections : [])
      .slice(0, 32)
      .map(normalizeSelectionRange)
      .filter((range) => range !== null)
    return { sheetId: serverSheetId, ranges }
  }

  // =========================================================
  // 远端选区 → 高亮 + 徽章
  // =========================================================

  /** 画某个远端用户的高亮框，并记录徽章位置 */
  function applyRemoteSelection(message) {
    const sender = message.sender
    const senderId = sender?.userId ?? sender?.username
    if (senderId == null) return
    if (myUserId.value != null && String(sender.userId) === String(myUserId.value)) return

    const selection = message.selection || {}
    const ranges = Array.isArray(selection.ranges) ? selection.ranges : []
    clearRemoteSelection(senderId)
    if (ranges.length === 0) return

    const workbook = univerAPI()?.getActiveWorkbook?.()
    if (!workbook) return
    const localSheetId = workbookAdapter()?.getLocalSheetId(selection.sheetId)
    if (!localSheetId) return
    const sheet = workbook.getSheetBySheetId(localSheetId)
    if (!sheet || typeof sheet.highlightRanges !== 'function') return

    const color = avatarColor(sender)
    const franges = []
    for (const range of ranges) {
      const startRow = Number(range.startRow)
      const endRow = Number(range.endRow)
      const startColumn = Number(range.startColumn)
      const endColumn = Number(range.endColumn)
      if (
        !Number.isFinite(startRow) || !Number.isFinite(endRow) ||
        !Number.isFinite(startColumn) || !Number.isFinite(endColumn)
      ) {
        continue
      }
      try {
        franges.push(
          sheet.getRange(startRow, startColumn, endRow - startRow + 1, endColumn - startColumn + 1)
        )
      } catch {
        // ignore malformed remote ranges
      }
    }
    if (franges.length === 0) return

    const disposable = sheet.highlightRanges(franges, {
      stroke: color,
      strokeWidth: 2,
      fill: `${color}33`,
      widgets: {},
      widgetSize: 0,
      rowHeaderFill: `${color}26`,
      columnHeaderFill: `${color}26`
    })
    remoteHighlights.set(String(senderId), { disposable })
    if (ranges.length > 0) {
      remoteCursorRanges.set(String(senderId), {
        sheetId: localSheetId,
        startRow: Number(ranges[0].startRow),
        startColumn: Number(ranges[0].startColumn)
      })
      scheduleCursorLayout()
    } else {
      remoteCursorRanges.delete(String(senderId))
      scheduleCursorLayout()
    }
  }

  /** 30ms 合并重排请求，滚动时徽章跟着单元格走 */
  function scheduleCursorLayout() {
    if (cursorLayoutTimer) return
    cursorLayoutTimer = window.setTimeout(() => {
      cursorLayoutTimer = null
      layoutRemoteCursors()
    }, 30)
  }

  /** 依据 remoteCursorRanges 重新计算每个徽章的像素位置 */
  function layoutRemoteCursors() {
    const workbook = univerAPI()?.getActiveWorkbook?.()
    const badges = []
    for (const [userId, entry] of remoteCursorRanges) {
      const sheet = workbook?.getSheetBySheetId(entry.sheetId)
      if (!sheet) continue
      let left = -9999
      let top = -9999
      try {
        const rect = sheet
          .getRange(entry.startRow, entry.startColumn)
          .getCellRect?.()
        if (rect && Number.isFinite(rect.left) && Number.isFinite(rect.top)) {
          left = rect.left + 2
          top = rect.top + 2
        }
      } catch {
        // getCellRect 在部分 Univer 版本不可用，徽章退化为屏幕外
      }
      const user = deps.findUser(userId)
      if (!user) continue
      badges.push({
        userId,
        username: user.username,
        nickname: user.nickname || user.username,
        left,
        top,
        color: avatarColor(user)
      })
    }
    remoteBadges.value = badges
  }

  /** 清掉某个用户的高亮与徽章（他断线或清空选区时） */
  function clearRemoteSelection(userId) {
    const entry = remoteHighlights.get(String(userId))
    if (entry?.disposable?.dispose) {
      try {
        entry.disposable.dispose()
      } catch {
        // dispose 失败不影响主流程
      }
    }
    remoteHighlights.delete(String(userId))
    if (remoteCursorRanges.delete(String(userId))) {
      scheduleCursorLayout()
    }
  }

  /** 清空全部远端高亮（本端离线或整册重建时） */
  function clearAllRemoteSelections() {
    for (const [, entry] of remoteHighlights) {
      try {
        entry?.disposable?.dispose?.()
      } catch {
        // ignore
      }
    }
    remoteHighlights.clear()
    remoteCursorRanges.clear()
    scheduleCursorLayout()
  }

  /** 在线名单变化时同步：消失的用户清高亮 */
  function syncRemoteSelectionsWithPresence(users) {
    const ids = new Set((Array.isArray(users) ? users : []).map((user) => String(user.userId)))
    for (const userId of remoteHighlights.keys()) {
      if (!ids.has(userId)) clearRemoteSelection(userId)
    }
  }

  /** 容器滚动/窗口缩放时重排徽章（passive 监听，不阻塞滚动） */
  function attachCursorLayoutListeners() {
    const container = document.getElementById('univer-container')
    if (!container) return
    const schedule = () => scheduleCursorLayout()
    container.addEventListener('scroll', schedule, { passive: true })
    window.addEventListener('resize', schedule)
    cursorScrollDisposable = {
      dispose() {
        container.removeEventListener('scroll', schedule)
        window.removeEventListener('resize', schedule)
      }
    }
  }

  /** 组件销毁/整册重建时释放全部资源 */
  function disposePresence() {
    selectionDisposable?.dispose?.()
    selectionDisposable = null
    if (selectionSendTimer) {
      clearTimeout(selectionSendTimer)
      selectionSendTimer = null
    }
    if (cursorLayoutTimer) {
      clearTimeout(cursorLayoutTimer)
      cursorLayoutTimer = null
    }
    cursorScrollDisposable?.dispose?.()
    cursorScrollDisposable = null
    lastSelectionPayload = null
    clearAllRemoteSelections()
    remoteBadges.value = []
    remoteCursorRanges.clear()
  }

  return {
    remoteBadges,
    remoteCursorRanges,
    attachSelectionBroadcast,
    applyRemoteSelection,
    clearRemoteSelection,
    clearAllRemoteSelections,
    syncRemoteSelectionsWithPresence,
    attachCursorLayoutListeners,
    scheduleCursorLayout,
    disposePresence
  }
}
