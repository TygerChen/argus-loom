/**
 * 行级数据指纹：明细行内容拼成明文指纹，用于行内编辑的“冲突检测”。
 *
 * <p>为什么不依赖后端 version 字段：财务明细的行内编辑是“离线改、整行提交”，
 * 服务端只需知道“你看到的行和我存的行是不是同一份”。把 18 个业务字段按
 * 固定顺序拼接（金额统一为去尾零数字文本），提交时带上打开编辑器那一刻的
 * 指纹；服务端指纹不一致就说明期间被人改过 → 拒绝覆盖。</p>
 *
 * <p>注意：当前服务端 FinanceImportService.recordFingerprint 使用同一套
 * 字段与格式（不额外做 SHA-256），这里必须与服务端保持一致，否则乐观锁
 * 会误判为冲突。</p>
 */

/** 参与指纹的业务字段：金额用数值化文本（去前导零），其余用原样文本 */
const FIELDS = [
  'dayLabel', 'summary', 'counterparty', 'department', 'category', 'expenseName',
  'businessDate', 'debit', 'credit', 'balance', 'prevBalance', 'income', 'expense',
  'fixedPurchase', 'fixedRedemption', 'fixedBalance', 'currencyCode', 'warnings'
]

/** 计算一行财务明细的指纹（与服务端 recordFingerprint 一致的明文） */
export function computeRecordFingerprint(row) {
  return FIELDS.map((field) => (isAmount(field) ? amountText(row[field]) : fpText(row[field]))).join('|')
}

function isAmount(field) {
  return [
    'debit', 'credit', 'balance', 'prevBalance', 'income', 'expense',
    'fixedPurchase', 'fixedRedemption', 'fixedBalance'
  ].includes(field)
}

/** 文本字段：null/undefined/空串 → ''（与其他空值在指纹里不可区分） */
function fpText(value) {
  if (value === null || value === undefined || value === '') return ''
  return String(value)
}

/** 金额字段：可解析为数字时用数字文本（1.10 与 1.1 视为相同），否则原样 */
function amountText(value) {
  if (value === null || value === undefined || value === '') return ''
  const number = Number(value)
  return Number.isFinite(number) ? String(number) : String(value)
}

/**
 * 纯 JS SHA-256（FIPS 180-4）：输入按 UTF-8 编码，输出 64 位 16 进制。
 * 实现即标准算法：填充 → 64 轮压缩（K 常量表 + 消息调度 W）→ 8 个累积寄存器。
 */
export function sha256Hex(input) {
  const utf8 = unescape(encodeURIComponent(input))
  const bytes = []
  for (let i = 0; i < utf8.length; i += 1) bytes.push(utf8.charCodeAt(i))
  bytes.push(0x80)
  while (bytes.length % 64 !== 56) bytes.push(0)
  const bitLength = utf8.length * 8
  const high = Math.floor(bitLength / 0x100000000)
  bytes.push((high >>> 24) & 0xff, (high >>> 16) & 0xff, (high >>> 8) & 0xff, high & 0xff)
  bytes.push((bitLength >>> 24) & 0xff, (bitLength >>> 16) & 0xff, (bitLength >>> 8) & 0xff, bitLength & 0xff)

  const K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ]
  const H = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  ]
  const W = Array.from({ length: 64 })
  const mask = 0xffffffff

  for (let offset = 0; offset < bytes.length; offset += 64) {
    for (let t = 0; t < 16; t += 1) {
      const index = offset + t * 4
      W[t] = ((bytes[index] << 24) | (bytes[index + 1] << 16) | (bytes[index + 2] << 8) | bytes[index + 3]) >>> 0
    }
    for (let t = 16; t < 64; t += 1) {
      const s0 = rotr(W[t - 15], 7) ^ rotr(W[t - 15], 18) ^ (W[t - 15] >>> 3)
      const s1 = rotr(W[t - 2], 17) ^ rotr(W[t - 2], 19) ^ (W[t - 2] >>> 10)
      W[t] = (W[t - 16] + s0 + W[t - 7] + s1) >>> 0
    }

    let a = H[0]
    let b = H[1]
    let c = H[2]
    let d = H[3]
    let e = H[4]
    let f = H[5]
    let g = H[6]
    let h = H[7]

    for (let t = 0; t < 64; t += 1) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + S1 + ch + K[t] + W[t]) & mask
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) & mask

      h = g
      g = f
      f = e
      e = (d + temp1) & mask
      d = c
      c = b
      b = a
      a = (temp1 + temp2) & mask
    }

    H[0] = (H[0] + a) & mask
    H[1] = (H[1] + b) & mask
    H[2] = (H[2] + c) & mask
    H[3] = (H[3] + d) & mask
    H[4] = (H[4] + e) & mask
    H[5] = (H[5] + f) & mask
    H[6] = (H[6] + g) & mask
    H[7] = (H[7] + h) & mask
  }

  return H.map((value) => value.toString(16).padStart(8, '0')).join('')
}

function rotr(value, shift) {
  return ((value >>> shift) | (value << (32 - shift))) >>> 0
}
