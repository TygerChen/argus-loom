/**
 * Univer 表格引擎 ↔ 后端存储格式（LuckySheet 风格 celldata JSON）的双向转换层。
 *
 * <p><b>为什么需要这个文件</b>：后端存的是紧凑的 {r, c, v} 稀疏单元格列表
 * （ExcelSheetService 的 chunk/delta 模型），前端渲染用的是 Univer 引擎的
 * 快照结构（行列数、样式表、单元格对象）。两者字段名、类型编号、对齐值
 * 枚举都不一样（见下面三组映射表），必须有纯函数层做翻译。</p>
 *
 * <p><b>设计要点</b>：无副作用（输入转输出，不改入参）——转换出错不会污染
 * 原数据；空表格兜底 DEFAULT_ROW/COLUMN_COUNT；行列上限贴 Excel 规范
 * （1048576 行 × 16384 列）。改这里之前先跑前端测试，格式兼容靠它们锁住。</p>
 */
const DEFAULT_ROW_COUNT = 100
const DEFAULT_COLUMN_COUNT = 20
const MAX_EXCEL_ROWS = 1048576
const MAX_EXCEL_COLUMNS = 16384

const CELL_TYPE_TO_UNIVER = {
  s: 1,
  n: 2,
  b: 3
}

const UNIVER_TO_CELL_TYPE = {
  1: 's',
  2: 'n',
  3: 'b'
}

// Luckysheet stores these alignment values with a different numbering than Univer.
const LUCKY_HT_TO_UNIVER = { 0: 2, 1: 1, 2: 3 }
const UNIVER_HT_TO_LUCKY = { 1: 1, 2: 0, 3: 2 }
const LUCKY_VT_TO_UNIVER = { 0: 2, 1: 1, 2: 3 }
const UNIVER_VT_TO_LUCKY = { 1: 1, 2: 0, 3: 2 }
const LUCKY_TB_TO_UNIVER = { 1: 3, 2: 1 }
const UNIVER_TB_TO_LUCKY = { 1: 2, 3: 1 }

const UNIVER_WORKBOOK_RESOURCE_KEY = 'univerWorkbookResources'
const UNIVER_SHEET_CONFIG_KEY = 'univerSheetConfig'
const UNIVER_SHEET_ID_KEY = 'univerSheetId'
const DEFAULT_FREEZE = {
  xSplit: 0,
  ySplit: 0,
  startRow: -1,
  startColumn: -1
}
const BORDER_SIDE_KEYS = ['t', 'r', 'b', 'l']
const LUCKY_TR_TO_UNIVER = {
  1: { a: 45 },
  2: { a: -45 },
  3: { a: 0, v: 1 },
  4: { a: 90 },
  5: { a: -90 }
}
const SHEET_DEFAULT_VALUE = {
  hidden: 0,
  tabColor: '',
  showGridlines: 1,
  defaultColumnWidth: 73,
  defaultRowHeight: 21,
  rightToLeft: 0
}
const INTERNAL_REV_FIELD = '_rev'

