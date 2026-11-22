<template>
  <main class="dl-page fund-page">
    <section class="dl-shell fund-shell">
      <header class="fund-header">
        <div>
          <p class="eyebrow">Argus Finance · Fund Daily</p>
          <h1>资金日报</h1>
          <p class="fund-summary">上传资金报表 Excel（每文件=一公司，每 Sheet=一账户），按基准日生成集团资金看板并推送。</p>
        </div>
        <div class="header-actions">
          <el-button :icon="UploadFilled" type="primary" :loading="uploading" @click="selectFile">上传报表</el-button>
          <input ref="fileInput" class="file-input" type="file" accept=".xlsx,.xls,.xlsm" multiple @change="handleFileChange" />
        </div>
      </header>

      <section class="fund-toolbar">
        <el-date-picker v-model="reportDate" type="date" placeholder="基准日" value-format="YYYY-MM-DD" :clearable="false" />
        <el-button type="primary" :icon="MagicStick" :loading="generating" @click="generate">生成日报</el-button>
        <el-button :icon="Refresh" :loading="loadingFiles" @click="loadFiles">刷新文件</el-button>
        <span class="push-hint">推送渠道：钉钉机器人（未配置凭据时仅落库不推送）</span>
      </section>

      <section class="fund-files">
        <div class="panel-head">
          <h2>报表文件（文件名解析公司名）</h2>
        </div>
        <el-table :data="files" size="small" stripe empty-text="尚未上传报表文件">
          <el-table-column prop="fileName" label="文件名" min-width="260" show-overflow-tooltip />
          <el-table-column label="大小" width="120">
            <template #default="{ row }">{{ formatSize(row.size) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeFile(row.fileName)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <template v-if="execution">
        <section class="fund-status">
          <el-tag :type="statusTagType" effect="light">{{ statusText }}</el-tag>
          <el-tag v-if="execution.pushStatus" :type="pushTagType" effect="light">推送：{{ execution.pushStatus }}</el-tag>
          <span v-if="execution.pushDetail" class="push-detail">{{ execution.pushDetail }}</span>
          <span v-if="execution.durationMs != null" class="push-detail">耗时 {{ execution.durationMs }} ms</span>
        </section>

        <section v-if="daily" class="metrics-grid">
          <div class="metric metric-accent"><span>上日余额</span><strong>{{ wan(daily.totalPrev) }}</strong></div>
          <div class="metric"><span>本日收入</span><strong>{{ wan(daily.totalIncome) }}</strong></div>
          <div class="metric"><span>本日支出</span><strong>{{ wan(daily.totalPayout) }}</strong></div>
          <div class="metric"><span>本日结余</span><strong>{{ wan(daily.totalBalance) }}</strong></div>
          <div class="metric" :class="{ 'metric-warn': !daily.balanceCheckOk }">
            <span>合计勾稽</span>
            <strong>{{ daily.balanceCheckOk ? '通过' : `差额 ${daily.balanceCheckDiff}` }}</strong>
          </div>
        </section>

        <section v-if="daily && daily.warnings?.length" class="fund-warnings">
          <div v-for="(w, i) in daily.warnings" :key="i" class="warn-line">{{ w }}</div>
        </section>

        <section v-if="daily" class="fund-table-panel">
          <div class="panel-head"><h2>公司分组明细</h2></div>
          <el-table :data="tableRows" size="small" stripe :row-class-name="rowClass">
            <el-table-column prop="name" label="公司 / 账户" min-width="220" show-overflow-tooltip />
            <el-table-column prop="currency" label="币种" width="72" />
            <el-table-column label="上日余额" width="110" align="right"><template #default="{ row }">{{ wan(row.prev) }}</template></el-table-column>
            <el-table-column label="本日收入" width="110" align="right"><template #default="{ row }">{{ wan(row.income) }}</template></el-table-column>
            <el-table-column label="本日支出" width="110" align="right"><template #default="{ row }">{{ wan(row.payout) }}</template></el-table-column>
            <el-table-column label="转账剔除" width="110" align="right"><template #default="{ row }">{{ wan(row.transfer) }}</template></el-table-column>
            <el-table-column label="理财剔除" width="110" align="right"><template #default="{ row }">{{ wan(row.finance) }}</template></el-table-column>
            <el-table-column label="本日结余" width="110" align="right"><template #default="{ row }">{{ wan(row.balance) }}</template></el-table-column>
          </el-table>
        </section>
      </template>
      <el-empty v-else description="选择基准日，点击「生成日报」" />
    </section>
  </main>
</template>

