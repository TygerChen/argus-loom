<template>
  <main class="dl-page operation-center-page">
    <section class="dl-shell operation-center-shell">
      <header class="admin-header">
        <div>
          <p class="eyebrow">Argus Excel Operations</p>
          <h1>运营中心</h1>
          <p class="summary">审计日志、协同日志、在线会话与版本容量策略。</p>
        </div>
        <div class="admin-actions">
          <el-button :icon="ArrowLeft" @click="goBack">返回工作台</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
        </div>
      </header>

      <div v-if="summary" class="ops-metrics">
        <div class="metric">
          <span>在线会话</span>
          <strong>{{ summary.onlineSessions || 0 }}</strong>
        </div>
        <div class="metric">
          <span>导出待处理</span>
          <strong>{{ summary.exportPending || 0 }}</strong>
        </div>
        <div class="metric">
          <span>协同日志</span>
          <strong>{{ summary.collabLogs || 0 }}</strong>
        </div>
        <div class="metric metric-accent">
          <span>存储模式</span>
          <strong>{{ summary.storageType || '-' }}</strong>
        </div>
      </div>

      <el-card class="admin-panel ops-panel" shadow="never">
        <el-tabs v-model="activeTab" @tab-change="loadCurrent">
          <el-tab-pane label="审计日志" name="audit">
            <div class="filter-row">
              <el-input v-model="auditFilter.username" placeholder="操作人" clearable />
              <el-input v-model="auditFilter.action" placeholder="动作" clearable />
              <el-input v-model="auditFilter.targetType" placeholder="对象类型" clearable />
              <el-button type="primary" :icon="Search" @click="pageNum = 1; loadAudit()">查询</el-button>
            </div>
            <el-table v-loading="auditLoading" :data="auditRecords" stripe empty-text="暂无审计记录">
              <el-table-column prop="id" label="ID" width="90" />
              <el-table-column prop="username" label="操作人" width="120" />
              <el-table-column prop="action" label="动作" min-width="150" />
              <el-table-column prop="targetType" label="对象类型" width="110" />
              <el-table-column prop="targetId" label="对象 ID" width="120" />
              <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip />
              <el-table-column prop="ip" label="IP" width="140" />
              <el-table-column label="时间" width="175">
                <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="协同日志" name="collab">
            <div class="filter-row">
              <el-input v-model="collabFilter.documentId" placeholder="文档 ID" clearable />
              <el-input v-model="collabFilter.username" placeholder="操作人" clearable />
              <el-select v-model="collabFilter.merged" placeholder="合并标记" clearable style="width: 120px">
                <el-option label="已合并" :value="1" />
                <el-option label="未合并" :value="0" />
              </el-select>
              <el-select v-model="collabFilter.conflict" placeholder="冲突标记" clearable style="width: 120px">
                <el-option label="有冲突" :value="1" />
                <el-option label="无冲突" :value="0" />
              </el-select>
              <el-button type="primary" :icon="Search" @click="pageNum = 1; loadCollab()">查询</el-button>
            </div>
            <el-table v-loading="collabLoading" :data="collabRecords" stripe empty-text="暂无协同日志">
              <el-table-column prop="id" label="ID" width="90" />
              <el-table-column prop="documentId" label="文档 ID" width="110" />
              <el-table-column label="单元格" width="110">
                <template #default="{ row }">R{{ row.rowNo }} C{{ row.colNo }}</template>
              </el-table-column>
              <el-table-column prop="actorName" label="操作人" width="120" />
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.conflict === 1" type="danger" size="small">冲突</el-tag>
                  <el-tag v-else-if="row.merged === 1" type="warning" size="small">合并</el-tag>
                  <el-tag v-else type="info" size="small">普通写入</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="175">
                <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="在线会话" name="rooms">
            <div class="filter-row">
              <el-input v-model="roomFilter.documentId" placeholder="文档 ID" clearable />
              <el-input v-model="roomFilter.username" placeholder="用户" clearable />
              <el-button type="primary" :icon="Search" @click="pageNum = 1; loadRooms()">查询</el-button>
            </div>
            <el-table v-loading="roomLoading" :data="roomRecords" stripe empty-text="当前没有在线协作会话">
              <el-table-column prop="id" label="会话 ID" width="90" />
              <el-table-column prop="documentId" label="文档 ID" width="100" />
              <el-table-column prop="documentName" label="文档名" min-width="180" show-overflow-tooltip />
              <el-table-column prop="actorName" label="用户" width="120" />
              <el-table-column prop="nodeId" label="节点" min-width="150" />
              <el-table-column label="可编辑" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.canEdit === 1 ? 'success' : 'info'" size="small">
                    {{ row.canEdit === 1 ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="最近活跃" width="175">
                <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" type="danger" :icon="Warning" @click="kickRoom(row)">下线</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="版本策略" name="version">
            <div class="policy-layout">
              <div class="policy-form">
                <div class="policy-title">留存与自动快照</div>
                <div class="policy-fields">
                  <div class="policy-field">
                    <span>每文档保留版本数</span>
                    <el-input-number v-model="policyForm.retentionCount" :min="1" :max="1000" controls-position="right" />
                  </div>
                  <div class="policy-field">
                    <span>自动快照间隔（分钟）</span>
                    <el-input-number v-model="policyForm.autoCellIntervalMinutes" :min="1" :max="10080" controls-position="right" />
                  </div>
                </div>
                <div class="policy-meta">
                  <span>
                    环境默认 {{ versionPolicy.envRetentionCount || '-' }} 个 /
                    {{ versionPolicy.envAutoCellIntervalMinutes || '-' }} 分钟
                  </span>
                  <span v-if="versionPolicy.retentionUpdatedBy">
                    上次调整 {{ versionPolicy.retentionUpdatedBy }} · {{ formatTime(versionPolicy.retentionUpdatedTime) }}
                  </span>
                </div>
                <el-button type="primary" :icon="Setting" :loading="policySaving" @click="savePolicy">
                  保存策略
                </el-button>
              </div>

              <div class="policy-stats">
                <div class="stats-grid">
                  <div class="stat-box">
                    <span>版本总数</span>
                    <strong>{{ versionStats.versionCount || 0 }}</strong>
                  </div>
                  <div class="stat-box">
                    <span>版本分块</span>
                    <strong>{{ versionStats.chunkSnapshotCount || 0 }}</strong>
                  </div>
                  <div class="stat-box">
                    <span>快照单元格</span>
                    <strong>{{ (versionStats.cellCount || 0).toLocaleString() }}</strong>
                  </div>
                  <div class="stat-box stat-accent">
                    <span>估算占用</span>
                    <strong>{{ versionStats.approxSizeText || '0 B' }}</strong>
                  </div>
                  <div class="stat-box">
                    <span>可回收版本</span>
                    <strong>{{ versionStats.reclaimableVersions || 0 }}</strong>
                  </div>
                  <div class="stat-box">
                    <span>有版本文档</span>
                    <strong>{{ versionStats.documentsWithVersions || 0 }}</strong>
                  </div>
                </div>
              </div>
            </div>

            <div class="top-documents">
              <div class="section-title">占用 Top 10</div>
              <el-table v-loading="statsLoading" :data="versionStats.topDocuments || []" stripe empty-text="暂无版本数据">
                <el-table-column prop="documentName" label="文档名" min-width="220" show-overflow-tooltip />
                <el-table-column prop="documentId" label="文档 ID" width="110" />
                <el-table-column prop="versionCount" label="版本数" width="90" />
                <el-table-column prop="chunkCount" label="分块数" width="100" />
                <el-table-column prop="approxSizeText" label="占用" width="130" />
              </el-table>
            </div>
          </el-tab-pane>
        </el-tabs>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            background
            @current-change="loadCurrent"
          />
        </div>
      </el-card>
    </section>
  </main>
</template>

<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any -- spreadsheet JSON rows and editor payloads */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Refresh, Search, Setting, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getOpsSummary,
  getVersionPolicy,
  getVersionStats,
  kickOnlineRoom,
  updateVersionPolicy,
} from '@/api/operations'
import { useOpsTables } from '@/views/excel/composables/useOpsTables'

const router = useRouter()
const loading = ref(false)
const summary = ref<any>(null)
const activeTab = ref('audit')
const pageNum = ref(1)
const pageSize = 20

const policyForm = ref({ retentionCount: 10, autoCellIntervalMinutes: 30 })
const versionPolicy = ref<any>({})
const versionStats = ref<any>({})
const policySaving = ref(false)
const statsLoading = ref(false)

const {
  auditFilter,
  collabFilter,
  roomFilter,
  auditRecords,
  collabRecords,
  roomRecords,
  auditLoading,
  collabLoading,
  roomLoading,
  total,
  loadAudit,
  loadCollab,
  loadRooms,
} = useOpsTables({ activeTab, pageNum, pageSize })

onMounted(() => {
  refreshAll()
})

function goBack() {
  router.push({ name: 'excel' })
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.all([loadSummary(), loadPolicy(), loadVersionStats(), loadCurrent()])
  } catch (error: any) {
    ElMessage.error(error.message || '加载运营中心失败')
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await getOpsSummary()
  } catch {
    summary.value = null
  }
}