export function createUniverWorkbookAdapter({ documentId, documentName, metas, celldataList }) {
  const styleStore = createStyleStore()
  const originalMetas = new Map()
  const originalCells = new Map()
  const baselineCells = new Map()
  const serverRevisions = new Map()
  const serverDbIds = new Map()
  let baselineStructure = null
  const workbookId = `dl-${documentId}-${Date.now()}`
  const sheetList = normalizeSheetList(metas, celldataList)
  const storedWorkbookResources = findStoredWorkbookResources(sheetList)

  const workbookData = {
    id: workbookId,
    appVersion: '0.25.1',
    name: documentName || '未命名文档',
    locale: 'zhCN',
    styles: styleStore.styles,
    sheetOrder: [],
    sheets: {}
  }
  if (storedWorkbookResources) {
    workbookData.resources = storedWorkbookResources
  }

  sheetList.forEach((item, index) => {
    const meta = item.meta || {}
    const celldata = item.celldata || []
    const storedSheetId = meta.config?.[UNIVER_SHEET_ID_KEY]
    const sheetId = String(storedSheetId || meta.sheetId || `sheet-${index}`)
    if (meta.sheetId != null) {
      serverDbIds.set(sheetId, Number(meta.sheetId))
    }
    const { cellData, rowData, columnData, mergeData, maxRow, maxCol } = buildSheetData(
      sheetId,
      celldata,
      meta.config,
      styleStore,
      originalCells,
      baselineCells,
      serverRevisions
    )

    const rowCount = clampNumber(meta.totalRows, maxRow + 1, DEFAULT_ROW_COUNT, MAX_EXCEL_ROWS)
    const columnCount = clampNumber(meta.totalCols, maxCol + 1, DEFAULT_COLUMN_COUNT, MAX_EXCEL_COLUMNS)

    workbookData.sheetOrder.push(sheetId)
    const sheetSnapshot = {
      id: sheetId,
      name: meta.sheetName || `Sheet${index + 1}`,
      rowCount,
      columnCount,
      cellData,
      rowData,
      columnData,
      mergeData,
      freeze: { ...DEFAULT_FREEZE }
    }
    applySheetAdvancedConfig(sheetSnapshot, meta.config?.[UNIVER_SHEET_CONFIG_KEY])
    workbookData.sheets[sheetId] = sheetSnapshot

    originalMetas.set(sheetId, {
      index,
      config: meta.config || {},
      hyperlink: meta.hyperlink || {},
      images: meta.images || {},
      conditionFormats: meta.luckysheet_conditionformat_save || [],
      chart: meta.chart || []
    })
  })

  if (workbookData.sheetOrder.length === 0) {
    const sheetId = 'sheet-0'
    workbookData.sheetOrder.push(sheetId)
    const sheetSnapshot = {
      id: sheetId,
      name: 'Sheet1',
      rowCount: DEFAULT_ROW_COUNT,
      columnCount: DEFAULT_COLUMN_COUNT,
      cellData: {},
      rowData: {},
      columnData: {},
      mergeData: [],
      freeze: { ...DEFAULT_FREEZE }
    }
    applySheetAdvancedConfig(sheetSnapshot, undefined)
    workbookData.sheets[sheetId] = sheetSnapshot
    originalMetas.set(sheetId, {
      index: 0,
      config: {},
      hyperlink: {},
      images: {},
      conditionFormats: [],
      chart: []
    })
  }

  function structureSignature(snapshot, activeSheetId) {
    const sheets = serializeWorkbook({
      snapshot,
      activeSheetId,
      originalMetas,
      originalCells,
      baselineCells
    })
    // 注意：签名刻意不含 row/column（网格维度）。在空白行/列输入内容会让
    // 维度增长，但这不是"结构变更"——后端增量路径会自动扩块，启动时维度
    // 也会由 celldata clamp 回来；若把它算进签名，普通打字就会触发
    // 整册重建 + STRUCTURE 快照 + 广播全员重载（实测行为）。
    return stableJson(sheets.map((item) => ({
      name: item.name,
      config: item.config
    })))
  }

  function currentCellContents(cell) {
    return {
      v: cell?.v ?? null,
      f: cell?.f ?? null,
      s: cell?.s ?? null,
      p: cell?.p ?? null
    }
  }

  function sameCellContents(left, right) {
    return stableJson(left) === stableJson(right)
  }

  function storeBaselineCells(snapshot) {
    baselineCells.clear()
    for (const sheetId of snapshot.sheetOrder || Object.keys(snapshot.sheets || {})) {
      const sheet = snapshot.sheets?.[sheetId] || {}
      for (const [rowKey, rowCells] of Object.entries(sheet.cellData || {})) {
        for (const [columnKey, cell] of Object.entries(rowCells || {})) {
          baselineCells.set(
            `${sheetId}:${Number(rowKey)}:${Number(columnKey)}`,
            currentCellContents(cell)
          )
        }
      }
    }
  }

  function collectChangedCells(snapshot) {
    const updates = []
    const styles = snapshot.styles || {}
    const sheetIds = Array.isArray(snapshot.sheetOrder)
      ? snapshot.sheetOrder
      : Object.keys(snapshot.sheets || {})

    for (const sheetId of sheetIds) {
      const dbSheetId = serverDbIds.get(sheetId)
      if (dbSheetId === undefined || dbSheetId === null) continue

      const sheet = snapshot.sheets?.[sheetId] || {}
      const prefix = `${sheetId}:`
      const presentKeys = new Set()

      for (const [rowKey, rowCells] of Object.entries(sheet.cellData || {})) {
        for (const [columnKey, cell] of Object.entries(rowCells || {})) {
          const r = Number(rowKey)
          const c = Number(columnKey)
          if (!Number.isFinite(r) || !Number.isFinite(c)) continue

          const key = `${r}:${c}`
          presentKeys.add(key)
          const current = currentCellContents(cell)
          const baseline = baselineCells.get(prefix + key)
          if (!baseline || !sameCellContents(current, baseline)) {
            const legacy = originalCells.get(prefix + key)
            const luckyCell = serializeLuckyCell(cell, styles, legacy, baseline)
            const baseState = serverRevisions.get(prefix + key)
            updates.push({
              sheetId: dbSheetId,
              r,
              c,
              v: luckyCell || null,
              baseRev: baseState?.rev ?? null,
              ...(baseState?.valueText != null ? { baseValue: baseState.valueText } : {})
            })
          }
        }
      }

      for (const [key, baseline] of baselineCells) {
        if (!key.startsWith(prefix)) continue
        const relativeKey = key.slice(prefix.length)
        if (presentKeys.has(relativeKey)) continue

        const parts = relativeKey.split(':')
        const r = Number(parts[0])
        const c = Number(parts[1])
        if (Number.isFinite(r) && Number.isFinite(c)) {
          // key 本身已是 `${sheetId}:${r}:${c}` 完整形式，不能再拼 prefix（否则双前缀查不到 rev）
          const baseState = serverRevisions.get(key)
          updates.push({
            sheetId: dbSheetId,
            r,
            c,
            v: null,
            baseRev: baseState?.rev ?? null,
            ...(baseState?.valueText != null ? { baseValue: baseState.valueText } : {})
          })
        }
      }
    }

    return updates
  }

  return {
    workbookData,
    serialize(snapshot, activeSheetId, { withData = false } = {}) {
      return serializeWorkbook({
        snapshot,
        activeSheetId,
        originalMetas,
        originalCells,
        baselineCells,
        serverRevisions,
        withData
      })
    },

    captureBaseline(snapshot, activeSheetId) {
      storeBaselineCells(snapshot)
      baselineStructure = structureSignature(snapshot, activeSheetId)
    },

    hasStructuralChange(snapshot, activeSheetId) {
      const current = structureSignature(snapshot, activeSheetId)
      return baselineStructure === null || current !== baselineStructure
    },

    collectChangedCells(snapshot) {
      return collectChangedCells(snapshot)
    },

    getLocalSheetId(dbSheetId) {
      const target = Number(dbSheetId)
      for (const [localSheetId, storedId] of serverDbIds) {
        if (Number(storedId) === target) return localSheetId
      }
      return null
    },

    getServerSheetId(localSheetId) {
      const storedId = serverDbIds.get(String(localSheetId))
      return storedId == null ? null : Number(storedId)
    },

    acceptBaseline(snapshot, activeSheetId) {
      storeBaselineCells(snapshot)
      baselineStructure = structureSignature(snapshot, activeSheetId)
    },

    getRevisionState(sheetId, row, col) {
      return serverRevisions.get(`${sheetId}:${row}:${col}`) || null
    },

    updateRevisionState(sheetId, row, col, revision, valueText) {
      if (revision == null) {
        serverRevisions.delete(`${sheetId}:${row}:${col}`)
        return
      }
      serverRevisions.set(`${sheetId}:${row}:${col}`, {
        rev: Number(revision),
        valueText: valueText ?? null
      })
    },

    clearRevisionStates(sheetIds) {
      const prefixes = (sheetIds || []).map((id) => `${id}:`)
      for (const key of [...serverRevisions.keys()]) {
        if (prefixes.some((prefix) => key.startsWith(prefix))) {
          serverRevisions.delete(key)
        }
      }
    },

    updateServerSheetIds(snapshot, serverSheetIdMap) {
      if (!serverSheetIdMap || typeof serverSheetIdMap !== 'object') return
      const sheetIds = Array.isArray(snapshot.sheetOrder)
        ? snapshot.sheetOrder
        : Object.keys(snapshot.sheets || {})
      sheetIds.forEach((sheetId, index) => {
        const mapped = serverSheetIdMap[index] ?? serverSheetIdMap[String(index)]
        if (mapped != null) {
          serverDbIds.set(sheetId, Number(mapped))
        }
      })
    }
  }
}

