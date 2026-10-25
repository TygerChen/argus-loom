<template>
  <main class="dl-page finance-page">
    <section class="dl-shell finance-shell">
      <header class="finance-header">
        <div>
          <p class="eyebrow">Argus Finance</p>
          <h1>财务数据中心</h1>
          <p class="finance-summary">财务工作簿导入后抽取标准流水，按区域、账户、币种查看汇总并在线编辑明细。</p>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" :loading="loadingAll" @click="loadAll">刷新</el-button>
          <el-button type="primary" :icon="UploadFilled" :loading="importing" @click="selectFile">
            导入财务工作簿
          </el-button>
          <el-button :icon="FolderOpened" @click="goDashboard">Excel 工作台</el-button>
          <input ref="fileInput" class="file-input" type="file" accept=".xlsx,.xls" @change="handleFileChange" />
        </div>
      </header>

      <section class="filter-panel">
        <el-select v-model="filters.regionName" class="filter-item" placeholder="全部区域" clearable @change="applyFilters">
          <el-option label="北京" value="北京" />
          <el-option label="唐山" value="唐山" />
          <el-option label="其他单位" value="其他单位" />
        </el-select>
        <el-select v-model="filters.currencyCode" class="filter-item" placeholder="全部币种" clearable @change="applyFilters">
          <el-option v-for="code in currencyOptions" :key="code" :label="code" :value="code" />
        </el-select>
        <el-select v-model="filters.sheetName" class="filter-item" placeholder="全部账户" clearable @change="applyFilters">
          <el-option v-for="sheet in sheetOptions" :key="sheet" :label="sheet" :value="sheet" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          class="filter-item"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          @change="applyFilters"
        />
        <el-input
          v-model="filters.keyword"
          class="filter-item filter-keyword"
          placeholder="摘要 / 往来单位 / 部门"
          clearable
          @keyup.enter="applyFilters"
          @clear="loadAll"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" :icon="Search" @click="applyFilters">查询</el-button>
        <el-button :icon="RefreshLeft" @click="resetFilters">重置</el-button>
      </section>

      <section class="metrics-grid" v-loading="loadingAll">
        <div class="metric metric-accent"><span>流水记录</span><strong>{{ overview.recordCount ?? 0 }}</strong></div>
        <div class="metric"><span>导入批次</span><strong>{{ overview.batchCount ?? 0 }}</strong></div>
        <div class="metric"><span>账户口径</span><strong>{{ overview.accountCount ?? 0 }}</strong></div>
        <div class="metric"><span>期间</span><strong class="metric-text">{{ dateRangeText }}</strong></div>
        <div class="metric"><span>借方合计</span><strong>{{ formatAmount(overview.debitTotal) }}</strong></div>
        <div class="metric"><span>贷方合计</span><strong>{{ formatAmount(overview.creditTotal) }}</strong></div>
        <div class="metric"><span>收入合计</span><strong>{{ formatAmount(overview.incomeTotal) }}</strong></div>
        <div class="metric metric-warn"><span>异常提示</span><strong>{{ overview.warningCount ?? 0 }}</strong></div>
      </section>

      <section class="finance-panel">
        <div class="panel-head">
          <div>
            <h2>账户汇总</h2>
            <p>点击“查看”进入该账户，按原导入批次和 Sheet 定位流水。</p>
          </div>
          <el-tag v-if="filters.batchId" type="success" effect="light" closable @close="clearAccountFilter">已锁定账户</el-tag>
          <span v-else class="scope-empty">当前为全部账户</span>
        </div>
        <el-table v-loading="loadingAccounts" :data="accounts" size="small" stripe max-height="440" empty-text="暂无账户汇总">
          <el-table-column prop="regionName" label="区域" width="96" />
          <el-table-column label="账套 / 版本" width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="book-cell">
                <span>{{ row.bookKey || '-' }}</span>
                <el-tag size="small" :type="row.batchVersion && row.batchVersion > 1 ? 'success' : 'info'" effect="plain">
                  v{{ row.batchVersion || 1 }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="源文件" min-width="210" show-overflow-tooltip />
          <el-table-column prop="sheetName" label="账户" min-width="170" show-overflow-tooltip />
          <el-table-column prop="currencyCode" label="币种" width="86" />
          <el-table-column prop="recordCount" label="笔数" width="86" align="right" />
          <el-table-column label="首日" width="110"><template #default="{ row }">{{ row.firstDate || '-' }}</template></el-table-column>
          <el-table-column label="末日" width="110"><template #default="{ row }">{{ row.lastDate || '-' }}</template></el-table-column>
          <el-table-column label="期初余额" width="140" align="right"><template #default="{ row }">{{ formatAmount(row.openingBalance) }}</template></el-table-column>
          <el-table-column label="借方合计" width="140" align="right"><template #default="{ row }">{{ formatAmount(row.debitTotal) }}</template></el-table-column>
          <el-table-column label="贷方合计" width="140" align="right"><template #default="{ row }">{{ formatAmount(row.creditTotal) }}</template></el-table-column>
          <el-table-column label="收入合计" width="140" align="right"><template #default="{ row }">{{ formatAmount(row.incomeTotal) }}</template></el-table-column>
          <el-table-column label="支出合计" width="140" align="right"><template #default="{ row }">{{ formatAmount(row.expenseTotal) }}</template></el-table-column>
          <el-table-column label="期末余额" width="140" align="right"><template #default="{ row }">{{ formatAmount(row.endingBalance) }}</template></el-table-column>
          <el-table-column label="操作" width="160" align="center" fixed="right">
            <template #default="{ row }">
              <div class="account-actions">
                <el-button link type="primary" @click="lookIntoAccount(row)">查看</el-button>
                <el-button link @click="openVersionHistory(row)">版本</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="finance-panel">
        <div class="panel-head">
          <div>
            <h2>每日发生额</h2>
            <p>按业务日期汇总借方、贷方、收入与支出，最多展示最近 30 个有数据的工作日。</p>
          </div>
        </div>
        <el-table v-loading="loadingDaily" :data="dailyRows" size="small" stripe empty-text="暂无每日汇总">
          <el-table-column prop="businessDate" label="日期" min-width="130" />
          <el-table-column prop="recordCount" label="笔数" width="110" align="right" />
          <el-table-column label="借方" width="170" align="right"><template #default="{ row }">{{ formatAmount(row.debitTotal) }}</template></el-table-column>
          <el-table-column label="贷方" width="170" align="right"><template #default="{ row }">{{ formatAmount(row.creditTotal) }}</template></el-table-column>
          <el-table-column label="收入" width="170" align="right"><template #default="{ row }">{{ formatAmount(row.incomeTotal) }}</template></el-table-column>
          <el-table-column label="支出" width="170" align="right"><template #default="{ row }">{{ formatAmount(row.expenseTotal) }}</template></el-table-column>
        </el-table>
      </section>

      <section class="finance-panel detail-panel">
        <div class="panel-head">
          <div>
            <h2>明细维护</h2>
            <p>{{ detailScopeText }}，点击“编辑”后修改行内字段并保存。</p>
          </div>
          <div class="detail-actions">
            <el-button v-if="editingId !== null" :disabled="submitting" @click="cancelCurrentEdit">取消编辑</el-button>
            <el-button type="primary" :disabled="!dirtyId" :loading="submitting" @click="saveCurrentEdit">保存当前行</el-button>
          </div>
        </div>

        <el-table v-loading="loadingRecords" :data="records" size="small" stripe row-key="id" :max-height="560" empty-text="暂无明细">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="日期" width="150">
            <template #default="{ row }">
              <el-date-picker v-if="isEditing(row)" v-model="row.businessDate" type="date" value-format="YYYY-MM-DD" size="small" @change="markDirty(row)" />
              <span v-else>{{ row.businessDate || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag :type="row.recordType === 'OPENING' ? 'warning' : 'info'" size="small" effect="plain">{{ row.recordType === 'OPENING' ? '期初' : '流水' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="摘要" min-width="200">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.summary" size="small" @input="markDirty(row)" />
              <span v-else class="cell-text">{{ row.summary || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="往来单位" min-width="150">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.counterparty" size="small" @input="markDirty(row)" />
              <span v-else class="cell-text">{{ row.counterparty || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="部门 / 科目" min-width="160">
            <template #default="{ row }">
              <div class="cell-stack">
                <el-input v-if="isEditing(row)" v-model="row.department" size="small" placeholder="部门" @input="markDirty(row)" />
                <span v-else class="muted">{{ row.department || '-' }}</span>
                <el-input v-if="isEditing(row)" v-model="row.category" size="small" placeholder="科目" @input="markDirty(row)" />
                <span v-else class="muted">{{ row.category || '-' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="借方" width="120" align="right">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.debit" size="small" class="amount-input" @input="markDirty(row)" />
              <span v-else>{{ formatAmount(row.debit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="贷方" width="120" align="right">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.credit" size="small" class="amount-input" @input="markDirty(row)" />
              <span v-else>{{ formatAmount(row.credit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="余额" width="140" align="right">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.balance" size="small" class="amount-input" @input="markDirty(row)" />
              <span v-else>{{ formatAmount(row.balance) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="上日余额" width="140" align="right">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.prevBalance" size="small" class="amount-input" @input="markDirty(row)" />
              <span v-else>{{ formatAmount(row.prevBalance) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="收入 / 支出" width="160" align="right">
            <template #default="{ row }">
              <div class="cell-stack">
                <el-input v-if="isEditing(row)" v-model="row.income" size="small" class="amount-input" placeholder="收入" @input="markDirty(row)" />
                <span v-else>{{ formatAmount(row.income) }}</span>
                <el-input v-if="isEditing(row)" v-model="row.expense" size="small" class="amount-input" placeholder="支出" @input="markDirty(row)" />
                <span v-else>{{ formatAmount(row.expense) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="币种" width="90">
            <template #default="{ row }">
              <el-input v-if="isEditing(row)" v-model="row.currencyCode" size="small" @input="markDirty(row)" />
              <el-tag v-else size="small" effect="plain">{{ row.currencyCode || 'CNY' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center" fixed="right">
            <template #default="{ row }">
              <el-button v-if="isEditing(row)" link :disabled="submitting" @click="saveEdit(row)">保存</el-button>
              <el-button v-else link type="primary" @click="enterEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-wrap">
          <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev, pager, next, total" background @current-change="loadRecords" />
        </div>
      </section>
    </section>

    <el-dialog v-model="versionVisible" title="版本历史" width="min(760px, calc(100% - 32px))">
      <div class="version-meta">
        <span>账套</span><strong>{{ versionBook || '-' }}</strong>
        <el-tag v-if="currentVersionLabel" type="success" size="small" effect="light">当前 v{{ currentVersionLabel }}</el-tag>
      </div>
      <el-table v-loading="versionLoading" :data="versionRows" size="small" stripe empty-text="暂无版本记录">
        <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.batchVersion || 1 }}</template></el-table-column>
        <el-table-column prop="fileName" label="源文件" min-width="210" show-overflow-tooltip />
        <el-table-column label="导入时间" width="170"><template #default="{ row }">{{ formatTime(row.createTime) }}</template></el-table-column>
        <el-table-column prop="mappedRows" label="记录" width="80" align="right" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.isCurrent" type="success" size="small">当前</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">历史</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewVersion(row)">查看</el-button>
            <el-button link :disabled="Boolean(row.isCurrent) || versionActivating" @click="activateVersion(row)">设为当前</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
/**
 * 财务数据中心 —— 07 篇的主战场页面。
 *
 * <p>从上到下四块：筛选面板（区域/币种/账户/日期，对应后端
 * pageFilteredRecords 的多条件查询）、汇总卡片（总览数字，financeOverview）、
 * 批次版本列表（listBatchVersions，可切换当前版本）、明细表格（行内编辑，
 * 提交时带 fingerprint 乐观锁，见 utils/fingerprint.js）。</p>
 *
 * <p><b>导入交互</b>（07 篇"先预览后入账"的前端半边）：选文件 → 上传 →
 * 后端返回校验报告（跳过的 Sheet、警告行）→ 展示给用户 → 确认后才生效。
 * 重复文件会被后端 FINANCE_FILE_DUPLICATED 拦截，错误信息里带了出路提示。</p>
 */
/* eslint-disable @typescript-eslint/no-explicit-any -- finance batch/version payloads mirror arbitrary Excel JSON */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderOpened, Refresh, RefreshLeft, Search, UploadFilled } from '@element-plus/icons-vue'
import {
  activateBatchVersion,
  getAccountSummaries,
  getBatchVersions,
  getDailySummaries,
  getFinanceOverview,
  getFinanceRecords,
  importFinanceFile,
  updateFinanceRecords,
} from '@/api/finance'
import { computeRecordFingerprint } from '@/utils/fingerprint'

const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const PAGE_SIZE = 100
const importing = ref(false)
const loadingAll = ref(false)
const loadingOverview = ref(false)
const loadingAccounts = ref(false)
const loadingDaily = ref(false)
const loadingRecords = ref(false)
const overview = ref<Record<string, any>>({})
const accounts = ref<any[]>([])
const daily = ref<any[]>([])
const records = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = PAGE_SIZE
const submitting = ref(false)
const editingId = ref<number | null>(null)
const originals = new Map<number, { base: Record<string, any>; fingerprint: string }>()

const filters = reactive({
  regionName: '',
  currencyCode: '',
  sheetName: '',
  batchId: null as number | null,
  keyword: '',
  from: '',
  to: '',
})
const dateRange = ref<string[]>([])

function buildFilterParams() {
  const params: Record<string, any> = {}
  if (filters.regionName) params.regionName = filters.regionName
  if (filters.currencyCode) params.currencyCode = filters.currencyCode
  if (filters.sheetName) params.sheetName = filters.sheetName
  if (filters.batchId) params.batchId = filters.batchId
  if (filters.from) params.from = filters.from
  if (filters.to) params.to = filters.to
  return params
}

async function loadOverview() {
  loadingOverview.value = true
  try {
    overview.value = (await getFinanceOverview(buildFilterParams())) || {}
  } catch (error: any) {
    ElMessage.error(`汇总加载失败：${error.message}`)
  } finally {
    loadingOverview.value = false
  }
}

async function loadAccounts() {
  loadingAccounts.value = true
  try {
    accounts.value = (await getAccountSummaries(buildFilterParams())) || []
  } catch (error: any) {
    ElMessage.error(`账户汇总加载失败：${error.message}`)
  } finally {
    loadingAccounts.value = false
  }
}

async function loadDaily() {
  loadingDaily.value = true
  try {
    daily.value = (await getDailySummaries(buildFilterParams())) || []
  } catch (error: any) {
    ElMessage.error(`每日汇总加载失败：${error.message}`)
  } finally {
    loadingDaily.value = false
  }
}

async function loadRecords() {
  loadingRecords.value = true
  try {
    const params = {
      ...buildFilterParams(),
      keyword: filters.keyword || undefined,
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
    }
    const data = await getFinanceRecords(params)
    records.value = data.records || []
    total.value = Number(data.total || 0)
    discardRowEdit()
  } catch (error: any) {
    ElMessage.error(`明细加载失败：${error.message}`)
  } finally {
    loadingRecords.value = false
  }
}

async function loadAll() {
  loadingAll.value = true
  try {
    await Promise.all([loadOverview(), loadAccounts(), loadDaily(), loadRecords()])
  } finally {
    loadingAll.value = false
  }
}

function applyFilters() {
  const range = dateRange.value
  if (range && range.length === 2) {
    filters.from = range[0] || ''
    filters.to = range[1] || ''
  } else {
    filters.from = ''
    filters.to = ''
  }
  pageNum.value = 1
  loadAll()
}

function resetFilters() {
  filters.regionName = ''
  filters.currencyCode = ''
  filters.sheetName = ''
  filters.batchId = null
  filters.keyword = ''
  filters.from = ''
  filters.to = ''
  dateRange.value = []
  pageNum.value = 1
  loadAll()
}

function lookIntoAccount(row: any) {
  cancelCurrentEdit()
  filters.batchId = row.batchId
  filters.sheetName = row.sheetName
  filters.currencyCode = row.currencyCode
  pageNum.value = 1
  loadAll()
  ElMessage.success(`已定位到 ${row.regionName || ''} ${row.sheetName || ''} 账户`)
}

function clearAccountFilter() {
  filters.batchId = null
  filters.sheetName = ''
  filters.currencyCode = ''
  pageNum.value = 1
  loadAll()
}

function selectFile() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    const result = await importFinanceFile(file, {
      bookKey: '',
      regionName: '',
      currencyCode: 'CNY',
    })
    ElMessage.success(`财务导入完成，写入 ${result.mappedRows ?? 0} 行流水`)
    resetFilters()
  } catch (error: any) {
    ElMessage.error(`财务导入失败：${error.message}`)
  } finally {
    importing.value = false
  }
}

function isEditing(row: any) {
  return editingId.value === row.id
}

function editableSnapshot(row: any) {
  return {
    businessDate: row.businessDate || null,
    summary: row.summary || null,
    counterparty: row.counterparty || null,
    department: row.department || null,
    category: row.category || null,
    expenseName: row.expenseName || null,
    debit: amountValue(row.debit),
    credit: amountValue(row.credit),
    balance: amountValue(row.balance),
    prevBalance: amountValue(row.prevBalance),
    income: amountValue(row.income),
    expense: amountValue(row.expense),
    currencyCode: row.currencyCode || 'CNY',
  } as Record<string, any>
}

function enterEdit(row: any) {
  if (editingId.value !== null) cancelCurrentEdit()
  originals.set(row.id, { base: editableSnapshot(row), fingerprint: computeRecordFingerprint(row) })
  editingId.value = row.id
  row._dirty = false
}

function markDirty(row: any) {
  if (editingId.value !== row.id) return
  const original = originals.get(row.id)
  if (!original) return
  const current = editableSnapshot(row)
  row._dirty = Object.keys(original.base).some((key) => original.base[key] !== current[key])
}

function cancelCurrentEdit() {
  if (editingId.value === null) return
  const row = records.value.find((item) => item.id === editingId.value)
  const original = originals.get(editingId.value)
  if (row && original) Object.assign(row, original.base)
  endEdit()
}

function endEdit() {
  if (editingId.value !== null) {
    const row = records.value.find((item) => item.id === editingId.value)
    if (row) {
      row._dirty = false
    }
  }
  originals.clear()
  editingId.value = null
}

function discardRowEdit() {
  originals.clear()
  editingId.value = null
}

function buildUpdatePayload(row: any) {
  const original = originals.get(row.id) || { base: {}, fingerprint: null }
  return {
    id: row.id,
    businessDate: row.businessDate || null,
    dayLabel: row.dayLabel || null,
    summary: row.summary || null,
    counterparty: row.counterparty || null,
    department: row.department || null,
    category: row.category || null,
    expenseName: row.expenseName || null,
    debit: amountValue(row.debit),
    credit: amountValue(row.credit),
    balance: amountValue(row.balance),
    prevBalance: amountValue(row.prevBalance),
    income: amountValue(row.income),
    expense: amountValue(row.expense),
    fixedPurchase: amountValue(row.fixedPurchase),
    fixedRedemption: amountValue(row.fixedRedemption),
    fixedBalance: amountValue(row.fixedBalance),
    currencyCode: row.currencyCode || 'CNY',
    warnings: row.warnings || null,
    expectedFingerprint: original.fingerprint || null,
  }
}

async function saveCurrentEdit() {
  if (editingId.value === null) return
  const row = records.value.find((item) => item.id === editingId.value)
  if (row) await saveEdit(row)
}

async function saveEdit(row: any) {
  const amountFields = ['debit', 'credit', 'balance', 'prevBalance', 'income', 'expense', 'fixedPurchase', 'fixedRedemption', 'fixedBalance']
  const invalid = amountFields.find((field) => {
    const value = row[field]
    return value !== '' && value !== null && value !== undefined && !Number.isFinite(Number(value))
  })
  if (invalid) {
    ElMessage.warning('金额字段只能填写数字')
    return
  }
  submitting.value = true
  try {
    const data = await updateFinanceRecords([buildUpdatePayload(row)])
    if (!Number(data.updatedCount)) throw new Error('未找到对应记录')
    endEdit()
    ElMessage.success('明细保存成功')
    await Promise.all([loadOverview(), loadAccounts(), loadDaily()])
  } catch (error: any) {
    row._dirty = true
    ElMessage.error(`保存失败：${error.message}`)
  } finally {
    submitting.value = false
  }
}

// versions
const versionVisible = ref(false)
const versionLoading = ref(false)
const versionActivating = ref(false)
const versionRows = ref<any[]>([])
const versionBook = ref('')
const versionAccount = ref<any>(null)

async function openVersionHistory(row: any) {
  versionAccount.value = row
  versionBook.value = row.bookKey || ''
  versionVisible.value = true
  versionLoading.value = true
  try {
    versionRows.value = (await getBatchVersions(versionBook.value)) || []
  } catch (error: any) {
    ElMessage.error(`版本历史加载失败：${error.message}`)
  } finally {
    versionLoading.value = false
  }
}

function viewVersion(row: any) {
  cancelCurrentEdit()
  filters.batchId = row.id
  filters.sheetName = versionAccount.value?.sheetName || ''
  filters.currencyCode = versionAccount.value?.currencyCode || ''
  pageNum.value = 1
  loadAll()
  versionVisible.value = false
}

async function activateVersion(row: any) {
  try {
    await ElMessageBox.confirm(`确认将 v${row.batchVersion || 1} 设为当前版本吗？其他版本将保留为历史。`, '切换版本', {
      confirmButtonText: '确认切换',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  versionActivating.value = true
  try {
    const data = await activateBatchVersion(row.id)
    if (!data.isCurrent) throw new Error('服务端未生效')
    ElMessage.success(`已切换到 v${data.batchVersion || 1}`)
    versionVisible.value = false
    resetFilters()
  } catch (error: any) {
    ElMessage.error(`切换失败：${error.message}`)
  } finally {
    versionActivating.value = false
  }
}

const currencyOptions = computed(() => [...new Set(accounts.value.map((item) => item.currencyCode).filter(Boolean))].sort())
const sheetOptions = computed(() => [...new Set(accounts.value.map((item) => item.sheetName).filter(Boolean))].sort())
const dailyRows = computed(() => daily.value.slice(0, 30))
const dateRangeText = computed(() => {
  if (!overview.value.dateMin && !overview.value.dateMax) return '-'
  return `${overview.value.dateMin || '?'} ~ ${overview.value.dateMax || '?'}`
})
const detailScopeText = computed(() => {
  const scope = []
  if (filters.regionName) scope.push(filters.regionName)
  if (filters.batchId) scope.push(`批次 ${filters.batchId}`)
  if (filters.sheetName) scope.push(filters.sheetName)
  if (filters.currencyCode) scope.push(filters.currencyCode)
  return scope.length ? `${scope.join(' / ')} 的流水` : '全部台账流水'
})
const dirtyId = computed(() => {
  if (editingId.value === null) return null
  const row = records.value.find((item) => item.id === editingId.value)
  return row?._dirty ? row.id : null
})
const currentVersionLabel = computed(() => {
  const current = versionRows.value.find((row: any) => row.isCurrent)
  return current?.batchVersion || null
})

function amountValue(value: any) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function formatAmount(value: any) {
  if (value === null || value === undefined || value === '') return '-'
  const number = Number(value)
  if (!Number.isFinite(number)) return String(value)
  return new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(number)
}

function formatTime(value: string) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

function goDashboard() {
  router.push('/app/excel')
}

onMounted(() => loadAll())
</script>

<style scoped>
.finance-page { --dl-accent-strong: #185c3c; --dl-accent: #2f7d57; min-height: 100%; background: transparent; }
.dl-shell { width: 100%; margin: 0; }
.finance-shell { padding: 28px 0 60px; }
.finance-header { display: grid; grid-template-columns: 1fr auto; gap: 24px; align-items: end; padding: 24px 0 20px; }
.eyebrow { margin: 0 0 8px; color: var(--el-color-primary); font-size: 13px; font-weight: 700; text-transform: uppercase; }
h1 { margin: 0; font-size: 34px; line-height: 1.15; }
.finance-summary { max-width: 720px; margin: 12px 0 0; color: var(--el-text-color-secondary); font-size: 15px; line-height: 1.7; }
.header-actions { display: flex; gap: 10px; align-items: center; }
.file-input { display: none; }
.filter-panel { display: grid; grid-template-columns: minmax(110px, 150px) minmax(100px, 130px) minmax(140px, 1fr) minmax(230px, 300px) minmax(180px, 1.2fr) auto auto; gap: 10px; align-items: center; padding: 14px; margin-bottom: 18px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); }
.filter-item { width: 100%; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 18px; }
.metric { min-height: 88px; padding: 14px 16px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); }
.metric span { display: block; color: var(--el-text-color-secondary); font-size: 13px; }
.metric strong { display: block; margin-top: 8px; font-size: 22px; line-height: 1.1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.metric .metric-text { font-size: 16px; line-height: 1.4; }
.metric-accent strong { color: var(--dl-accent-strong, #185c3c); }
.metric-warn strong { color: var(--el-color-danger); }
.finance-panel { padding: 20px; margin-bottom: 18px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.panel-head h2 { margin: 0; font-size: 18px; }
.panel-head p, .detail-actions { margin-top: 6px; color: var(--el-text-color-secondary); font-size: 13px; }
.detail-actions { display: flex; gap: 8px; align-items: center; }
.scope-empty { color: var(--el-text-color-secondary); font-size: 13px; }
.account-actions { display: flex; justify-content: center; gap: 8px; }
.version-meta { display: flex; align-items: center; gap: 10px; padding: 10px 12px; margin-bottom: 14px; border-radius: 6px; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); font-size: 13px; }
.version-meta strong { color: var(--el-text-color-primary); font-size: 14px; }
.book-cell { display: flex; align-items: center; gap: 6px; min-width: 0; }
.book-cell > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cell-stack { display: grid; gap: 4px; min-width: 0; }
.cell-text { display: block; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.amount-input :deep(.el-input__inner) { text-align: right; }
.pagination-wrap { display: flex; justify-content: flex-end; padding-top: 16px; }
@media (max-width: 1100px) {
  .filter-panel { grid-template-columns: 1fr 1fr; }
  .metrics-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .finance-header { grid-template-columns: 1fr; }
  .header-actions { flex-wrap: wrap; }
  .filter-panel { grid-template-columns: 1fr; }
  .metrics-grid { grid-template-columns: 1fr; }
  .panel-head { flex-direction: column; align-items: flex-start; }
}
</style>
