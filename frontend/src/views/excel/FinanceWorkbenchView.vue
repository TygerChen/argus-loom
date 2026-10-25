<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getFinanceCompactSummary,
  getFinanceReports,
  getPayrollPage,
  generateFinanceReport,
  getReportPdf,
  savePayrollRows,
} from '@/api/finance'
import { extractApiError } from '@/api/http'
import PageHeaderHero from '@/components/layout/PageHeaderHero.vue'

const currentMonth = ref(new Date().toISOString().slice(0, 7))
const payrollRows = ref<Record<string, any>[]>([])
const payrollLoading = ref(false)
const saving = ref(false)
const errorMsg = ref('')
const summary = ref<Record<string, any>>({})
const reports = ref<any[]>([])
const reportGenerating = ref(false)
const reportPreview = ref<Record<string, any> | null>(null)
const reportError = ref('')

async function loadSummary() {
  try {
    summary.value = await getFinanceCompactSummary()
  } catch (err) {
    errorMsg.value = extractApiError(err, '加载财务汇总失败')
  }
}

async function loadPayroll() {
  payrollLoading.value = true
  errorMsg.value = ''
  try {
    const page = await getPayrollPage(currentMonth.value, 1, 200)
    payrollRows.value = page?.records ?? []
  } catch (err) {
    errorMsg.value = extractApiError(err, '加载薪酬台账失败')
  } finally {
    payrollLoading.value = false
  }
}

async function loadReports() {
  try {
    const page = await getFinanceReports(1, 20)
    reports.value = page?.records ?? []
  } catch {
    reports.value = []
  }
}

async function saveRow(row: Record<string, any>) {
  try {
    const userId = Number(row.userId)
    if (!Number.isInteger(userId) || userId <= 0) {
      ElMessage.warning('请先填写员工 ID')
      return
    }
    await savePayrollRows([buildPayload(row, userId)])
    ElMessage.success('薪酬已保存并通过实发工资自动重算')
    await loadPayroll()
  } catch (err) {
    ElMessage.error(extractApiError(err, '保存失败'))
  }
}

function addRow() {
  const nextId = Math.max(0, ...payrollRows.value.map((r) => Number(r.userId) || 0)) + 1
  payrollRows.value.unshift({
    userId: nextId,
    payStatus: 'DRAFT',
    workDays: 21,
    baseSalary: 0,
    performanceBonus: 0,
    allowance: 0,
    socialSecurity: 0,
    deduction: 0,
  })
}

async function saveAll() {
  saving.value = true
  try {
    const payload = payrollRows.value
      .filter((row) => Number(row.userId) > 0)
      .map((row) => buildPayload(row, Number(row.userId)))
    if (payload.length === 0) {
      ElMessage.warning('请先新增一条薪酬数据')
      return
    }
    await savePayrollRows(payload)
    ElMessage.success('薪酬台账已保存并留痕')
    await loadPayroll()
  } catch (err) {
    ElMessage.error(extractApiError(err, '批量保存失败'))
  } finally {
    saving.value = false
  }
}

function buildPayload(row: Record<string, any>, userId: number): Record<string, any> {
  return {
    userId,
    payrollMonth: currentMonth.value,
    workDays: Number(row.workDays ?? 0),
    baseSalary: Number(row.baseSalary ?? 0),
    performanceBonus: Number(row.performanceBonus ?? 0),
    attendanceBonus: Number(row.attendanceBonus ?? 0),
    overtimePay: Number(row.overtimePay ?? 0),
    allowance: Number(row.allowance ?? 0),
    subsidy: Number(row.subsidy ?? 0),
    otherBonus: Number(row.otherBonus ?? 0),
    socialSecurity: Number(row.socialSecurity ?? 0),
    housingFund: Number(row.housingFund ?? 0),
    personalTax: Number(row.personalTax ?? 0),
    deduction: Number(row.deduction ?? 0),
    otherDeduction: Number(row.otherDeduction ?? 0),
    payStatus: row.payStatus ?? 'DRAFT',
    remark: row.remark ?? '',
  }
}

async function generateReport() {
  reportGenerating.value = true
  reportPreview.value = null
  reportError.value = ''
  try {
    reportPreview.value = await generateFinanceReport(currentMonth.value)
    ElMessage.success('AI 财务简报已生成')
    await loadReports()
  } catch (err) {
    reportError.value = extractApiError(err, '生成简报失败')
  } finally {
    reportGenerating.value = false
  }
}