async function loadPolicy() {
  try {
    versionPolicy.value = (await getVersionPolicy()) || {}
    policyForm.value.retentionCount = Number(versionPolicy.value.retentionCount ?? 10)
    policyForm.value.autoCellIntervalMinutes = Number(versionPolicy.value.autoCellIntervalMinutes ?? 30)
  } catch (error: any) {
    ElMessage.error(error.message || '加载版本策略失败')
  }
}

async function loadVersionStats() {
  statsLoading.value = true
  try {
    versionStats.value = (await getVersionStats()) || {}
  } catch (error: any) {
    ElMessage.error(error.message || '加载版本统计失败')
  } finally {
    statsLoading.value = false
  }
}

function loadCurrent() {
  if (activeTab.value === 'audit') return loadAudit()
  if (activeTab.value === 'collab') return loadCollab()
  if (activeTab.value === 'rooms') return loadRooms()
  return Promise.resolve()
}

async function savePolicy() {
  policySaving.value = true
  try {
    const result = await updateVersionPolicy(policyForm.value)
    versionPolicy.value = result || {}
    ElMessage.success('版本策略已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    policySaving.value = false
  }
}

async function kickRoom(row: any) {
  try {
    await ElMessageBox.confirm(`确定下线会话 ${row.id}（${row.actorName}）吗？`, '强制下线', {
      confirmButtonText: '下线',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await kickOnlineRoom(row.id)
    ElMessage.success('已强制下线')
    loadRooms()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '操作失败')
    }
  }
}

function formatTime(value: string) {
  if (!value) return '-'
  return String(value).replace('T', ' ').substring(0, 19)
}
</script>

<style scoped>
.operation-center-shell {
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
  max-width: 680px;
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

.ops-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.metric {
  padding: 16px 18px;
  border: 1px solid var(--border-default, #e2e8f0);
  border-radius: 8px;
  background: var(--dl-panel, #ffffff);
}

.metric span,
.metric strong {
  display: block;
}

.metric span {
  color: var(--text-muted, #64748b);
  font-size: 13px;
}

.metric strong {
  margin-top: 6px;
  font-size: 26px;
  line-height: 1;
}

.metric-accent {
  border-color: rgba(77, 125, 87, 0.24);
  background: linear-gradient(180deg, #f5faf6, var(--dl-panel, #ffffff));
}

.metric-accent strong {
  color: var(--dl-accent-strong, #185c3c);
}

.admin-panel {
  border: 1px solid var(--border-default, #e2e8f0);
}

.filter-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.filter-row .el-input {
  width: 180px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 18px;
}

.policy-layout {
  display: grid;
  grid-template-columns: minmax(280px, 380px) 1fr;
  gap: 24px;
  margin-bottom: 24px;
}

.policy-form {
  padding: 18px;
  border: 1px solid var(--border-default, #e2e8f0);
  border-radius: 8px;
  background: #fafbf9;
}

.policy-title,
.section-title {
  margin-bottom: 14px;
  font-size: 15px;
  font-weight: 700;
}

.policy-fields {
  display: grid;
  gap: 12px;
  margin-bottom: 12px;
}

.policy-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.policy-field span {
  color: var(--text-muted, #64748b);
  font-size: 13px;
}

.policy-meta {
  display: grid;
  gap: 6px;
  margin-bottom: 16px;
  color: var(--text-muted, #64748b);
  font-size: 12px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.stat-box {
  padding: 14px 16px;
  border: 1px solid var(--border-default, #e2e8f0);
  border-radius: 8px;
}

.stat-box span,
.stat-box strong {
  display: block;
}

.stat-box span {
  color: var(--text-muted, #64748b);
  font-size: 12px;
}

.stat-box strong {
  margin-top: 6px;
  font-size: 20px;
  line-height: 1;
}

.stat-accent strong {
  color: var(--dl-accent-strong, #185c3c);
}

@media (max-width: 900px) {
  .admin-header {
    align-items: start;
    flex-direction: column;
  }

  .ops-metrics,
  .policy-layout,
  .stats-grid {
    grid-template-columns: 1fr;
  }
}
</style>