function normalizeSheetList(metas, celldataList) {
  const list = Array.isArray(metas) ? metas : []
  return list.map((meta, index) => ({
    meta,
    celldata: Array.isArray(celldataList) ? celldataList[index] || [] : []
  }))
}

function findStoredWorkbookResources(sheetList) {
  for (const item of sheetList) {
    const resources = item.meta?.config?.[UNIVER_WORKBOOK_RESOURCE_KEY]
    if (Array.isArray(resources) && resources.length > 0) return resources
  }
  return null
}

function applySheetAdvancedConfig(target, sourceConfig) {
  if (!sourceConfig || typeof sourceConfig !== 'object') return
  if (sourceConfig.freeze && typeof sourceConfig.freeze === 'object') {
    target.freeze = { ...DEFAULT_FREEZE, ...sourceConfig.freeze }
  }
  for (const key of Object.keys(SHEET_DEFAULT_VALUE)) {
    if (sourceConfig[key] !== undefined && sourceConfig[key] !== null) {
      target[key] = sourceConfig[key]
    }
  }
}

function collectSheetAdvancedConfig(source) {
  const output = {}
  if (source.freeze && !isDefaultFreeze(source.freeze)) {
    output.freeze = source.freeze
  }
  for (const key of Object.keys(SHEET_DEFAULT_VALUE)) {
    const value = source[key]
    if (value === undefined || value === null) continue
    if (value !== SHEET_DEFAULT_VALUE[key]) {
      output[key] = value
    }
  }
  return output
}