async function exportPdf(reportId: number) {
  try {
    const res = await getReportPdf(reportId)
    const blob = new Blob([res.data], { type: 'application/pdf' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `finance-report-${reportId}.pdf`
    a.click()
    URL.revokeObjectURL(url)
  } catch (err) {
    ElMessage.error(extractApiError(err, 'PDF 导出失败'))
  }
}

function money(value: any): string {
  if (value === null || value === undefined) return '0.00'
  const num = Number(value)
  if (!Number.isFinite(num)) return String(value)
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  void loadPayroll()
  void loadSummary()
  void loadReports()
})
</script>

<template>
  <main class="finance-workbench-page">
    <PageHeaderHero
      eyebrow="财务/领导工作台"
      title="考勤薪酬与 AI 简报"
      description="维护员工考勤与月度薪酬正式台账，核算实发工资并生成可留痕、可导出 PDF 的财务简报。"
    >
      <template #actions>
        <label class="month-picker">
          结算月份
          <input v-model="currentMonth" type="month" @change="loadPayroll" />
        </label>
      </template>
    </PageHeaderHero>

    <div v-if="errorMsg" class="alert alert--error">{{ errorMsg }}</div>

    <section class="summary-grid">
      <div class="metric-card">
        <span class="metric-label">本月应发人员</span>
        <strong class="metric-value">{{ summary?.payroll?.headcount ?? 0 }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">本月实发合计</span>
        <strong class="metric-value">{{ money(summary?.payroll?.netSalary) }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">总收入</span>
        <strong class="metric-value">{{ money(summary?.ledger?.income) }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">总支出</span>
        <strong class="metric-value">{{ money(summary?.ledger?.expense) }}</strong>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>月度薪酬台账</h2>
          <p>员工仅可查看本人；财务/领导统一维护，保存后自动重算并写入审计留痕。</p>
        </div>
        <div class="panel-actions">
          <button class="btn btn--secondary" @click="addRow">新增人员</button>
          <button class="btn btn--primary" :disabled="saving" @click="saveAll">
            {{ saving ? '保存中...' : '保存全部' }}
          </button>
        </div>
      </div>

      <div v-if="payrollLoading" class="empty">正在加载薪酬台账...</div>
      <div v-else-if="payrollRows.length === 0" class="empty">
        本月暂无薪酬数据，点击「新增人员」建立第一行台账。
      </div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>员工 ID</th>
              <th>出勤天数</th>
              <th>基本工资</th>
              <th>绩效</th>
              <th>奖金/补贴</th>
              <th>社保公积金</th>
              <th>扣款</th>
              <th>实发工资</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in payrollRows" :key="row.userId">
              <td><input v-model="row.userId" class="input input--narrow" type="number" /></td>
              <td><input v-model="row.workDays" class="input input--narrow" type="number" /></td>
              <td><input v-model="row.baseSalary" class="input" type="number" step="0.01" /></td>
              <td><input v-model="row.performanceBonus" class="input" type="number" step="0.01" /></td>
              <td><input v-model="row.allowance" class="input" type="number" step="0.01" /></td>
              <td><input v-model="row.socialSecurity" class="input" type="number" step="0.01" /></td>
              <td><input v-model="row.deduction" class="input" type="number" step="0.01" /></td>
              <td class="amount">{{ money(row.netSalary) }}</td>
              <td>
                <select v-model="row.payStatus" class="input select">
                  <option value="DRAFT">草稿</option>
                  <option value="PAID">已发放</option>
                  <option value="VOID">作废</option>
                </select>
              </td>
              <td><button class="link-btn" @click="saveRow(row)">保存</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>AI 财务简报</h2>
          <p>自动读取财务流水与薪酬台账，生成经营概况、薪酬分析、风险提示和管理建议，全部留痕。</p>
        </div>
        <div class="panel-actions">
          <button class="btn btn--primary" :disabled="reportGenerating" @click="generateReport">
            {{ reportGenerating ? '生成中...' : '生成简报' }}
          </button>
        </div>
      </div>

      <div v-if="reportError" class="alert alert--error">{{ reportError }}</div>

      <div v-if="reportPreview" class="report-preview">
        <h3>{{ reportPreview.title }}</h3>
        <pre>{{ reportPreview.contentMarkdown }}</pre>
      </div>

      <div v-if="reports.length > 0" class="report-list">
        <div v-for="report in reports" :key="report.id" class="report-row">
          <div>
            <strong>{{ report.title }}</strong>
            <span class="report-meta">{{ formatDateTime(report.createTime) }} · {{ report.status }}</span>
          </div>
          <button class="btn btn--secondary" @click="exportPdf(report.id)">导出 PDF</button>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.finance-workbench-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 26px 4vw 60px;
}
.month-picker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.month-picker input {
  padding: 7px 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: #fff;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin: 20px 0;
}
.metric-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 16px 18px;
}
.metric-label {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.metric-value {
  display: block;
  margin-top: 8px;
  font-size: 22px;
  color: var(--el-color-primary);
  font-weight: 700;
}
.panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 18px 20px;
  margin-bottom: 20px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.panel-head h2 {
  margin: 0 0 4px;
  font-size: 16px;
}
.panel-head p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.panel-actions {
  display: flex;
  gap: 8px;
}
.table-wrap {
  overflow-x: auto;
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 960px;
}
.data-table th,
.data-table td {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 10px 8px;
  text-align: left;
  font-size: 13px;
  white-space: nowrap;
}
.data-table th {
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.input {
  width: 110px;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
}
.input--narrow {
  width: 70px;
}
.select {
  width: 96px;
}
.amount {
  font-weight: 600;
  color: var(--el-color-success);
}
.link-btn {
  color: var(--el-color-primary);
  background: none;
  border: none;
  cursor: pointer;
  font-size: 13px;
}
.report-preview {
  background: #f7f8fa;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 14px;
}
.report-preview h3 {
  margin: 0 0 8px;
  font-size: 15px;
}
.report-preview pre {
  white-space: pre-wrap;
  font-family: inherit;
  line-height: 1.65;
  margin: 0;
  color: var(--el-text-color-primary);
}
.report-list {
  display: flex;
  flex-direction: column;
}
.report-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 12px 0;
}
.report-meta {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}
.empty {
  padding: 34px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.alert {
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
  font-size: 13px;
}
.alert--error {
  background: rgba(220, 38, 38, 0.08);
  border: 1px solid rgba(220, 38, 38, 0.25);
  color: #b91c1c;
}
@media (max-width: 900px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .panel-head {
    flex-direction: column;
  }
}
</style>
