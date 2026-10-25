<template>
  <main class="dl-page export-tasks-page">
    <section class="dl-shell export-tasks-shell">
      <header class="admin-header">
        <div>
          <p class="eyebrow">Argus Excel Export</p>
          <h1>异步导出任务</h1>
          <p class="summary">大文档后台生成 xlsx，完成后可直接下载。</p>
        </div>
        <div class="admin-actions">
          <el-button :icon="ArrowLeft" @click="goBack">返回工作台</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="loadTasks">刷新</el-button>
        </div>
      </header>

      <el-card class="admin-panel" shadow="never">
        <div class="filter-row">
          <el-input
            v-model="keyword"
            placeholder="按文件名搜索"
            clearable
            :prefix-icon="Search"
            @keyup.enter="pageNum = 1; loadTasks()"
            @clear="pageNum = 1; loadTasks()"
          />
          <el-button type="primary" :icon="Search" @click="pageNum = 1; loadTasks()">查询</el-button>
        </div>

        <el-table v-loading="loading" :data="tasks" stripe empty-text="还没有导出任务">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="documentId" label="文档 ID" width="100" />
          <el-table-column prop="fileName" label="文件名" min-width="220" />
          <el-table-column prop="username" label="创建人" width="120" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="160">
            <template #default="{ row }">
              <el-progress :percentage="row.progress || 0" :status="progressStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 2"
                size="small"
                type="success"
                :icon="Download"
                @click="downloadTask(row)"
              >
                下载
              </el-button>
              <el-button
                v-if="row.status === 0 || row.status === 1"
                size="small"
                type="warning"
                :icon="Close"
                @click="cancelTask(row)"
              >
                取消
              </el-button>
              <el-button
                v-if="row.status === 3"
                size="small"
                type="danger"
                @click="retryTask(row)"
              >
                重试
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
            @current-change="loadTasks"
          />
        </div>
      </el-card>
    </section>
  </main>
</template>

<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any -- task payloads mirror backend JSON */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Close, Download, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelExportTask, downloadExportTask, listExportTasks, retryExportTask } from '@/api/excel'

const router = useRouter()
const tasks = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const pageNum = ref(1)
const pageSize = 20
const total = ref(0)

const POLL_INTERVAL_MS = 3000
let pollTimer: number | null = null

onMounted(() => loadTasks())
onBeforeUnmount(stopPolling)

function goBack() {
  router.push({ name: 'excel' })
}

async function loadTasks() {
  loading.value = true
  try {
    const res = await listExportTasks(pageNum.value, pageSize, keyword.value)
    const payload = res.data
    if (!payload?.success) throw new Error(payload?.message || '加载导出任务失败')
    tasks.value = payload.data?.records || []
    total.value = payload.data?.total || 0
    syncPolling()
  } catch (error: any) {
    ElMessage.error(error.message || '加载导出任务失败')
  } finally {
    loading.value = false
  }
}

function hasActiveTask() {
  return tasks.value.some((task) => task.status === 0 || task.status === 1)
}

function syncPolling() {
  if (hasActiveTask() && pollTimer === null) {
    pollTimer = window.setInterval(async () => {
      try {
        const res = await listExportTasks(pageNum.value, pageSize, keyword.value)
        const payload = res.data
        if (payload?.success) {
          tasks.value = payload.data?.records || []
          total.value = payload.data?.total || 0
        }
        if (!hasActiveTask()) stopPolling()
      } catch {
        // 轮询失败静默，下一次心跳再试
      }
    }, POLL_INTERVAL_MS)
  } else if (!hasActiveTask()) {
    stopPolling()
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function downloadTask(row: any) {
  try {
    const blobResponse = await downloadExportTask(row.id)
    const blob = blobResponse.data
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = row.fileName || `export-${row.id}.xlsx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('下载已开始')
  } catch (error: any) {
    ElMessage.error(error.message || '下载失败')
  }
}

async function cancelTask(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消导出任务 #${row.id} 吗？`, '取消导出', {
      confirmButtonText: '取消任务',
      cancelButtonText: '保留任务',
      type: 'warning',
    })
    const res = await cancelExportTask(row.id)
    if (res.data?.success === false) throw new Error(res.data.message || '取消任务失败')
    ElMessage.success('任务已取消')
    loadTasks()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '取消任务失败')
    }
  }
}

async function retryTask(row: any) {
  try {
    const res = await retryExportTask(row.id)
    if (res.data?.success === false) throw new Error(res.data.message || '重试失败')
    ElMessage.success('已重新发起导出')
    loadTasks()
  } catch (error: any) {
    ElMessage.error(error.message || '重试失败')
  }
}

function statusText(status: number) {
  if (status === 0) return '等待中'
  if (status === 1) return '导出中'
  if (status === 2) return '已完成'
  if (status === 3) return '失败'
  if (status === 4) return '已取消'
  if (status === 5) return '已过期'
  return '未知'
}

function statusTag(status: number) {
  if (status === 2) return 'success'
  if (status === 3 || status === 4 || status === 5) return 'danger'
  if (status === 1) return 'warning'
  return 'info'
}

function progressStatus(status: number) {
  if (status === 2) return 'success'
  if (status === 3 || status === 4 || status === 5) return 'exception'
  return 'active'
}

function formatTime(value: string) {
  if (!value) return '-'
  return String(value).replace('T', ' ').substring(0, 19)
}
</script>

<style scoped>
.export-tasks-shell {
  padding: 12px 0 48px;
}

.admin-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  padding: 8px 0 26px;
}

.eyebrow {
  margin: 0 0 8px;
  color: var(--dl-accent-strong, #185c3c);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.admin-header h1 {
  margin: 0;
  font-size: 40px;
  line-height: 1.1;
}

.summary {
  max-width: 640px;
  margin: 12px 0 0;
  color: var(--text-muted, #64748b);
  font-size: 14px;
  line-height: 1.7;
}

.admin-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.admin-panel {
  border: 1px solid var(--border-default, #e2e8f0);
}

.filter-row {
  display: flex;
  gap: 10px;
  max-width: 560px;
  margin-bottom: 16px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 18px;
}

@media (max-width: 760px) {
  .admin-header {
    align-items: start;
    flex-direction: column;
  }

  .admin-header h1 {
    font-size: 32px;
  }

  .filter-row {
    flex-direction: column;
  }
}
</style>
