/**
 * 协同编辑的纯函数助手：不依赖组件状态，可独立单测。
 * 从 SheetEditor.vue 抽出，减少主组件体积（P2 可读性拆分第一步）。
 */

/** 判定哪些 Univer 命令是“只读操作”（激活/滚动/缩放等），不计入脏标记 */
export const READONLY_COMMAND_MARKERS = [
  'activate',
  'selection',
  'view',
  'scroll',
  'zoom',
  'close',
  'spell'
]

export function isEditCommand(commandId) {
  if (!commandId || !commandId.startsWith('sheet.command.')) return false
  return !READONLY_COMMAND_MARKERS.some((marker) => commandId.includes(marker))
}

/**
 * 归一化一个选区范围：补齐缺省 end 字段、过滤非法值。
 * 无效选区返回 null，调用方负责丢弃。
 */
export function normalizeSelectionRange(range) {
  if (!range || typeof range !== 'object') return null
  const startRow = Number(range.startRow ?? range.row ?? -1)
  const endRow = Number(range.endRow ?? range.row ?? range.startRow ?? startRow)
  const startColumn = Number(range.startColumn ?? range.column ?? -1)
  const endColumn = Number(range.endColumn ?? range.column ?? range.startColumn ?? startColumn)
  if (
    !Number.isFinite(startRow) || !Number.isFinite(endRow) ||
    !Number.isFinite(startColumn) || !Number.isFinite(endColumn) ||
    startRow < 0 || endRow < startRow ||
    startColumn < 0 || endColumn < startColumn
  ) {
    return null
  }
  return { startRow, endRow, startColumn, endColumn }
}

/** 协同头像色板：按用户名哈希取色，同一用户在各端颜色一致 */
export const COLLAB_COLORS = ['#2f7d57', '#3f6fb5', '#d08a2e', '#a8538a', '#5b7e3f', '#3d7f8f']

/** 用户名 → 稳定颜色（纯哈希，无状态） */
export function avatarColor(user) {
  const seed = String(user?.username || 'guest')
    .split('')
    .reduce((sum, char) => sum + char.charCodeAt(0), 0)
  return COLLAB_COLORS[seed % COLLAB_COLORS.length]
}

/**
 * 把协同通道里的 Luckysheet 单元格值转成 Univer 的 setValue 形态。
 * 规则：保留 v/f/p；没有 v 时用 m 兜底；空对象视为清空（{ v: null }）。
 */
export function remoteLuckyToUniver(luckyCell) {
  if (luckyCell === undefined || luckyCell === null) return { v: null }
  if (typeof luckyCell !== 'object') return { v: luckyCell }

  const cell = {}
  if (Object.prototype.hasOwnProperty.call(luckyCell, 'v')) cell.v = luckyCell.v
  if (Object.prototype.hasOwnProperty.call(luckyCell, 'f')) cell.f = String(luckyCell.f)
  if (luckyCell.p && typeof luckyCell.p === 'object') cell.p = luckyCell.p
  if (
    !Object.prototype.hasOwnProperty.call(cell, 'v') &&
    Object.prototype.hasOwnProperty.call(luckyCell, 'm')
  ) {
    cell.v = luckyCell.m
  }
  return Object.keys(cell).length > 0 ? cell : { v: null }
}
