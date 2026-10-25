/**
 * useCollabSync：协同通道的生命周期与消息处理。
 *
 * <p>从 SheetEditor.vue 拆出的第二个 composable。职责：</p>
 * <ul>
 *   <li>建立/断开 WebSocket（collabClient）；</li>
 *   <li>分发服务端消息（welcome/presence/cells-updated/sync/selection/workbook-updated/error）；</li>
 *   <li>把远端单元格变更应用到本地 Univer（applyRemoteCells，期间拉起
 *       applyingRemoteCells 闸门防止编辑监听误标脏、选区广播回声）；</li>
 *   <li>实时保存通道：sendChangedCellsViaCollab 发出 cell-edits 并登记
 *       pendingCollabSaves，15 秒未确认自动降级 HTTP；acceptCollabSave
 *       收到 ack 后按合并/冲突结果提示并重置基线。</li>
 * </ul>
 *
 * <p>结构变更（workbook-updated）只转发给组件回调——整册重建涉及
 * dispose/init 生命周期，留在组件里。文档版本号 lastKnownDocumentVersion
 * 由组件持有（bootWorkbook/saveChanges 都要读写），这里通过 deps.docVersion
 * 的 get/set 访问。</p>
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createCollabClient } from '@/utils/collabClient'
import { getAccessToken } from '@/utils/auth'
import { remoteLuckyToUniver } from '@/utils/collabHelpers'

/** 实时保存单次确认超时（毫秒）；超时降级走 HTTP 增量接口 */
const COLLAB_SAVE_TIMEOUT_MS = 15000