function isDefaultFreeze(freeze) {
  return (
    freeze &&
    (freeze.xSplit === 0 || freeze.xSplit === undefined) &&
    (freeze.ySplit === 0 || freeze.ySplit === undefined) &&
    (freeze.startRow === -1 || freeze.startRow === undefined) &&
    (freeze.startColumn === -1 || freeze.startColumn === undefined)
  )
}

function createStyleStore() {
  const styles = {}
  const keyToId = new Map()
  let nextId = 0

  function appendId(style) {
    const compact = compactObject(style)
    if (Object.keys(compact).length === 0) return undefined
    const stable = stableJson(compact)
    const existing = keyToId.get(stable)
    if (existing) return existing

    const id = `dl-style-${nextId}`
    nextId += 1
    keyToId.set(stable, id)
    styles[id] = compact
    return id
  }

  return { styles, appendId }
}

function buildSheetData(sheetId, celldata, sourceConfig, styleStore, originalCells, baselineCells, serverRevisions) {
  const cellData = {}
  const rowData = {}
  const columnData = {}
  const mergeData = []
  let maxRow = -1
  let maxCol = -1
  const revisionValues = new Map()

  for (const entry of celldata || []) {
    if (!entry || entry.rev == null) continue
    const r = Number(entry.r)
    const c = Number(entry.c)
    if (!Number.isFinite(r) || !Number.isFinite(c)) continue
    const value = entry.v
    revisionValues.set(`${sheetId}:${r}:${c}`, {
      rev: Number(entry.rev),
      valueText: value && typeof value === 'object' && value.v != null
        ? value.v
        : (value === null || value === undefined ? null : value)
    })
  }

  for (const entry of celldata || []) {
    if (!entry) continue
    const r = Number(entry.r)
    const c = Number(entry.c)
    if (!Number.isFinite(r) || !Number.isFinite(c) || r < 0 || c < 0) continue

    const luckyCell = entry.v
    if (isEmptyCell(luckyCell)) continue

    const univerCell = luckyCellToUniver(luckyCell, styleStore)
    if (!univerCell) continue

    if (!cellData[r]) cellData[r] = {}
    cellData[r][c] = univerCell

    originalCells.set(`${sheetId}:${r}:${c}`, luckyCell)
    baselineCells.set(`${sheetId}:${r}:${c}`, {
      v: univerCell.v ?? null,
      f: univerCell.f ?? null,
      s: univerCell.s ?? null,
      p: univerCell.p ?? null
    })
    if (revisionValues.has(`${sheetId}:${r}:${c}`)) {
      serverRevisions.set(`${sheetId}:${r}:${c}`, revisionValues.get(`${sheetId}:${r}:${c}`))
    }

    maxRow = Math.max(maxRow, r)
    maxCol = Math.max(maxCol, c)
  }

  const mergeConfig = sourceConfig.merge || {}
  Object.values(mergeConfig).forEach((range) => {
    if (!range || !Number.isFinite(Number(range.r)) || !Number.isFinite(Number(range.c))) return
    const startRow = Number(range.r)
    const startColumn = Number(range.c)
    const rowSpan = Math.max(Number(range.rs) || 1, 1)
    const colSpan = Math.max(Number(range.cs) || 1, 1)
    mergeData.push({
      startRow,
      startColumn,
      endRow: startRow + rowSpan - 1,
      endColumn: startColumn + colSpan - 1
    })
    maxRow = Math.max(maxRow, startRow + rowSpan - 1)
    maxCol = Math.max(maxCol, startColumn + colSpan - 1)
  })

  Object.entries(sourceConfig.rowlen || {}).forEach(([rowIndex, height]) => {
    const numberHeight = Number(height)
    if (!Number.isFinite(numberHeight) || numberHeight <= 0) return
    rowData[rowIndex] = { h: numberHeight }
  })

  Object.entries(sourceConfig.columnlen || {}).forEach(([columnIndex, width]) => {
    const numberWidth = Number(width)
    if (!Number.isFinite(numberWidth) || numberWidth <= 0) return
    columnData[columnIndex] = { w: numberWidth }
  })

  Object.entries(sourceConfig.rowhidden || {}).forEach(([rowIndex, hidden]) => {
    const row = Number(rowIndex)
    if (!Number.isFinite(row) || row < 0 || !toBoolean(hidden)) return
    if (!rowData[row]) rowData[row] = {}
    rowData[row].hd = 1
  })

  Object.entries(sourceConfig.colhidden || {}).forEach(([columnIndex, hidden]) => {
    const column = Number(columnIndex)
    if (!Number.isFinite(column) || column < 0 || !toBoolean(hidden)) return
    if (!columnData[column]) columnData[column] = {}
    columnData[column].hd = 1
  })

  return {
    cellData,
    rowData,
    columnData,
    mergeData,
    maxRow: Math.max(maxRow, 0),
    maxCol: Math.max(maxCol, 0)
  }
}

