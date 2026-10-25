<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  getMyAttendance,
  getMyAttendanceSummary,
  getMyPayroll,
} from '@/api/finance'
import { extractApiError } from '@/api/http'
import PageHeaderHero from '@/components/layout/PageHeaderHero.vue'

const month = ref(new Date().toISOString().slice(0, 7))
const payroll = ref<Record<string, any> | null>(null)
const attendanceRows = ref<any[]>([])
const attendanceSummary = ref<Record<string, any>>({})
const loading = ref(false)
const errorMsg = ref('')

async function load() {
  loading.value = true
  errorMsg.value = ''
  const [year, mon] = month.value.split('-').map(Number)
  try {
    payroll.value = await getMyPayroll(month.value)
    const attendance = await getMyAttendance(year, mon)
    attendanceRows.value = attendance?.rows ?? []
    const summary = await getMyAttendanceSummary(month.value)
    attendanceSummary.value = summary?.attendanceSummary ?? {}
  } catch (err: any) {
    errorMsg.value = extractApiError(err, '加载薪酬失败')
  } finally {
    loading.value = false
  }
}

function money(value: any): string {
  if (value === null || value === undefined) return '0.00'
  const num = Number(value)
  if (!Number.isFinite(num)) return String(value)
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function statusLabel(status: string): string {
  if (status === 'PAID') return '已发放'
  if (status === 'VOID') return '已作废'
  return '草稿/待核对'
}

onMounted(load)
</script>

<template>
  <main class="my-payroll-page">
    <PageHeaderHero
      eyebrow="员工自助"
      title="我的薪酬与考勤"
      description="查看本人月度考勤、薪酬构成与实发工资。财务相关数据由财务/领导统一维护。"
    >
      <template #actions>
        <label class="month-picker">
          结算月份
          <input v-model="month" type="month" @change="load" />
        </label>
      </template>
    </PageHeaderHero>

    <div v-if="errorMsg" class="alert">{{ errorMsg }}</div>
    <div v-if="loading" class="loading">正在加载...</div>

    <template v-else>
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>本月薪酬</h2>
            <p class="muted">{{ month }} 结算周期 · {{ statusLabel(payroll?.payStatus ?? '') }}</p>
          </div>
          <strong v-if="payroll" class="total">实发 ¥{{ money(payroll.netSalary) }}</strong>
        </div>

        <div v-if="!payroll" class="empty">该月薪酬尚未发布</div>
        <div v-else class="grid">
          <div class="cell">
            <span>出勤天数</span>
            <b>{{ payroll.workDays ?? 0 }}</b>
          </div>
          <div class="cell">
            <span>基本工资</span>
            <b>{{ money(payroll.baseSalary) }}</b>
          </div>
          <div class="cell">
            <span>绩效奖金</span>
            <b>{{ money(payroll.performanceBonus) }}</b>
          </div>
          <div class="cell">
            <span>考勤/加班</span>
            <b>{{ money((payroll.attendanceBonus ?? 0) + (payroll.overtimePay ?? 0)) }}</b>
          </div>
          <div class="cell">
            <span>补贴合计</span>
            <b>{{ money((payroll.allowance ?? 0) + (payroll.subsidy ?? 0)) }}</b>
          </div>
          <div class="cell">
            <span>社保公积金</span>
            <b>{{ money((payroll.socialSecurity ?? 0) + (payroll.housingFund ?? 0)) }}</b>
          </div>
          <div class="cell">
            <span>个税</span>
            <b>{{ money(payroll.personalTax) }}</b>
          </div>
          <div class="cell">
            <span>其他扣款</span>
            <b>{{ money((payroll.deduction ?? 0) + (payroll.otherDeduction ?? 0)) }}</b>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>本月考勤</h2>
            <p class="muted">
              出勤 {{ attendanceSummary.days ?? 0 }} 天 · 迟到 {{
                attendanceSummary.lateMinutes ?? 0
              }} 分钟 · 早退 {{ attendanceSummary.earlyMinutes ?? 0 }} 分钟 · 加班 {{
                attendanceSummary.overtimeMinutes ?? 0
              }} 分钟
            </p>
          </div>
        </div>

        <div v-if="attendanceRows.length === 0" class="empty">本月暂无考勤记录</div>
        <div v-else class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>日期</th>
                <th>状态</th>
                <th>迟到</th>
                <th>早退</th>
                <th>加班</th>
                <th>请假类型</th>
                <th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in attendanceRows" :key="row.id">
                <td>{{ String(row.workDate).slice(0, 10) }}</td>
                <td>{{ row.status }}</td>
                <td>{{ row.lateMinutes ?? 0 }}</td>
                <td>{{ row.earlyMinutes ?? 0 }}</td>
                <td>{{ row.overtimeMinutes ?? 0 }}</td>
                <td>{{ row.leaveType || '—' }}</td>
                <td>{{ row.remark || '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.my-payroll-page {
  max-width: 1080px;
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
  gap: 14px;
  margin-bottom: 14px;
}
.panel-head h2 {
  margin: 0;
  font-size: 16px;
}
.muted {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.total {
  font-size: 20px;
  color: var(--el-color-success);
}
.grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.cell {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 14px;
}
.cell span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 6px;
}
.cell b {
  font-size: 16px;
}
.table-wrap {
  overflow-x: auto;
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 720px;
}
.data-table th,
.data-table td {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 10px 8px;
  text-align: left;
  font-size: 13px;
}
.data-table th {
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.empty,
.loading {
  padding: 26px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.error {
  background: rgba(220, 38, 38, 0.08);
  border: 1px solid rgba(220, 38, 38, 0.25);
  color: #b91c1c;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
  font-size: 13px;
}
@media (max-width: 800px) {
  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
