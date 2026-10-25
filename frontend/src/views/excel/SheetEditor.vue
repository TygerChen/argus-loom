<template>
  <main class="editor-page">
    <header class="editor-toolbar">
      <div class="toolbar-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="doc-meta">
          <strong
            v-if="!editingName"
            class="doc-name"
            :class="{ 'is-readonly': !canEdit }"
            title="点击重命名"
            @click="canEdit && startRename()"
          >{{ documentName || '未命名文档' }}</strong>
          <div v-else class="doc-name-edit">
            <input
              ref="nameInput"
              v-model="renameValue"
              class="rename-input"
              maxlength="200"
              @keydown.enter="confirmRename"
              @keydown.escape="cancelRename"
              @blur="confirmRename"
            />
          </div>
          <span v-if="loadingSheet">加载 Sheet 数据：{{ loadedChunks }}/{{ totalChunks }} 块</span>
          <span v-else>{{ sheetCount }} 个 Sheet</span>
        </div>
      </div>

      <div class="toolbar-right">
        <div class="collab-bar" :class="`is-${collabStatus}`">
          <span class="collab-dot"></span>
          <span class="collab-label">{{ collabStatusLabel }}</span>
          <el-tooltip
            v-for="user in onlineUsers"
            :key="userKey(user)"
            :content="userTip(user)"
            placement="bottom"
          >
            <span class="collab-avatar" :style="{ backgroundColor: avatarColor(user) }">
              {{ avatarText(user) }}
            </span>
          </el-tooltip>
        </div>
        <el-tag v-if="hasUnsavedChanges" type="warning" effect="light">有未保存修改</el-tag>
        <el-tag v-if="!canEdit" type="info" effect="light">只读</el-tag>
        <el-dropdown v-if="canEdit" trigger="click" @command="handleAdvancedCommand">
          <el-button :icon="Tools">高级功能</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="filter" :icon="Filter">筛选 / 取消筛选</el-dropdown-item>
              <el-dropdown-item command="findReplace" :icon="Search">查找替换</el-dropdown-item>
              <el-dropdown-item command="clearFilters" :icon="Delete" divided>清除筛选条件</el-dropdown-item>
              <el-dropdown-item command="conditionalFormatting" :icon="MagicStick">条件格式</el-dropdown-item>
              <el-dropdown-item command="dataValidation" :icon="Grid">数据验证</el-dropdown-item>
              <el-dropdown-item command="table" :icon="Grid">表格</el-dropdown-item>
              <el-dropdown-item command="sort" :icon="Sort">排序</el-dropdown-item>
              <el-dropdown-item command="hyperlink" :icon="Link" divided>插入超链接</el-dropdown-item>
              <el-dropdown-item command="image" :icon="Picture">插入图片</el-dropdown-item>
              <el-dropdown-item command="comment" :icon="ChatDotRound">评论</el-dropdown-item>
              <el-dropdown-item command="note" :icon="Tickets">批注</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-if="canEdit" :icon="Filter" @click="toggleFilter">
          {{ filterActive ? '取消筛选' : '筛选' }}
        </el-button>
        <el-button v-if="canEdit && filterActive" :icon="Delete" @click="clearFilterCriteria">
          清除筛选
        </el-button>
        <el-button
          v-if="canEdit"
          :icon="RefreshLeft"
          :disabled="historyBusy"
          @click="undoHistoryChange"
        >
          撤销
        </el-button>
        <el-button
          v-if="canEdit"
          :icon="RefreshRight"
          :disabled="historyBusy"
          @click="redoHistoryChange"
        >
          重做
        </el-button>
        <el-button :icon="Clock" @click="openHistory">历史</el-button>
        <el-button :icon="Collection" @click="openVersions">版本</el-button>
        <el-button v-if="canEdit" type="primary" :icon="Upload" :loading="saving" @click="saveChanges">
          保存
        </el-button>
        <el-button type="success" :icon="Download" :loading="exporting" @click="exportCurrentWorkbook">
          导出 XLSX
        </el-button>
      </div>
    </header>

    <section class="sheet-stage">
      <div v-if="booting" class="boot-panel">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在打开表格...</span>
      </div>
      <div id="univer-container" class="univer-container"></div>
      <div class="remote-cursor-layer">
        <span
          v-for="badge in remoteBadges"
          :key="String(badge.userId)"
          class="remote-cursor-badge"
          :style="{ background: badge.color, transform: `translate(${badge.left}px, ${badge.top}px)` }"
        >{{ badge.text }}</span>
      </div>
    </section>

    <el-dialog
      v-model="historyDialogVisible"
      title="编辑历史 / 撤销重做"
      width="880px"
      top="6vh"
      append-to-body
    >
      <el-table v-loading="historyLoading" :data="historyRows" size="small" max-height="52vh">
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作人" min-width="120">
          <template #default="{ row }">{{ row.userDisplayName || '-' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="historyTypeTag(row.operationType)" effect="plain">
              {{ historyTypeLabel(row.operationType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="单元格" width="140">
          <template #default="{ row }">R{{ row.rowIndex ?? '-' }} C{{ row.colIndex ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="新值" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ readableValue(row.newValue) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="historyDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
/**
 * Excel 协同编辑器 —— 前端最复杂的页面，05 篇的全部舞台。
 *
 * <p><b>组件分工</b>：本组件负责"骨架与编排"——加载文档/分块数据（分段加载，
 * 工具栏能看到 loadedChunks/totalChunks）、挂载 Univer 表格引擎、
 * 建立协同连接（createCollabClient）、把用户的编辑批量提交（batchUpdateCells）
 * 并处理版本冲突弹窗。纯数据处理逻辑在 utils（univerAdapter 格式互转、
 * collabClient 网络层），视图层不做数据转换——Vue 组件可读性第一。</p>
 *
 * <p><b>协同状态机</b>：collabStatus（online/offline/connecting）驱动右上角
 * 的连接指示灯和在线头像列表；hasUnsavedChanges 提示未保存修改；
 * canEdit 为 false 时整个表格只读（权限在进入页面前已由后端校验，
 * 这里的只读是体验而非防线）。</p>
 *
 * <p><b>版本冲突的 UX</b>（05 篇 Q&A 的落地）：保存返回 REVISION_CONFLICT 时
 * 弹窗给用户两个选项——刷新看别人的（放弃我的）或覆盖服务器（我的优先），
 * 系统不替用户做决定。</p>
 */
/* eslint-disable @typescript-eslint/no-explicit-any -- Univer/JSON spreadsheet data and collab payloads are dynamic */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ChatDotRound,
  Collection,
  Delete,
  Download,
  Filter,
  Grid,
  Clock,
  Link,
  Loading,
  MagicStick,
  Picture,
  Search,
  Sort,
  Tickets,
  Tools,
  Upload,
  RefreshLeft,
  RefreshRight,
} from '@element-plus/icons-vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import {
  batchUpdateCells,
  getDocument,
  getDocumentHistory,
  loadAllCelldata,
  loadSheetChunks,
  redoDocumentEdit,
  renameDocument,
  saveWorkbook,
  undoDocumentEdit,
  uploadImageAsset,
} from '@/api/excel'
import { exportExcel } from '@/utils/export.js'
import { createCollabClient } from '@/utils/collabClient'
import { avatarColor, isEditCommand, remoteLuckyToUniver } from '@/utils/collabHelpers'
import { createUniverWorkbookAdapter } from '@/utils/univerAdapter'

// ── Univer 0.25 presets（与 DataLoom 保持同等能力集） ──
import { createUniver } from '@univerjs/presets'
import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core'
import zhCN from '@univerjs/preset-sheets-core/locales/zh-CN'
import { UniverSheetsConditionalFormattingPreset } from '@univerjs/preset-sheets-conditional-formatting'
import conditionalFormattingZhCN from '@univerjs/preset-sheets-conditional-formatting/locales/zh-CN'
import { UniverSheetsDataValidationPreset } from '@univerjs/preset-sheets-data-validation'
import dataValidationZhCN from '@univerjs/preset-sheets-data-validation/locales/zh-CN'
import { UniverSheetsDrawingPreset } from '@univerjs/preset-sheets-drawing'
import drawingZhCN from '@univerjs/preset-sheets-drawing/locales/zh-CN'
import { UniverSheetsFindReplacePreset } from '@univerjs/preset-sheets-find-replace'
import findReplaceZhCN from '@univerjs/preset-sheets-find-replace/locales/zh-CN'
import { UniverSheetsFilterPreset } from '@univerjs/preset-sheets-filter'
import filterZhCN from '@univerjs/preset-sheets-filter/locales/zh-CN'
import { UniverSheetsHyperLinkPreset } from '@univerjs/preset-sheets-hyper-link'
import hyperLinkZhCN from '@univerjs/preset-sheets-hyper-link/locales/zh-CN'
import { UniverSheetsNotePreset } from '@univerjs/preset-sheets-note'
import noteZhCN from '@univerjs/preset-sheets-note/locales/zh-CN'
import { UniverSheetsSortPreset } from '@univerjs/preset-sheets-sort'
import sortZhCN from '@univerjs/preset-sheets-sort/locales/zh-CN'
import { UniverSheetsTablePreset } from '@univerjs/preset-sheets-table'
import tableZhCN from '@univerjs/preset-sheets-table/locales/zh-CN'
import { UniverSheetsThreadCommentPreset } from '@univerjs/preset-sheets-thread-comment'
import threadCommentZhCN from '@univerjs/preset-sheets-thread-comment/locales/zh-CN'
import '@univerjs/sheets-conditional-formatting-ui/lib/index.css'
import '@univerjs/sheets-data-validation-ui/lib/index.css'
import '@univerjs/sheets-drawing-ui/lib/index.css'
import '@univerjs/sheets-filter-ui/lib/index.css'
import '@univerjs/preset-sheets-find-replace/lib/index.css'
import '@univerjs/sheets-hyper-link-ui/lib/index.css'
import '@univerjs/sheets-note-ui/lib/index.css'
import '@univerjs/sheets-sort-ui/lib/index.css'
import '@univerjs/sheets-table-ui/lib/index.css'
import '@univerjs/sheets-thread-comment-ui/lib/index.css'
import '@univerjs/preset-sheets-core/lib/index.css'

const route = useRoute()
const router = useRouter()

const documentId = ref(route.params.id as string)
const documentName = ref('')
const sheetCount = ref(0)
const booting = ref(true)
const loadingSheet = ref(false)
const loadedChunks = ref(0)
const totalChunks = ref(0)
const saving = ref(false)
const exporting = ref(false)
const workbookDirty = ref(false)
const canEdit = ref(false)
const filterActive = ref(false)
const permission = ref('viewer')

const historyBusy = ref(false)
const historyDialogVisible = ref(false)
const historyLoading = ref(false)
const historyRows = ref<any[]>([])

const editingName = ref(false)
const renameValue = ref('')
const nameInput = ref<HTMLInputElement | null>(null)

let univerApp: any = null
let univerAPI: any = null
let workbookAdapter: any = null
let activeWorkbookId: string | null = null
let collabClient: any = null
let commandDisposable: { dispose: () => void } | null = null
let applyingRemoteCells = false
let collabSeq = 0
let editFlushTimer: number | null = null
const ownRequestIds = new Set<string>()
const pendingSyncCells = new Map<string, { sheetId: number; r: number; c: number; v: any }>()
const dirtyCells = new Map<string, { sheetId: number; r: number; c: number; v: any }>()

const CHUNKS_PER_REQUEST = 5

const hasUnsavedChanges = computed(() => workbookDirty.value || dirtyCells.size > 0)

const collabStatus = ref('offline')
/** 协同乐观锁：客户端编辑所基于的服务器文档版本；每次广播/冲突回执都会刷新。 */
const baseRevision = ref<number | null>(null)
const onlineUsers = ref<any[]>([])
const collabStatusLabel = computed(() => {
  const labels: Record<string, string> = { connecting: '连接中', online: '在线', offline: '离线' }
  return labels[collabStatus.value] || '离线'
})

const remoteBadges = ref<any[]>([])

function unwrapApi(response: any): any {
  const body = response?.data
  if (body && typeof body === 'object' && 'success' in body) {
    if (!body.success) throw new Error(body.message || '请求失败')
    return body.data
  }
  return body
}

// ── 文档重命名 ──
function startRename() {
  if (!canEdit.value) return
  renameValue.value = documentName.value
  editingName.value = true
  setTimeout(() => nameInput.value?.focus(), 50)
}

async function confirmRename() {
  if (!editingName.value) return
  const newName = renameValue.value.trim()
  editingName.value = false
  if (!newName || newName === documentName.value) return
  try {
    await renameDocument(Number(documentId.value), newName)
    documentName.value = newName
    ElMessage.success('重命名成功')
  } catch (error: any) {
    ElMessage.error(`重命名失败：${error.message}`)
  }
}

function cancelRename() {
  editingName.value = false
}

// ── 初始化 ──
onMounted(async () => {
  await initDocument()
})

onBeforeUnmount(() => {
  disposeWorkbook()
})

async function initDocument() {
  const loading = ElLoading.service({ lock: true, text: '正在加载文档结构...', background: 'rgba(255,255,255,0.78)' })
  try {
    const doc = unwrapApi(await getDocument(Number(documentId.value)))
    if (!doc) throw new Error('文档不存在')
    documentId.value = doc.id
    documentName.value = doc.name
    baseRevision.value = typeof doc.version === 'number' ? doc.version : null
    canEdit.value = doc.canEdit !== false
    permission.value = doc.permission || 'viewer'

    const metas = doc.sheets || []
    sheetCount.value = metas.length
    const celldataList: any[] = []

    for (let index = 0; index < metas.length; index += 1) {
      const meta = metas[index]
      loading.setText(`正在加载 ${meta.sheetName || `Sheet${index + 1}`}...`)
      // eslint-disable-next-line no-await-in-loop
      celldataList.push(await fetchSheetCelldata(meta))
    }

    loading.close()
    await nextTick()
    bootWorkbook(doc, metas, celldataList)
  } catch (error: any) {
    loading.close()
    console.error('打开文档失败:', error)
    ElMessage.error(`打开文档失败：${error.message}`)
    router.push({ name: 'excel' })
  } finally {
    booting.value = false
  }
}

async function fetchSheetCelldata(meta: any) {
  loadingSheet.value = true
  loadedChunks.value = 0
  totalChunks.value = meta.chunkCount || 1

  const batches: Array<{ start: number; end: number }> = []
  for (let start = 0; start < totalChunks.value; start += CHUNKS_PER_REQUEST) {
    batches.push({ start, end: Math.min(start + CHUNKS_PER_REQUEST - 1, totalChunks.value - 1) })
  }

  try {
    const MAX_INFLIGHT = 3
    const results = new Array<{ celldata: any[] } | null>(batches.length)
    let nextBatch = 0

    async function worker() {
      while (nextBatch < batches.length) {
        const index = nextBatch
        nextBatch += 1
        const range = batches[index]!
        const payload = unwrapApi(await loadSheetChunks(
          Number(documentId.value),
          meta.sheetId,
          range.start,
          range.end,
        ))
        results[index] = { celldata: payload?.chunks?.flatMap((chunk: any) => chunk.celldata || []) || [] }
        loadedChunks.value = Math.max(loadedChunks.value, range.end + 1)
      }
    }

    await Promise.all(Array.from({ length: Math.min(MAX_INFLIGHT, batches.length) }, () => worker()))
    const cells: any[] = []
    for (const result of results) {
      if (result) cells.push(...result.celldata)
    }
    return cells
  } catch (error: any) {
    ElMessage.warning(`${meta.sheetName || 'Sheet'} 分块加载失败，降级为全量加载：${error.message}`)
    const payload = unwrapApi(await loadAllCelldata(Number(documentId.value), meta.sheetId))
    return payload?.celldata || []
  } finally {
    loadedChunks.value = totalChunks.value
    loadingSheet.value = false
  }
}

function bootWorkbook(doc: any, metas: any[], celldataList: any[]) {
  disposeWorkbook()

  const adapter = createUniverWorkbookAdapter({
    documentId: documentId.value,
    documentName: doc.name,
    metas,
    celldataList,
  })

  const created = createUniver({
    locale: 'zhCN' as any,
    locales: {
      zhCN: {
        ...zhCN,
        ...conditionalFormattingZhCN,
        ...dataValidationZhCN,
        ...drawingZhCN,
        ...findReplaceZhCN,
        ...filterZhCN,
        ...hyperLinkZhCN,
        ...noteZhCN,
        ...sortZhCN,
        ...tableZhCN,
        ...threadCommentZhCN,
      },
    },
    presets: [
      UniverSheetsCorePreset({
        container: 'univer-container',
        toolbar: true,
        footer: true,
        formulaBar: true,
        statusBarStatistic: true,
      } as any),
      UniverSheetsConditionalFormattingPreset(),
      UniverSheetsDataValidationPreset(),
      UniverSheetsDrawingPreset(),
      UniverSheetsFindReplacePreset(),
      UniverSheetsFilterPreset(),
      UniverSheetsHyperLinkPreset(),
      UniverSheetsNotePreset(),
      UniverSheetsSortPreset(),
      UniverSheetsTablePreset(),
      UniverSheetsThreadCommentPreset(),
    ],
  })

  univerApp = created.univer
  univerAPI = created.univerAPI
  workbookAdapter = adapter
  if (import.meta.env.DEV) {
    ;(window as any).__argusUniverAPI = univerAPI
  }

  const workbook = univerAPI.createWorkbook(adapter.workbookData)
  activeWorkbookId = workbook.getId()
  sheetCount.value = workbook.getNumSheets()
  workbook.setEditable(canEdit.value)
  workbookAdapter.captureBaseline(workbook.save(), getCurrentSheetId())
  attachEditListener()
  connectCollab()
  refreshFilterState()
  workbookDirty.value = false
}

function disposeWorkbook() {
  collabClient?.disconnect?.()
  collabClient = null
  ownRequestIds.clear()
  pendingSyncCells.clear()

  commandDisposable?.dispose?.()
  commandDisposable = null
  if (editFlushTimer !== null) {
    window.clearTimeout(editFlushTimer)
    editFlushTimer = null
  }

  if (univerAPI && activeWorkbookId) {
    try {
      univerAPI.disposeUnit(activeWorkbookId)
    } catch {
      // ignore: instance is disposed below anyway
    }
  }
  univerApp?.dispose?.()
  univerApp = null
  univerAPI = null
  workbookAdapter = null
  activeWorkbookId = null
  if (import.meta.env.DEV) {
    delete (window as any).__argusUniverAPI
  }
  collabStatus.value = 'offline'
  onlineUsers.value = []
  remoteBadges.value = []
}

function getActiveSheetFacade() {
  const workbook = univerAPI?.getActiveWorkbook?.()
  return workbook?.getActiveSheet?.() || null
}

function getCurrentSheetId() {
  return getActiveSheetFacade()?.getSheetId?.() || null
}

// ── 协同 ──
function connectCollab() {
  if (!documentId.value) return
  collabClient?.disconnect?.()
  collabClient = createCollabClient({
    documentId: Number(documentId.value),
    onStatus: (status: string) => {
      collabStatus.value = status
      if (status !== 'online') {
        onlineUsers.value = []
        remoteBadges.value = []
      }
      if (status === 'online') window.setTimeout(() => flushPendingSyncCells(), 200)
    },
    onMessage: handleCollabMessage,
  })
  collabClient.connect()
}

function handleCollabMessage(message: any) {
  if (!message || typeof message !== 'object') return

  if (message.type === 'cells-updated') {
    const requestId = message.requestId
    if (requestId && ownRequestIds.has(requestId)) {
      ownRequestIds.delete(requestId)
      // 自己的编辑被服务器接受：广播带回了写入后的最新 revision
      if (typeof message.revision === 'number') {
        baseRevision.value = message.revision
      }
      return
    }
    if (typeof message.revision === 'number') {
      baseRevision.value = message.revision
    }
    applyRemoteCells(Array.isArray(message.cells) ? message.cells : [])
    return
  }

  if (message.type === 'presence') {
    onlineUsers.value = Array.isArray(message.users) ? message.users : []
    return
  }

  if (message.type === 'error') {
    if (message.type === 'REVISION_CONFLICT' || message.code === 'REVISION_CONFLICT') {
      // 任务 8：旧 baseRevision 冲突——让用户选择覆盖或放弃；用服务器最新版本重试
      ElMessageBox.confirm(
        `${message.message || '该单元格已被他人更新'}。是否用你的内容覆盖服务器上的最新值？`,
        '编辑冲突',
        { confirmButtonText: '覆盖服务器值', cancelButtonText: '放弃我的修改', type: 'warning' }
      ).then(() => {
        ElMessage.info('已选择覆盖，请重新修改该单元格后保存（已刷新到最新版本）')
        if (typeof message.serverRevision === 'number') {
          baseRevision.value = message.serverRevision
        }
      }).catch(() => {
        if (typeof message.serverRevision === 'number') {
          baseRevision.value = message.serverRevision
        }
      })
      return
    }
    ElMessage.error(message.message || '协同通道异常')
  }
}

function applyRemoteCells(cells: any[]) {
  const workbook = univerAPI?.getActiveWorkbook?.()
  if (!workbook || !workbookAdapter) return

  applyingRemoteCells = true
  try {
    for (const cell of cells) {
      if (!cell || !Number.isFinite(Number(cell.r)) || !Number.isFinite(Number(cell.c))) continue
      const localSheetId = workbookAdapter.getLocalSheetId(cell.sheetId)
      if (!localSheetId) continue
      const sheet = workbook.getSheetBySheetId(localSheetId)
      if (!sheet) continue
      sheet.getRange(Number(cell.r), Number(cell.c)).setValue(remoteLuckyToUniver(cell.v))
    }
  } finally {
    applyingRemoteCells = false
  }
}

function attachEditListener() {
  commandDisposable?.dispose?.()
  if (!univerAPI) return
  commandDisposable = univerAPI.addEvent(univerAPI.Event.CommandExecuted, (event: any) => {
    if (applyingRemoteCells) return
    const commandId = String(event?.id || '')
    if (commandId && isEditCommand(commandId)) {
      workbookDirty.value = true
    }
    if (commandId.includes('filter') || commandId.includes('activate')) {
      refreshFilterState()
    }
    if (commandId === 'sheet.command.set-range-values') {
      handleLocalRangeCommand(event?.params || event?.command?.params)
    }
  })
}

function handleLocalRangeCommand(params: any) {
  if (!canEdit.value || !univerAPI || !workbookAdapter) return
  const workbook = univerAPI.getActiveWorkbook()
  const range = params?.range
  if (!workbook || !range) return

  const localSheetId = params?.subUnitId
  const serverSheetId = workbookAdapter.getServerSheetId(localSheetId)
  if (serverSheetId == null) return
  const sheet = workbook.getSheetBySheetId(localSheetId)
  if (!sheet) return

  const startRow = Math.max(0, Number(range.startRow ?? 0))
  const startColumn = Math.max(0, Number(range.startColumn ?? 0))
  const endRow = Math.min(Math.max(startRow, Number(range.endRow ?? startRow)), (sheet.getMaxRows?.() ?? startRow), 200)
  const endColumn = Math.min(Math.max(startColumn, Number(range.endColumn ?? startColumn)), (sheet.getMaxColumns?.() ?? startColumn), 200)

  for (let r = startRow; r <= endRow; r++) {
    for (let c = startColumn; c <= endColumn; c++) {
      const value = sheet.getRange(r, c).getValue()
      const wrapped = value === null || value === undefined ? { v: null } : { v: value, m: String(value ?? '') }
      const key = `${serverSheetId}:${r}:${c}`
      pendingSyncCells.set(key, { sheetId: serverSheetId, r, c, v: wrapped })
      dirtyCells.set(key, { sheetId: serverSheetId, r, c, v: wrapped })
    }
  }
  workbookDirty.value = true
  scheduleEditFlush()
}

function scheduleEditFlush() {
  if (editFlushTimer !== null) return
  editFlushTimer = window.setTimeout(() => {
    editFlushTimer = null
    flushPendingSyncCells()
  }, 180)
}

function flushPendingSyncCells() {
  if (pendingSyncCells.size === 0) return
  if (!canEdit.value || !collabClient || collabStatus.value !== 'online') return

  const cells = [...pendingSyncCells.values()]
  pendingSyncCells.clear()
  for (const cell of cells) {
    const requestId = `edit-${Date.now()}-${++collabSeq}`
    ownRequestIds.add(requestId)
    const sent = collabClient.send({
      type: 'CELL_EDIT',
      documentId: Number(documentId.value),
      sheetId: cell.sheetId,
      r: cell.r,
      c: cell.c,
      v: cell.v,
      timestamp: Date.now(),
      requestId,
      baseRevision: baseRevision.value,
    })
    if (!sent) ownRequestIds.delete(requestId)
  }
}

// ── 筛选 / 高级功能 ──
function refreshFilterState() {
  filterActive.value = !!getActiveSheetFacade()?.getFilter?.()
}

function toggleFilter() {
  if (!univerAPI) {
    ElMessage.warning('表格尚未初始化')
    return
  }
  const sheet = getActiveSheetFacade()
  if (!sheet) {
    ElMessage.warning('请先选择工作表')
    return
  }

  const existingFilter = sheet.getFilter?.()
  if (existingFilter) {
    existingFilter.remove()
    workbookDirty.value = true
    refreshFilterState()
    ElMessage.success('已取消筛选')
    return
  }

  const dataRange = sheet.getDataRange()
  dataRange.createFilter()
  workbookDirty.value = true
  refreshFilterState()
  ElMessage.success('已开启筛选，点击列头漏斗图标可筛选和排序')
}

function clearFilterCriteria() {
  const sheet = getActiveSheetFacade()
  const activeFilter = sheet?.getFilter?.()
  if (!activeFilter) return
  activeFilter.removeFilterCriteria()
  workbookDirty.value = true
  ElMessage.success('已清除筛选条件')
}

async function handleAdvancedCommand(command: string) {
  if (!univerAPI) {
    ElMessage.warning('表格尚未初始化')
    return
  }

  switch (command) {
    case 'filter':
      toggleFilter()
      break
    case 'clearFilters':
      clearFilterCriteria()
      break
    case 'findReplace':
      runAdvancedCommand('ui.operation.open-find-dialog', '查找替换')
      break
    case 'conditionalFormatting':
      runAdvancedCommand('sheet.operation.open.conditional.formatting.panel', '条件格式')
      break
    case 'dataValidation':
      runAdvancedCommand('data-validation.command.addRuleAndOpen', '数据验证')
      break
    case 'table':
      runAdvancedCommand('sheet.operation.open-table-selector', '表格')
      break
    case 'sort':
      runAdvancedCommand('sheet.command.sort-range-custom', '排序')
      break
    case 'hyperlink':
      runAdvancedCommand('sheet.operation.insert-hyper-link-toolbar', '超链接')
      break
    case 'image':
      await insertImageFromDisk()
      break
    case 'comment':
      runAdvancedCommand('sheet.operation.toggle-comment-panel', '评论')
      break
    case 'note':
      runAdvancedCommand('sheet.operation.add-note-popup', '批注')
      break
    default:
      break
  }
}

async function runAdvancedCommand(commandId: string, label: string) {
  try {
    const executed = await univerAPI.executeCommand(commandId)
    if (!executed) {
      ElMessage.warning(`「${label}」未执行，请先选中单元格或工作表后再试`)
    }
  } catch (error: any) {
    console.warn(`执行 ${label} 失败:`, error)
    ElMessage.warning(`「${label}」暂时不可用：${error.message}`)
  }
}

async function insertImageFromDisk() {
  if (!univerAPI) {
    ElMessage.warning('表格尚未初始化')
    return
  }
  const sheet = univerAPI.getActiveWorkbook()?.getActiveSheet?.()
  if (!sheet) {
    ElMessage.warning('请先选择工作表后再插入图片')
    return
  }
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/png,image/jpeg,image/gif,image/webp,image/bmp'
  input.onchange = async () => {
    const file = input.files && input.files[0]
    if (!file) return
    const loading = ElLoading.service({ fullscreen: true, text: '图片上传中...' })
    try {
      const data = unwrapApi(await uploadImageAsset(file, Number(documentId.value)))
      const url = data?.url
      if (!url) throw new Error('图片上传成功但未返回访问地址')
      await sheet.insertImage(url, 0, 0, 0, 0)
      workbookDirty.value = true
      ElMessage.success('图片已插入')
    } catch (error: any) {
      ElMessage.error(`图片插入失败：${error.message}`)
    } finally {
      loading.close()
    }
  }
  input.click()
}

// ── 历史与撤销 ──
async function openHistory() {
  historyDialogVisible.value = true
  await loadHistoryData()
}

async function loadHistoryData() {
  historyLoading.value = true
  try {
    const history = await getDocumentHistory(Number(documentId.value))
    historyRows.value = history?.records || []
  } catch (error: any) {
    ElMessage.error(`历史加载失败：${error.message}`)
  } finally {
    historyLoading.value = false
  }
}

function historyTypeLabel(type: string) {
  const map: Record<string, string> = {
    CELL_EDIT: '编辑',
    UNDO: '撤销',
    REDO: '重做',
    RENAME: '重命名',
  }
  return map[type] || type || '-'
}

function historyTypeTag(type: string) {
  if (type === 'UNDO') return 'warning'
  if (type === 'REDO') return 'success'
  return 'primary'
}

function readableValue(value: any) {
  if (value == null || value === '') return '（空）'
  if (typeof value === 'object') {
    if (value.v != null) return String(value.v)
    return JSON.stringify(value)
  }
  return String(value)
}

async function undoHistoryChange() {
  await performHistoryChange(undoDocumentEdit, '撤销成功')
}

async function redoHistoryChange() {
  await performHistoryChange(redoDocumentEdit, '重做成功')
}

async function performHistoryChange(apiCall: (docId: number) => Promise<any>, successText: string) {
  if (!canEdit.value) {
    ElMessage.warning('当前文档为只读')
    return
  }
  if (historyBusy.value) return

  historyBusy.value = true
  try {
    const result = unwrapApi(await apiCall(Number(documentId.value)))
    if (result?.rowIndex != null && result?.colIndex != null) {
      const localSheetId = workbookAdapter?.getLocalSheetId(Number(result.sheetId ?? firstServerSheetId()))
      const sheet = localSheetId && univerAPI?.getActiveWorkbook?.()?.getSheetBySheetId(localSheetId)
      if (sheet) {
        const restored = parseRestoredValue(result.restoredValue)
        applyingRemoteCells = true
        try {
          sheet.getRange(Number(result.rowIndex), Number(result.colIndex)).setValue(restored)
        } finally {
          applyingRemoteCells = false
        }
        broadcastHistoryReversal(result, restored)
      }
    }
    ElMessage.success(successText)
  } catch (error: any) {
    ElMessage.error(error.message || '操作失败')
  } finally {
    historyBusy.value = false
    if (historyDialogVisible.value) loadHistoryData()
  }
}

function parseRestoredValue(value: any) {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string') return value
  try {
    const parsed = JSON.parse(value)
    if (parsed && typeof parsed === 'object') return parsed.v ?? parsed
    return parsed
  } catch {
    return value
  }
}

function broadcastHistoryReversal(result: any, restored: any) {
  if (!collabClient || collabStatus.value !== 'online') return
  const requestId = `history-${Date.now()}-${++collabSeq}`
  ownRequestIds.add(requestId)
  collabClient.send({
    type: 'CELL_EDIT',
    documentId: Number(documentId.value),
    sheetId: Number(result.sheetId ?? firstServerSheetId()),
    r: Number(result.rowIndex),
    c: Number(result.colIndex),
    v: { v: restored, m: String(restored ?? '') },
    timestamp: Date.now(),
    requestId,
    baseRevision: baseRevision.value,
  })
}

function firstServerSheetId() {
  if (!univerAPI || !workbookAdapter) return null
  const workbook = univerAPI.getActiveWorkbook()
  const firstSheet = workbook?.getSheets?.()?.[0]
  return firstSheet ? workbookAdapter.getServerSheetId(firstSheet.getSheetId()) : null
}

function formatTime(value: string) {
  if (!value) return '-'
  return String(value).replace('T', ' ').substring(0, 19)
}

// ── 保存 ──
function serializeCurrentWorkbook(options: { withData?: boolean } = {}) {
  if (!workbookAdapter || !univerAPI) return []
  const workbook = univerAPI.getActiveWorkbook()
  if (!workbook) return []
  return workbookAdapter.serialize(workbook.save(), getCurrentSheetId(), options)
}

async function saveChanges() {
  if (!workbookAdapter || !univerAPI) {
    ElMessage.error('表格尚未初始化')
    return
  }
  if (!canEdit.value) {
    ElMessage.warning('当前文档为只读')
    return
  }

  saving.value = true
  try {
    const workbook = univerAPI.getActiveWorkbook()
    const snapshot = workbook.save()
    const activeSheetId = getCurrentSheetId()
    const structuralDirty = workbookAdapter.hasStructuralChange(snapshot, activeSheetId)
    const changedCells = workbookAdapter.collectChangedCells(snapshot)

    if (!structuralDirty && changedCells.length === 0 && dirtyCells.size === 0) {
      workbookDirty.value = false
      ElMessage.info('没有需要保存的修改')
      return
    }

    if (structuralDirty) {
      const sheets = serializeCurrentWorkbook()
      if (!sheets || sheets.length === 0) {
        throw new Error('表格数据为空')
      }
      const payload = unwrapApi(await saveWorkbook(Number(documentId.value), sheets))
      workbookAdapter.updateServerSheetIds(snapshot, payload?.sheetIdMap)
      sheetCount.value = sheets.length
      workbookAdapter.acceptBaseline(univerAPI.getActiveWorkbook().save(), activeSheetId)
      workbookDirty.value = false
      dirtyCells.clear()
      ElMessage.success(`保存成功（结构变化，全量重建），共 ${sheets.length} 个 Sheet`)
      return
    }

    if (changedCells.length === 0) {
      workbookDirty.value = false
      dirtyCells.clear()
      ElMessage.info('没有需要保存的修改')
      return
    }
    unwrapApi(await batchUpdateCells(Number(documentId.value), changedCells))
    workbookAdapter.acceptBaseline(univerAPI.getActiveWorkbook().save(), activeSheetId)
    workbookDirty.value = false
    dirtyCells.clear()
    ElMessage.success(`已保存 ${changedCells.length} 个单元格`)
  } catch (error: any) {
    ElMessage.error(`保存失败：${error.message}`)
  } finally {
    saving.value = false
  }
}

// ── 导出 ──
async function exportCurrentWorkbook() {
  if (hasUnsavedChanges.value) {
    ElMessage.warning('请先保存当前修改后再导出')
    return
  }
  exporting.value = true
  try {
    const sheets = serializeCurrentWorkbook({ withData: true })
    if (!sheets.length) throw new Error('表格尚未初始化')
    await exportExcel(sheets, documentName.value || '未命名文档')
    ElMessage.success('导出完成')
  } catch (error: any) {
    ElMessage.error(`导出失败：${error.message}`)
  } finally {
    exporting.value = false
  }
}

function openVersions() {
  router.push({ name: 'excel-versions', params: { id: documentId.value } })
}

function goBack() {
  router.push({ name: 'excel' })
}

// ── 协同用户展示 ──
function displayNameOf(user: any) {
  return user?.nickname || user?.username || user?.displayName || '匿名用户'
}

function userKey(user: any) {
  return `${user?.userId ?? 'guest'}-${displayNameOf(user)}`
}

function userTip(user: any) {
  return `${displayNameOf(user)}（${user?.canEdit === false ? '只读' : '编辑'}）`
}

function avatarText(user: any) {
  const name = displayNameOf(user)
  return Array.from(name).slice(0, 2).join('')
}
</script>

<style scoped>
.editor-page {
  display: flex;
  flex-direction: column;
  /* 100vh - 顶栏 64px - 主区域上下内边距 28px * 2，保证编辑器完整落在可视区内 */
  height: calc(100vh - 120px);
  overflow: hidden;
  background: #eef2eb;
}

.editor-toolbar {
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--dl-line);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 8px 24px rgba(20, 32, 24, 0.08);
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.doc-meta {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.doc-meta strong,
.doc-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta strong {
  max-width: 360px;
  font-size: 14px;
}

.doc-meta .doc-name {
  cursor: pointer;
  transition: color 0.15s;
}

.doc-meta .doc-name:hover {
  color: var(--dl-accent-strong);
}

.doc-meta .doc-name.is-readonly {
  cursor: default;
}

.doc-meta .doc-name.is-readonly:hover {
  color: var(--dl-ink);
}

.doc-name-edit {
  display: flex;
  align-items: center;
}

.rename-input {
  max-width: 280px;
  padding: 4px 8px;
  border: 1px solid var(--dl-accent-strong);
  border-radius: 4px;
  font-size: 14px;
  font-weight: 600;
  outline: none;
  background: #fff;
  box-shadow: 0 0 0 2px rgba(47, 125, 87, 0.12);
}

.doc-meta span {
  color: var(--dl-muted);
  font-size: 12px;
}

.collab-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 3px 8px;
  border: 1px solid var(--dl-line);
  border-radius: 6px;
  background: #fff;
}

.collab-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #c2c3c4;
}

.collab-bar.is-online .collab-dot {
  background: #2f7d57;
  box-shadow: 0 0 0 3px rgba(47, 125, 87, 0.12);
}

.collab-bar.is-connecting .collab-dot {
  background: #d08a2e;
}

.collab-bar.is-offline .collab-dot {
  background: #c24b4b;
}

.collab-label {
  color: var(--dl-muted);
  font-size: 12px;
  white-space: nowrap;
}

.collab-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #6b7280;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(22, 32, 24, 0.18);
}

.sheet-stage {
  position: relative;
  flex: 1;
  min-height: 0;
}

.univer-container {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.remote-cursor-layer {
  position: absolute;
  inset: 0;
  z-index: 30;
  overflow: hidden;
  pointer-events: none;
}

.remote-cursor-badge {
  position: absolute;
  left: 0;
  top: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 20px;
  min-width: 20px;
  padding: 0 6px;
  border-radius: 4px;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.18);
}

.boot-panel {
  position: absolute;
  inset: 18px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--dl-muted);
}

@media (max-width: 760px) {
  .editor-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar-left,
  .toolbar-right {
    justify-content: space-between;
    width: 100%;
  }

  .doc-meta strong {
    max-width: 190px;
  }
}
</style>