function luckyCellToUniver(luckyCell, styleStore) {
  if (typeof luckyCell !== 'object' || luckyCell === null) {
    return { v: luckyCell }
  }

  const cell = {}
  if (Object.prototype.hasOwnProperty.call(luckyCell, 'v')) cell.v = luckyCell.v
  if (Object.prototype.hasOwnProperty.call(luckyCell, 'f')) cell.f = String(luckyCell.f)
  if (
    luckyCell.p &&
    typeof luckyCell.p === 'object' &&
    (luckyCell.p.body || luckyCell.p.dataStream)
  ) {
    cell.p = luckyCell.p
  }
  if (!Object.prototype.hasOwnProperty.call(cell, 'v') && Object.prototype.hasOwnProperty.call(luckyCell, 'm')) {
    cell.v = luckyCell.m
  }

  const cellType = luckyCell.ct?.t
  if (cellType && CELL_TYPE_TO_UNIVER[cellType] !== undefined && !cell.f) {
    cell.t = CELL_TYPE_TO_UNIVER[cellType]
  }

  const style = luckyCellToStyle(luckyCell)
  if (style && Object.keys(style).length > 0) {
    const styleId = styleStore.appendId(style)
    if (styleId) cell.s = styleId
  }

  if (
    !Object.prototype.hasOwnProperty.call(cell, 'v') &&
    !Object.prototype.hasOwnProperty.call(cell, 'f') &&
    !cell.s &&
    !Object.prototype.hasOwnProperty.call(cell, 'p')
  ) {
    return null
  }

  return cell
}