<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any -- 资金日报文件/执行记录镜像任意工作簿 JSON，沿用 api/finance.ts 的动态类型约定 */
/**
 * 资金日报页：上传 → 生成 → 看板。数据即后端 report_execution.result_json，
 * 与钉钉推送严格同源；合计勾稽未通过时推送被后端拒绝（fail-closed）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, UploadFilled, MagicStick } from '@element-plus/icons-vue'
import {
  deleteFundReportFile,
  generateFundDaily,
  getFundDaily,
  listFundDailyExecutions,
  listFundReportFiles,
  uploadFundReportFile,
  type FundDailyExecution,
} from '@/api/finance'

const fileInput = ref<HTMLInputElement>()
const reportDate = ref(new Date().toISOString().slice(0, 10))
const files = ref<Record<string, any>[]>([])
const execution = ref<FundDailyExecution | null>(null)
const uploading = ref(false)
const generating = ref(false)
const loadingFiles = ref(false)

const daily = computed(() => {
  if (!execution.value?.result) return null
  try {
    return JSON.parse(execution.value.result)
  } catch {
    return null
  }
})

interface TableRow {
  name: string
  currency: string
  prev: number
  income: number
  payout: number
  transfer: number
  finance: number
  balance: number
  subtotal: boolean
}

const tableRows = computed<TableRow[]>(() => {
  const rows: TableRow[] = []
  for (const group of daily.value?.groups ?? []) {
    for (const account of group.accounts ?? []) {
      rows.push({
        name: `${account.company} / ${account.name}`,
        currency: account.currency,
        prev: account.prev, income: account.income, payout: account.payout,
        transfer: (account.transferIncome ?? 0) + (account.transferPayout ?? 0),
        finance: (account.financeIncome ?? 0) + (account.financePayout ?? 0),
        balance: account.balance, subtotal: false,
      })
    }
    rows.push({
      name: `${group.company} 资金合计`,
      currency: 'RMB',
      prev: group.prev, income: group.income, payout: group.payout,
      transfer: (group.transferIncome ?? 0) + (group.transferPayout ?? 0),
      finance: (group.financeIncome ?? 0) + (group.financePayout ?? 0),
      balance: group.balance, subtotal: true,
    })
  }
  return rows
})

const statusText = computed(() => {
  const s = execution.value?.status
  if (s === 'SUCCEEDED') return `生成成功 · ${execution.value?.bizDate}`
  if (s === 'FAILED') return '生成失败'
  return '生成中'
})

const statusTagType = computed(() => {
  const s = execution.value?.status
  if (s === 'SUCCEEDED') return 'success'
  if (s === 'FAILED') return 'danger'
  return 'info'
})

const pushTagType = computed(() => {
  const s = execution.value?.pushStatus
  if (s === 'SENT') return 'success'
  if (s === 'FAILED') return 'danger'
  return 'info'
})

function rowClass({ row }: { row: TableRow }) {
  return row.subtotal ? 'fund-subtotal-row' : ''
}

function wan(value: number | null | undefined) {
  if (value == null) return '—'
  return (Number(value) / 10000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatSize(size: number) {
  if (size == null) return '—'
  return `${(size / 1024).toFixed(1)} KB`
}

function selectFile() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  // 支持批量：顺序逐个上传（同名文件按"维护"语义覆盖归档），最后统一汇总
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  uploading.value = true
  const failed: string[] = []
  try {
    for (const file of files) {
      try {
        await uploadFundReportFile(file)
      } catch (e) {
        failed.push(`${file.name}：${(e as Error).message || '上传失败'}`)
      }
    }
    if (failed.length === 0) {
      ElMessage.success(files.length > 1 ? `已上传 ${files.length} 个文件` : '上传成功')
    } else {
      ElMessage({
        type: 'warning',
        message: `${files.length - failed.length}/${files.length} 个上传成功；失败：${failed.join('；')}`,
        duration: 6000,
      })
    }
    await loadFiles()
  } finally {
    uploading.value = false
  }
}

async function removeFile(fileName: string) {
  try {
    await ElMessageBox.confirm(`删除后该文件将退出日报合并范围：${fileName}`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteFundReportFile(fileName)
  ElMessage.success('已删除')
  await loadFiles()
}

async function generate() {
  generating.value = true
  try {
    execution.value = await generateFundDaily(reportDate.value)
    if (execution.value.status === 'SUCCEEDED') {
      ElMessage.success('日报生成完成')
    } else {
      ElMessage.error(execution.value.errorMessage || '日报生成失败')
    }
  } catch (e) {
    ElMessage.error((e as Error).message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function loadFiles() {
  loadingFiles.value = true
  try {
    files.value = (await listFundReportFiles()) ?? []
  } finally {
    loadingFiles.value = false
  }
}

onMounted(async () => {
  await loadFiles()
  const existing = await getFundDaily(reportDate.value).catch(() => null)
  if (existing?.id) {
    execution.value = existing
  }
  const recent = await listFundDailyExecutions(reportDate.value, reportDate.value).catch(() => [])
  if (!execution.value && recent?.length) {
    execution.value = recent[0] ?? null
  }
})
</script>

<style scoped>
.fund-page { padding: 24px; }
.fund-shell { max-width: 1180px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
.fund-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.eyebrow { font-size: 12px; color: #6b7280; letter-spacing: 0.08em; margin: 0 0 4px; }
.fund-header h1 { margin: 0; font-size: 26px; }
.fund-summary { color: #6b7280; font-size: 14px; margin: 8px 0 0; max-width: 560px; }
.file-input { display: none; }
.fund-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.push-hint { color: #9ca3af; font-size: 12px; }
.panel-head h2 { margin: 0; font-size: 16px; }
.fund-status { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.push-detail { color: #9ca3af; font-size: 12px; }
.metrics-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; }
.metric { border: 1px solid #e5e7eb; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; }
.metric span { color: #6b7280; font-size: 13px; }
.metric strong { font-size: 20px; }
.metric-accent { border-color: #2563eb; background: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-white)); }
.metric-warn { border-color: #d97706; background: color-mix(in srgb, #f59e0b 9%, var(--surface-white)); }
.fund-warnings { border: 1px dashed #d97706; border-radius: 8px; padding: 10px 14px; }
.warn-line { color: #b45309; font-size: 13px; line-height: 1.8; }
:deep(.fund-subtotal-row) { background: #f3f4f6; font-weight: 600; }
</style>