export function useCollabSync(deps) {
  const {
    documentId,           // Ref<Long>
    canEdit,              // Ref<boolean>
    saving,               // Ref<boolean>
    workbookDirty,        // Ref<boolean>
    univerAPI,            // () => UniverAPI | null
    workbookAdapter,      // () => adapter | null
    getCurrentSheetId,    // () => string | null
    docVersion,           // { get: () => number|null, set: (v) => void }
    onWorkbookUpdated,    // (message) => void（组件内整册重载）
    onPresenceSync,       // (users) => void（同步远端高亮，见 useRemotePresence）
    onRemoteSelection,    // (message) => void（画远端高亮）
    onOffline             // () => void（离线时清高亮）
  } = deps

  const collabStatus = ref('offline')
  const onlineUsers = ref([])
  const myUserId = ref(null)

  let collabClient = null
  let collabSequence = 0
  /** 应用远端消息期间为 true：编辑监听不标脏、选区不回声 */
  let applyingRemoteCells = false
  /** 有实时保存等待 ack（配合 pendingCollabSaves 判断"是否真的保存完了"） */
  let collabAckPending = false
  /** requestId → 发送格数；ack 逐个核销 */
  const pendingCollabSaves = new Map()

  /** 建立协同连接（重复调用会先释放旧连接） */
  function connectCollab() {
    if (!documentId.value || !getAccessToken()) return
    collabClient?.dispose?.()
    collabClient = createCollabClient({
      documentId: documentId.value,
      onStatus: (status) => {
        collabStatus.value = status
        if (status === 'offline') {
          onlineUsers.value = []
          onOffline()
        }
      },
      onMessage: handleCollabMessage
    })
  }

  /** 释放连接并复位全部协同状态（整册重建/组件销毁时调用） */
  function disconnectCollab() {
    collabClient?.dispose?.()
    collabClient = null
    pendingCollabSaves.clear()
    collabAckPending = false
    collabStatus.value = 'offline'
    onlineUsers.value = []
    myUserId.value = null
  }

  /** 协同是否在线（选区广播、实时保存的前置条件） */
  function isOnline() {
    return !!collabClient && collabStatus.value === 'online'
  }

  function handleCollabMessage(message) {
    if (!message || typeof message !== 'object') return

    // 在线名单：welcome（含自己 ID 与文档版本基线）与 presence（进出房间）
    if (message.type === 'welcome' || message.type === 'presence') {
      onlineUsers.value = Array.isArray(message.users) ? message.users : []
      if (message.type === 'welcome') {
        if (message.me?.userId != null) {
          myUserId.value = message.me.userId
        }
        const incomingVersion = Number(message.documentVersion)
        if (Number.isFinite(incomingVersion)) {
          // 断线重连后发现版本变了 → 说明离线期间发生过结构保存，需要整册重载
          const versionChangedAfterReconnect =
            message.reconnected === true &&
            docVersion.get() != null &&
            incomingVersion !== docVersion.get()
          docVersion.set(incomingVersion)
          if (versionChangedAfterReconnect) {
            onWorkbookUpdated({ type: 'workbook-updated', version: incomingVersion })
          }
        }
      }
      collabStatus.value = 'online'
      onPresenceSync(message.users)
      return
    }

    if (message.type === 'cells-updated') {
      const requestId = message.requestId
      // 自己发的批次收到 ack；否则是别人的变更，直接应用
      if (requestId && pendingCollabSaves.has(requestId)) {
        acceptCollabSave(requestId, message.cells)
        return
      }
      applyRemoteCells(Array.isArray(message.cells) ? message.cells : [])
      return
    }

    if (message.type === 'sync') {
      // 断线补拉：重放丢失的 ops，并检查是否漏了结构变更
      applyRemoteCells(Array.isArray(message.ops) ? message.ops : [])
      const syncVersion = Number(message.documentVersion)
      if (
        Number.isFinite(syncVersion) &&
        docVersion.get() != null &&
        syncVersion !== docVersion.get()
      ) {
        docVersion.set(syncVersion)
        onWorkbookUpdated({ type: 'workbook-updated', version: syncVersion })
      }
      return
    }

    if (message.type === 'selection-updated') {
      onRemoteSelection(message)
      return
    }

    if (message.type === 'workbook-updated') {
      onWorkbookUpdated(message)
      return
    }

    if (
      message.type === 'error' &&
      ['save-failed', 'invalid-cells', 'forbidden'].includes(message.code)
    ) {
      // 服务端拒绝保存：放弃等待 ack，交给用户手动重试（降级逻辑已在超时里触发过则无 pending）
      pendingCollabSaves.clear()
      collabAckPending = false
      saving.value = false
      ElMessage.error(`实时保存失败：${message.message}`)
    }
  }

  /**
   * 把服务端最终结果应用到本地编辑器：rev 记录 + 单元格值。
   * applyingRemoteCells 闸门保证这期间的 CommandExecuted 不会把文档标脏。
   */
  function applyRemoteCells(cells) {
    const workbook = univerAPI()?.getActiveWorkbook?.()
    if (!workbook || !workbookAdapter()) return

    applyingRemoteCells = true
    try {
      for (const item of cells) {
        if (!item || !Number.isFinite(Number(item.r)) || !Number.isFinite(Number(item.c))) continue
        const localSheetId = workbookAdapter().getLocalSheetId(item.sheetId)
        if (!localSheetId) continue
        if (item.rev != null) {
          const publicText = item.v && typeof item.v === 'object' && item.v.v != null
            ? item.v.v
            : (item.v === null || item.v === undefined ? null : item.v)
          if (item.v == null || (typeof item.v === 'object' && Object.keys(item.v).length === 0)) {
            workbookAdapter.updateRevisionState(localSheetId, Number(item.r), Number(item.c), null)
          } else {
            workbookAdapter.updateRevisionState(localSheetId, Number(item.r), Number(item.c), item.rev, publicText)
          }
        }
        const sheet = workbook.getSheetBySheetId(localSheetId)
        if (!sheet) continue
        sheet
          .getRange(Number(item.r), Number(item.c))
          .setValue(remoteLuckyToUniver(item.v))
      }
    } catch (error) {
      console.warn('应用远端单元格失败:', error)
    } finally {
      applyingRemoteCells = false
    }
  }

  /** 广播"我保存好了"（其他人据此刷新基线），避免下一个人的结构保存误报冲突 */
  function notifyWorkbookSaved() {
    if (!isOnline()) return
    collabClient.send({
      type: 'workbook-saved',
      requestId: `wb-${Date.now()}-${++collabSequence}`
    })
  }

  /**
   * 实时保存首选通道：发出后登记 pending，15 秒未确认自动降级 HTTP。
   * @returns 是否走了实时通道（false = 调用方应直接走 HTTP）
   */
  function sendChangedCellsViaCollab(changedCells, onTimeoutFallback) {
    if (!canEdit.value || !isOnline()) return false

    const requestId = `collab-${Date.now()}-${++collabSequence}`
    const sent = collabClient.send({
      type: 'cell-edits',
      requestId,
      cells: changedCells
    })
    if (!sent) return false

    pendingCollabSaves.set(requestId, changedCells.length)
    collabAckPending = true
    window.setTimeout(() => {
      if (!pendingCollabSaves.has(requestId)) return
      pendingCollabSaves.delete(requestId)
      finishSavingIfIdle()
      ElMessage.warning('实时通道未确认，已改为增量接口保存')
      onTimeoutFallback(changedCells)
    }, COLLAB_SAVE_TIMEOUT_MS)
    return true
  }

  /** 自己批次收到 ack：核销、应用合并/冲突结果、重置基线与脏标记 */
  function acceptCollabSave(requestId, resultCells) {
    const count = pendingCollabSaves.get(requestId)
    if (count === undefined) return
    pendingCollabSaves.delete(requestId)

    const cells = Array.isArray(resultCells) ? resultCells : []
    const mergedCount = cells.filter((cell) => cell?.merged).length
    const conflictCount = cells.filter((cell) => cell?.conflict).length

    if (mergedCount > 0 || conflictCount > 0) {
      applyRemoteCells(cells)
    }

    if (univerAPI() && workbookAdapter()) {
      workbookAdapter().acceptBaseline(univerAPI().getActiveWorkbook().save(), getCurrentSheetId())
      workbookDirty.value = false
    }
    finishSavingIfIdle()

    if (mergedCount > 0) {
      ElMessage.warning(`已合并 ${mergedCount} 个并发修改的单元格`)
    }
    if (conflictCount > 0) {
      ElMessage.error(`${conflictCount} 个单元格与其他编辑冲突，已保留服务端最新内容`)
    }
    if (count > 0) {
      ElMessage.success(`已实时保存 ${count} 个单元格`)
    }
  }

  /** 所有批次都确认后才关闭 saving 态（一批文档可能拆成多个 pending） */
  function finishSavingIfIdle() {
    if (pendingCollabSaves.size > 0) return
    collabAckPending = false
    saving.value = false
  }

  return {
    collabStatus,
    onlineUsers,
    myUserId,
    connectCollab,
    disconnectCollab,
    isOnline,
    applyRemoteCells,
    notifyWorkbookSaved,
    sendChangedCellsViaCollab,
    finishSavingIfIdle,
    pendingCollabSaves,
    isSavePending: () => collabAckPending,
    isApplyingRemoteCells: () => applyingRemoteCells,
    send: (message) => (collabClient ? collabClient.send(message) : false)
  }
}