function luckyCellToStyle(luckyCell) {
  const style = {}
  if (luckyCell.ct?.fa && luckyCell.ct.fa !== 'General') {
    style.n = { pattern: String(luckyCell.ct.fa) }
  }
  if (luckyCell.bg !== undefined && luckyCell.bg !== null) style.bg = { rgb: normalizeColor(luckyCell.bg) }
  if (luckyCell.ff) style.ff = String(luckyCell.ff)
  if (luckyCell.fs !== undefined && luckyCell.fs !== null) style.fs = Number(luckyCell.fs)
  if (luckyCell.fc !== undefined && luckyCell.fc !== null) style.cl = { rgb: normalizeColor(luckyCell.fc) }
  if (toBoolean(luckyCell.bl)) style.bl = 1
  if (toBoolean(luckyCell.it)) style.it = 1
  if (toBoolean(luckyCell.ul)) style.ul = { s: 1, t: 12 }
  if (toBoolean(luckyCell.cl)) style.st = { s: 1, t: 12 }
  if (luckyCell.ht !== undefined && LUCKY_HT_TO_UNIVER[luckyCell.ht] !== undefined) {
    style.ht = LUCKY_HT_TO_UNIVER[luckyCell.ht]
  }
  if (luckyCell.vt !== undefined && LUCKY_VT_TO_UNIVER[luckyCell.vt] !== undefined) {
    style.vt = LUCKY_VT_TO_UNIVER[luckyCell.vt]
  }
  if (luckyCell.tb !== undefined && LUCKY_TB_TO_UNIVER[luckyCell.tb] !== undefined) {
    style.tb = LUCKY_TB_TO_UNIVER[luckyCell.tb]
  }
  if (luckyCell.bd && typeof luckyCell.bd === 'object') {
    const border = luckyBorderToUniver(luckyCell.bd)
    if (border) style.bd = border
  }
  if (luckyCell.tr !== undefined && LUCKY_TR_TO_UNIVER[luckyCell.tr]) {
    style.tr = LUCKY_TR_TO_UNIVER[luckyCell.tr]
  }
  return compactObject(style)
}

function serializeWorkbook({ snapshot, activeSheetId, originalMetas, originalCells, baselineCells, serverRevisions, withData }) {
  const styles = snapshot.styles || {}
  const output = []
  const sheetIds = Array.isArray(snapshot.sheetOrder) ? snapshot.sheetOrder : Object.keys(snapshot.sheets || {})
  const workbookResources = Array.isArray(snapshot.resources) ? snapshot.resources : []

  sheetIds.forEach((sheetId, visibleIndex) => {
    const source = snapshot.sheets?.[sheetId] || {}
    const original = originalMetas.get(sheetId) || {}
    const baseConfig = original.config || {}
    const merged = {}
    const rowlen = {}
    const columnlen = {}
    const rowhidden = {}
    const colhidden = {}
    const celldata = []
    const dataMatrix = []
    let maxRow = -1
    let maxCol = -1

    for (const range of source.mergeData || []) {
      if (!range || !Number.isFinite(Number(range.startRow))) continue
      const r = Number(range.startRow)
      const c = Number(range.startColumn)
      const endRow = Number(range.endRow ?? r)
      const endColumn = Number(range.endColumn ?? c)
      merged[`${r}_${c}`] = {
        r,
        c,
        rs: Math.max(endRow - r + 1, 1),
        cs: Math.max(endColumn - c + 1, 1)
      }
      maxRow = Math.max(maxRow, endRow)
      maxCol = Math.max(maxCol, endColumn)
    }

    for (const [rowKey, cells] of Object.entries(source.rowData || {})) {
      const height = cells?.h ?? cells?.[0]?.h
      // 等于默认行高的条目不进 rowlen：Univer 在新行输入内容时会自动写
      // 默认行高，若照单全收，"在空白区域首次输入"就会因 config 变化被
      // 误判为结构变更，触发整册重建。真实改过行高（≠默认值）不受影响。
      if (height !== undefined && Number(height) > 0
          && Number(height) !== SHEET_DEFAULT_VALUE.defaultRowHeight) {
        rowlen[rowKey] = Number(height)
      }
      if (toBoolean(cells?.hd ?? cells?.[0]?.hd)) {
        rowhidden[rowKey] = 1
      }
    }

    for (const [columnKey, cells] of Object.entries(source.columnData || {})) {
      const width = cells?.w ?? cells?.[0]?.w
      if (width !== undefined && Number(width) > 0) {
        columnlen[columnKey] = Number(width)
      }
      if (toBoolean(cells?.hd ?? cells?.[0]?.hd)) {
        colhidden[columnKey] = 1
      }
    }

    for (const [rowKey, rowCells] of Object.entries(source.cellData || {})) {
      for (const [columnKey, cell] of Object.entries(rowCells || {})) {
        const r = Number(rowKey)
        const c = Number(columnKey)
        const luckyCell = serializeLuckyCell(
          cell,
          styles,
          originalCells.get(`${sheetId}:${r}:${c}`),
          baselineCells.get(`${sheetId}:${r}:${c}`)
        )

        if (!luckyCell) continue
        delete luckyCell[INTERNAL_REV_FIELD]
        const revision = serverRevisions?.get(`${sheetId}:${r}:${c}`)?.rev
        celldata.push({
          r,
          c,
          v: luckyCell,
          ...(revision != null ? { rev: revision } : {})
        })
        if (withData) {
          if (!dataMatrix[r]) dataMatrix[r] = []
          dataMatrix[r][c] = luckyCell
        }
        maxRow = Math.max(maxRow, r)
        maxCol = Math.max(maxCol, c)
      }
    }

    const rowCount = clampNumber(source.rowCount, maxRow + 1, 1, MAX_EXCEL_ROWS)
    const columnCount = clampNumber(source.columnCount, maxCol + 1, 1, MAX_EXCEL_COLUMNS)

    const config = { ...baseConfig }
    delete config[UNIVER_WORKBOOK_RESOURCE_KEY]
    delete config[UNIVER_SHEET_CONFIG_KEY]
    delete config[UNIVER_SHEET_ID_KEY]

    const sheetConfig = collectSheetAdvancedConfig(source)
    if (Object.keys(sheetConfig).length > 0) {
      config[UNIVER_SHEET_CONFIG_KEY] = sheetConfig
    }
    config[UNIVER_SHEET_ID_KEY] = sheetId
    if (visibleIndex === 0 && workbookResources.length > 0) {
      config[UNIVER_WORKBOOK_RESOURCE_KEY] = workbookResources
    }

    if (Object.keys(rowhidden).length > 0) config.rowhidden = rowhidden
    if (Object.keys(colhidden).length > 0) config.colhidden = colhidden

    output.push({
      name: source.name || `Sheet${visibleIndex + 1}`,
      status: activeSheetId === sheetId ? 1 : 0,
      order: visibleIndex,
      row: rowCount,
      column: columnCount,
      config: {
        ...config,
        merge: merged,
        columnlen,
        rowlen,
        ...(Object.keys(rowhidden).length > 0 ? { rowhidden } : {}),
        ...(Object.keys(colhidden).length > 0 ? { colhidden } : {})
      },
      hyperlink: original.hyperlink || {},
      images: original.images || {},
      luckysheet_conditionformat_save: original.conditionFormats || [],
      chart: original.chart || [],
      celldata,
      ...(withData ? { data: dataMatrix } : {})
    })
  })

  return output
}

