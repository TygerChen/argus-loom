<template>
  <main class="dl-page recycle-page">
    <section class="dl-shell recycle-shell">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">DataLoom Recycle Bin</p>
          <h1>回收站</h1>
          <p class="summary">文档保留 90 天，恢复后数据完整还原；彻底删除不会影响已归档的财务数据。</p>
        </div>

        <div class="header-actions">
          <template v-if="profile">
            <el-button :icon="DeleteFilled" :loading="batchPurging" :disabled="selectedRows.length === 0" @click="purgeSelected">
              批量彻底删除 {{ selectedRows.length }}
            </el-button>
            <el-button :icon="RefreshLeft" :disabled="selectedRows.length === 0" @click="restoreSelected">
              批量恢复 {{ selectedRows.length }}
            </el-button>
            <el-button :icon="Delete" :disabled="total === 0" @click="clearRecycle">清空回收站</el-button>
            <el-button :icon="Refresh" :loading="loading" @click="fetchRecycle">刷新</el-button>
            <el-button type="primary" :icon="FolderOpened" @click="goDashboard">返回工作台</el-button>
          </template>
        </div>
      </header>

      <section class="metrics-row">
        <div class="metric">
          <span>回收站文档</span>
          <strong>{{ total }}</strong>
        </div>
        <div class="metric">
          <span>本页已过期</span>
          <strong>{{ expiredCount }}</strong>
        </div>
        <div class="metric metric-accent">
          <span>保留周期</span>
          <strong>90 天</strong>
        </div>
      </section>

      <section class="filter-row">
        <el-input
          v-model="keyword"
          class="keyword-input"
          placeholder="按文档名称搜索"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      </section>

      <el-card class="recycle-panel" shadow="never">
        <template #header>
          <div class="panel-header">
            <div>
              <h2>回收文档</h2>
              <p>恢复后重新出现在工作台，超出 90 天会自动永久删除。</p>
            </div>
            <el-tag v-if="anyFinanceRelated" type="warning" effect="light">部分文档关联财务数据</el-tag>
          </div>
        </template>

        <el-table
          v-loading="loading"
          :data="records"
          row-key="id"
          stripe
          class="recycle-table"
          empty-text="回收站是空的。"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="文档名称" min-width="260">
            <template #default="{ row }">
              <span class="doc-name">{{ row.name }}</span>
              <el-tag v-if="row.financeRelated" size="small" type="warning" effect="plain" class="finance-tag">
                财务
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="110">
            <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="删除人" width="120">
            <template #default="{ row }">{{ row.deletedBy || '-' }}</template>
          </el-table-column>
          <el-table-column label="删除时间" width="180">
            <template #default="{ row }">{{ formatTime(row.deletedAt) }}</template>
          </el-table-column>
          <el-table-column label="到期时间" width="180">
            <template #default="{ row }">{{ formatTime(row.expireAt) }}</template>
          </el-table-column>
          <el-table-column label="剩余天数" width="110">
            <template #default="{ row }">
              <el-tag :type="remainDays(row) <= 0 ? 'danger' : 'info'" size="small">
                {{ remainDays(row) <= 0 ? '已过期' : `${remainDays(row)} 天` }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :icon="RefreshLeft" @click="restoreSingle(row)">
                恢复
              </el-button>
              <el-button size="small" type="danger" plain :icon="Delete" @click="purgeSingle(row)">
                彻底删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            background
            @current-change="fetchRecycle"
          />
        </div>
      </el-card>
    </section>

    <el-dialog
      v-model="purgeVisible"
      title="永久删除确认"
      width="min(560px, calc(100% - 32px))"
      :close-on-click-modal="false"
    >
      <template v-if="purgeRows.length > 0">
        <p class="purge-tip">
          删除后不可恢复，但关联财务数据仍会保留。请逐行输入这 {{ purgeRows.length }} 个文档的完整名称确认：
        </p>
        <ul class="purge-name-list">
          <li v-for="row in purgeRows" :key="row.id">{{ row.name }}</li>
        </ul>
        <el-input
          v-model="purgeInput"
          type="textarea"
          :rows="Math.min(Math.max(purgeRows.length, 2), 6)"
          placeholder="每行一个完整文档名称"
          class="purge-input"
        />
      </template>

      <template v-if="clearVisible">
        <p class="purge-tip">
          将永久删除当前回收站中的全部文档（不受列表筛选影响）。请输入 <code>DELETE</code> 确认清空：
        </p>
        <el-input v-model="clearInput" placeholder="输入 DELETE" class="purge-input" />
      </template>

      <template #footer>
        <el-button @click="closePurgeDialog">取消</el-button>
        <el-button
          v-if="!clearVisible"
          type="danger"
          :loading="batchPurging"
          :disabled="nameInputValid.length === 0"
          @click="executePurge"
        >
          确认永久删除
        </el-button>
        <el-button
          v-else
          type="danger"
          :loading="batchPurging"
          :disabled="clearInput.trim() !== 'DELETE'"
          @click="executeClear"
        >
          确认清空
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
/**
 * 回收站页面 —— 06 篇"后悔药"的第三件套。
 * 列表（带删除人/过期时间）、批量恢复、批量彻底删除、清空四组操作，
 * 与后端 RecycleBinController 一一对应。破坏性操作（彻底删除/清空）
 * 前用 ElMessageBox 二次确认——前端最后一道防手滑，后端权限校验才是防线。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, DeleteFilled, FolderOpened, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { extractApiError } from '@/api/http'
import {
  clearRecycleBin,
  getRecycleList,
  purgeDocuments,
  restoreDocuments
} from '@/api/excel'
import { getUserProfile } from '@/utils/auth'
import { formatSize, formatTime } from '@/utils/format'

interface RecycleRow {
  id: number
  name: string
  fileSize?: number
  financeRelated?: boolean
  deletedBy?: string
  deletedAt?: string
  expireAt?: string
}

interface RecycleListData {
  records?: RecycleRow[]
  total?: number
}

const router = useRouter()
const profile = ref(getUserProfile())
const records = ref<RecycleRow[]>([])
const loading = ref(false)
const batchPurging = ref(false)
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const keyword = ref('')
const selectedRows = ref<RecycleRow[]>([])
const purgeVisible = ref(false)
const purgeRows = ref<RecycleRow[]>([])
const purgeInput = ref('')
const clearVisible = ref(false)
const clearInput = ref('')

const expiredCount = computed(() => records.value.filter((row) => remainDays(row) <= 0).length)
const anyFinanceRelated = computed(() => records.value.some((row) => row.financeRelated === true))
const nameInputValid = computed(() => {
  const required = purgeRows.value.map((row) => row.name).sort()
  const actual = purgeInput.value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .sort()
  return required.length === actual.length && required.every((name, index) => name === actual[index])
    ? Array.from(new Set(required))
    : []
})

onMounted(async () => {
  await fetchRecycle()
})

function goDashboard() {
  router.push({ name: 'excel' })
}

async function fetchRecycle() {
  loading.value = true
  try {
    const response = await getRecycleList(pageNum.value, pageSize, keyword.value.trim())
    const data = (response.data?.data ?? {}) as RecycleListData
    records.value = data.records ?? []
    total.value = data.total ?? 0
  } catch (error) {
    ElMessage.error(`加载回收站失败：${extractApiError(error, '加载失败')}`)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNum.value = 1
  fetchRecycle()
}

function handleSelectionChange(rows: RecycleRow[]) {
  selectedRows.value = rows || []
}

function remainDays(row: RecycleRow) {
  if (!row.expireAt) return 0
  const expire = new Date(String(row.expireAt).replace(' ', 'T')).getTime()
  return Math.max(0, Math.ceil((expire - Date.now()) / 86400000))
}

async function restoreSelected() {
  const ids = selectedRows.value.map((row) => row.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(`确定恢复选中的 ${ids.length} 个文档吗？`, '恢复文档', {
      confirmButtonText: '恢复',
      cancelButtonText: '取消',
      type: 'success'
    })
    const response = await restoreDocuments(ids)
    const data = response.data?.data || {}
    let message = `已恢复 ${data.successCount ?? ids.length} 个文档`
    if (data.rejectedCount) message += `，${data.rejectedCount} 个未能恢复`
    ElMessage.success(message)
    selectedRows.value = []
    await afterChange(data.successCount ?? ids.length)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`恢复失败：${extractApiError(error, '恢复失败')}`)
    }
  }
}

async function restoreSingle(row: RecycleRow) {
  try {
    await ElMessageBox.confirm(`确定恢复文档“${row.name}”吗？`, '恢复文档', {
      confirmButtonText: '恢复',
      cancelButtonText: '取消',
      type: 'success'
    })
    await restoreDocuments([row.id])
    ElMessage.success('恢复成功')
    await afterChange(1)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`恢复失败：${extractApiError(error, '恢复失败')}`)
    }
  }
}

function purgeSingle(row: RecycleRow) {
  openPurgeDialog([row])
}

function purgeSelected() {
  if (selectedRows.value.length === 0) return
  openPurgeDialog(selectedRows.value.slice())
}

function openPurgeDialog(rows: RecycleRow[]) {
  clearVisible.value = false
  purgeRows.value = rows
  purgeInput.value = ''
  purgeVisible.value = true
}

function closePurgeDialog() {
  purgeVisible.value = false
  purgeRows.value = []
  purgeInput.value = ''
  clearInput.value = ''
}

async function executePurge() {
  if (nameInputValid.value.length === 0) return
  batchPurging.value = true
  try {
    const ids = purgeRows.value.map((row) => row.id)
    const response = await purgeDocuments(ids)
    const data = response.data?.data || {}
    const retained = data.financeRetainedCount || 0
    if (retained > 0) {
      ElMessage.success(`已永久删除，${retained} 个文档的财务数据已保留`)
    } else {
      ElMessage.success('已永久删除')
    }
    closePurgeDialog()
    await afterChange(data.successCount ?? ids.length)
  } catch (error) {
    ElMessage.error(`永久删除失败：${extractApiError(error, '永久删除失败')}`)
  } finally {
    batchPurging.value = false
  }
}

function clearRecycle() {
  clearVisible.value = true
  purgeVisible.value = true
  purgeRows.value = []
  clearInput.value = ''
}

async function executeClear() {
  if (clearInput.value.trim() !== 'DELETE') return
  batchPurging.value = true
  try {
    const response = await clearRecycleBin()
    const data = response.data?.data || {}
    ElMessage.success(`已清空 ${data.successCount ?? 0} 个文档，财务数据保留`)
    closePurgeDialog()
    pageNum.value = 1
    await fetchRecycle()
  } catch (error) {
    ElMessage.error(`清空回收站失败：${extractApiError(error, '清空失败')}`)
  } finally {
    batchPurging.value = false
  }
}

async function afterChange(changedCount: number) {
  const remains = records.value.length - changedCount
  if (remains <= 0 && pageNum.value > 1) {
    pageNum.value -= 1
  }
  await fetchRecycle()
}


</script>

<style scoped>
.recycle-shell {
  padding: 36px 0 56px;
}

.workspace-header {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 24px;
  align-items: end;
  padding: 30px 0 26px;
}

.eyebrow {
  margin: 0 0 8px;
  color: var(--dl-accent-strong);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: clamp(30px, 4vw, 48px);
  line-height: 1.08;
  letter-spacing: 0;
}

.summary {
  max-width: 700px;
  margin: 14px 0 0;
  color: var(--dl-muted);
  font-size: 15px;
  line-height: 1.7;
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 22px;
}

.metric {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: var(--dl-panel);
  box-shadow: var(--dl-shadow);
}

.metric span {
  color: var(--dl-muted);
  font-size: 13px;
}

.metric strong {
  color: var(--dl-ink);
  font-size: 26px;
  line-height: 1;
}

.metric-accent {
  border-color: rgba(47, 125, 87, 0.24);
  background: linear-gradient(180deg, #f5faf6, var(--dl-panel));
}

.metric-accent strong {
  color: var(--dl-accent-strong);
}

.filter-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.filter-row .keyword-input {
  max-width: 360px;
}

.recycle-panel .panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.panel-header h2 {
  margin: 0;
  font-size: 18px;
  letter-spacing: 0;
}

.panel-header p {
  margin: 6px 0 0;
  color: var(--dl-muted);
  font-size: 13px;
}

.doc-name {
  font-weight: 600;
  color: var(--dl-ink);
}

.finance-tag {
  margin-left: 8px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 18px;
}

.purge-tip {
  margin: 0 0 12px;
  color: var(--dl-muted);
  line-height: 1.7;
}

.purge-name-list {
  max-height: 150px;
  margin: 0 0 14px;
  padding: 12px 16px;
  overflow: auto;
  border: 1px solid var(--dl-line);
  border-radius: 6px;
  background: #fafbf9;
  color: var(--dl-ink);
  list-style: none;
  font-size: 13px;
  line-height: 1.9;
}

.purge-input {
  margin-top: 2px;
}
</style>