function serializeLuckyCell(cell, styles, legacy, baseline) {
  const current = {
    v: cell?.v ?? null,
    f: cell?.f ?? null,
    s: cell?.s ?? null,
    p: cell?.p ?? null
  }

  if (
    baseline &&
    baseline.v === current.v &&
    baseline.f === current.f &&
    baseline.s === current.s &&
    stableJson(baseline.p) === stableJson(current.p)
  ) {
    return legacy || makeLuckyCellFromCurrent(cell, styles, legacy)
  }

  return makeLuckyCellFromCurrent(cell, styles, legacy)
}

function makeLuckyCellFromCurrent(cell, styles, legacy) {
  const output = { ...(legacy && typeof legacy === 'object' ? legacy : {}) }
  const univerStyle = getStyle(styles, cell?.s)

  if (cell?.v !== undefined) {
    output.v = cell.v
  } else {
    delete output.v
  }

  if (cell?.f !== undefined) {
    output.f = cell.f
  } else {
    delete output.f
  }

  if (cell?.p !== undefined) {
    output.p = cell.p
  } else {
    delete output.p
  }

  applyStyleToLucky(output, univerStyle, cell)

  return Object.keys(output).length > 0 ? output : null
}

function getStyle(styles, styleId) {
  if (!styleId) return undefined
  if (typeof styleId === 'string') return styles?.[styleId] || undefined
  if (typeof styleId === 'object') return styleId
  return undefined
}

function applyStyleToLucky(output, style, cell) {
  if (cell?.t !== undefined && UNIVER_TO_CELL_TYPE[cell.t] !== undefined && !output.ct) {
    output.ct = { t: UNIVER_TO_CELL_TYPE[cell.t], fa: output.ct?.fa || 'General' }
  }
  if (!style) return

  if (style.n?.pattern) {
    const oldType = output.ct?.t || 'n'
    output.ct = { ...(output.ct || {}), t: output.ct?.t || oldType, fa: style.n.pattern }
  }
  if (style.bg !== undefined && style.bg !== null) output.bg = exposeColor(style.bg)
  if (style.ff !== undefined) output.ff = String(style.ff)
  if (style.fs !== undefined) output.fs = Number(style.fs)
  if (style.cl !== undefined && style.cl !== null) output.fc = exposeColor(style.cl)
  if (style.bl) output.bl = 1
  if (style.it) output.it = 1
  if (style.ul?.s) output.ul = 1
  if (style.st?.s) output.cl = 1
  if (style.ht !== undefined && UNIVER_HT_TO_LUCKY[style.ht] !== undefined) output.ht = UNIVER_HT_TO_LUCKY[style.ht]
  if (style.vt !== undefined && UNIVER_VT_TO_LUCKY[style.vt] !== undefined) output.vt = UNIVER_VT_TO_LUCKY[style.vt]
  if (style.tb !== undefined && UNIVER_TB_TO_LUCKY[style.tb] !== undefined) output.tb = UNIVER_TB_TO_LUCKY[style.tb]
  if (style.bd) output.bd = univerBorderToLucky(style.bd)
  if (style.tr) output.tr = univerTextRotationToLucky(style.tr)
}

function luckyBorderToUniver(borderData) {
  const output = {}
  for (const side of BORDER_SIDE_KEYS) {
    const entry = borderData?.[side]
    if (!entry) continue
    const style = Number(entry.s ?? entry.style)
    if (!Number.isFinite(style) || style <= 0 || style > 13) continue
    const color = entry.cl?.rgb ?? entry.color
    output[side] = {
      s: Math.round(style),
      cl: { rgb: normalizeColor(color) }
    }
  }
  return Object.keys(output).length > 0 ? output : undefined
}

function univerBorderToLucky(borderData) {
  const output = {}
  for (const side of BORDER_SIDE_KEYS) {
    const entry = borderData?.[side]
    if (!entry) continue
    const style = Number(entry.s)
    if (!Number.isFinite(style) || style <= 0 || style > 13) continue
    output[side] = {
      style: Math.round(style),
      color: exposeColor(entry.cl) || '#000000'
    }
  }
  return Object.keys(output).length > 0 ? output : undefined
}

function univerTextRotationToLucky(rotation) {
  const angle = Number(rotation?.a)
  if (rotation?.v === 1 && (!Number.isFinite(angle) || angle === 0)) return 3
  if (angle === 45) return 1
  if (angle === -45) return 2
  if (angle === 90) return 4
  if (angle === -90) return 5
  return 0
}

function normalizeColor(color) {
  if (!color) return '#000000'
  const first = typeof color === 'string' ? color : color?.rgb || color?.color || color
  const text = String(first).trim()
  if (/^[0-9a-fA-F]{6}$/.test(text)) return `#${text.toUpperCase()}`
  if (/^[0-9a-fA-F]{3}$/.test(text)) {
    return `#${text.split('').map((char) => char + char).join('').toUpperCase()}`
  }
  return text
}

function exposeColor(color) {
  if (typeof color === 'string') return color
  const rgb = color?.rgb || color?.color
  return typeof rgb === 'string' ? rgb : undefined
}

function toBoolean(value) {
  return value === true || value === 1 || value === '1'
}

function isEmptyCell(cell) {
  if (cell === undefined || cell === null) return true
  if (typeof cell !== 'object') return false
  if (Array.isArray(cell)) return cell.length === 0
  return Object.keys(cell).length === 0
}

function compactObject(source) {
  if (!source || typeof source !== 'object') return {}
  const result = {}
  for (const [key, value] of Object.entries(source)) {
    if (value === undefined || value === null) continue
    if (typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0) continue
    result[key] = value
  }
  return result
}

function stableJson(value) {
  return JSON.stringify(sortObject(value))
}

function sortObject(value) {
  if (Array.isArray(value)) return value.map(sortObject)
  if (value && typeof value === 'object') {
    return Object.keys(value)
      .sort()
      .reduce((result, key) => {
        result[key] = sortObject(value[key])
        return result
      }, {})
  }
  return value
}

function clampNumber(value, fallback, minimum, maximum) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue) || numberValue <= 0) {
    return Math.min(Math.max(fallback, minimum), maximum)
  }
  return Math.min(Math.max(numberValue, minimum), maximum)
}
