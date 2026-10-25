<template>
  <main class="dl-page dashboard-page">
    <section class="dl-shell dashboard-shell">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">Argus Excel Workspace</p>
          <h1>Excel 在线协作编辑</h1>
          <p class="summary">支持 Excel 文档上传、解析、实时协同编辑、版本快照、分享与后台导出。</p>
        </div>

        <div class="header-actions">
          <el-button v-if="selectedRows.length > 0" type="danger" plain :icon="Delete" @click="batchRemoveDocuments">
            批量回收 {{ selectedRows.length }}
          </el-button>
          <el-button :icon="Download" @click="goExports">导出任务</el-button>
          <el-button v-if="isAdmin" :icon="DataLine" @click="goOps">运营中心</el-button>
          <el-button :icon="FolderOpened" @click="goRecycle">回收站</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="loadDocuments">刷新</el-button>
          <el-button type="primary" :icon="UploadFilled" :loading="uploading" @click="selectFile">
            上传 Excel
          </el-button>
          <input
            ref="fileInput"
            class="file-input"
            type="file"
            accept=".xlsx,.xls"
            @change="handleFileChange"
          />
        </div>
      </header>

      <section class="metrics-row">
        <div class="metric">
          <span>文档总数</span>
          <strong>{{ total }}</strong>
        </div>
        <div class="metric">
          <span>当前页</span>
          <strong>{{ pageNum }}</strong>
        </div>
        <div class="metric metric-accent">
          <span>Sheet 总数</span>
          <strong>{{ totalSheets }}</strong>
        </div>
      </section>

      <el-card class="document-panel" shadow="never">
        <template #header>
          <div class="panel-header">
            <div>
              <h2>文档列表</h2>
              <p>点击文档名进入在线编辑器，拥有编辑权限时可协同修改。</p>
            </div>
            <el-tag v-if="uploading" type="warning" effect="light">正在解析上传文件</el-tag>
          </div>
        </template>

        <el-table
          v-loading="loading"
          :data="documents"
          row-key="id"
          stripe
          class="document-table"
          empty-text="还没有文档，先上传一个 Excel 文件吧。"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="文档名称" min-width="240">
            <template #default="{ row }">
              <button class="doc-link" @click="openDocument(row.id)">
                <span>{{ row.name }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="Sheet" width="90">
            <template #default="{ row }">{{ row.sheetCount || 0 }} 个</template>
          </el-table-column>
          <el-table-column label="文件大小" width="120">
            <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="权限" width="90">
            <template #default="{ row }">
              <el-tag :type="permissionTagType(row.permission)" size="small">
                {{ permissionLabel(row.permission) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="180">
            <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="520" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.canEdit" size="small" type="primary" :icon="EditPen" @click="openDocument(row.id)">
                编辑
              </el-button>
              <el-button v-else size="small" :icon="View" @click="openDocument(row.id)">查看</el-button>
              <el-button v-if="row.canEdit" size="small" :icon="Clock" @click="openVersions(row.id)">
                版本
              </el-button>
              <el-button v-if="row.canManage" size="small" :icon="Share" @click="openShareDialog(row)">
                分享
              </el-button>
              <el-button v-if="row.canEdit" size="small" :icon="Edit" @click="renameDocumentAction(row)">
                重命名
              </el-button>
              <el-button size="small" :icon="Download" :loading="exportingId === row.id" @click="exportDocument(row)">
                后台导出
              </el-button>
              <el-button v-if="row.canManage" size="small" type="danger" :icon="Delete" @click="removeDocument(row)">
                回收
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
            @current-change="loadDocuments"
          />
        </div>
      </el-card>
    </section>

    <el-dialog v-model="shareVisible" title="文档分享" width="min(480px, calc(100% - 32px))">
      <template v-if="shareDoc">
        <div class="share-doc-name">
          <span>文档</span>
          <strong>{{ shareDoc.name }}</strong>
        </div>

        <div class="share-grant">
          <el-input v-model="grantUsername" placeholder="输入用户名" clearable />
          <el-select v-model="grantPermission" class="share-role-select">
            <el-option label="可编辑" value="editor" />
            <el-option label="只读" value="viewer" />
          </el-select>
          <el-button type="primary" :loading="sharing" @click="grantShare">添加</el-button>
        </div>

        <el-table :data="shareUsers" size="small" empty-text="暂无分享用户">
          <el-table-column prop="displayName" label="显示名" min-width="120" />
          <el-table-column prop="username" label="用户名" min-width="120" />
          <el-table-column label="权限" width="110">
            <template #default="{ row }">
              <el-tag :type="permissionTagType(row.role)" size="small">
                {{ permissionLabel(row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="" width="90" align="right">
            <template #default="{ row }">
              <el-button v-if="String(row.role).toUpperCase() !== 'OWNER'" link type="danger" @click="revokeShare(row)">
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
/**
 * Excel 工作台首页：文档列表（分页/搜索）、上传入口、分享、删除（进回收站）、
 * 导出。列表项的每个操作都对应后端一个端点；删除是软删（进回收站，
 * 06 篇），这里永远不出现"物理删除"按钮。
 */
/* eslint-disable @typescript-eslint/no-explicit-any -- spreadsheet rows and dynamic table metadata */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Clock,
  DataLine,
  Delete,
  Download,
  Edit,
  EditPen,
  FolderOpened,
  Refresh,
  Share,
  UploadFilled,
  View,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchDeleteDocuments,
  createExportTask,
  deleteDocument,
  getDocumentList,
  listDocumentAcl,
  renameDocument,
  revokeDocumentAcl,
  shareDocument,
  uploadExcel,
} from '@/api/excel'
import { getUserProfile, isAdminUser } from '@/utils/auth'

const router = useRouter()
const profile = ref<Record<string, any> | null>(null)
const isAdmin = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const documents = ref<any[]>([])
const loading = ref(false)
const uploading = ref(false)
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const selectedRows = ref<any[]>([])
const exportingId = ref<number | null>(null)

const shareVisible = ref(false)
const shareDoc = ref<any>(null)
const shareUsers = ref<any[]>([])
const grantUsername = ref('')
const grantPermission = ref('viewer')
const sharing = ref(false)

const totalSheets = computed(() =>
  documents.value.reduce((sum, doc) => sum + Number(doc.sheetCount || 0), 0),
)

onMounted(() => {
  profile.value = getUserProfile()
  isAdmin.value = isAdminUser()
  loadDocuments()
})

function goExports() {
  router.push({ name: 'excel-exports' })
}

function goOps() {
  router.push({ name: 'excel-ops' })
}

function goRecycle() {
  router.push({ name: 'excel-recycle' })
}

function openVersions(id: number) {
  router.push({ name: 'excel-versions', params: { id } })
}

async function loadDocuments() {
  loading.value = true
  try {
    const res = await getDocumentList(pageNum.value, pageSize)
    const payload = res.data
    if (!payload?.success) throw new Error(payload?.message || '接口返回异常')
    documents.value = payload.data?.records || []
    total.value = payload.data?.total || 0
  } catch (error: any) {
    ElMessage.error(`加载文档失败：${error.message}`)
  } finally {
    loading.value = false
  }
}

function selectFile() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  if (!/\.(xlsx|xls)$/i.test(file.name)) {
    ElMessage.warning('请选择 .xlsx 或 .xls 文件')
    target.value = ''
    return
  }

  uploading.value = true
  try {
    const res = await uploadExcel(file)
    const payload = res.data
    if (!payload?.success) throw new Error(payload?.message || '上传失败')
    const documentId = payload.data?.documentId
    ElMessage.success(`上传成功，已解析 ${payload.data?.sheetCount || 0} 个 Sheet`)
    if (documentId) router.push({ name: 'excel-editor', params: { id: documentId } })
    else loadDocuments()
  } catch (error: any) {
    const msg = error?.response?.data?.message || error.message || '上传失败'
    ElMessage.error(msg)
  } finally {
    uploading.value = false
    target.value = ''
  }
}

function openDocument(id: number) {
  router.push({ name: 'excel-editor', params: { id } })
}

async function renameDocumentAction(row: any) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名文档', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputValue: row.name,
    })
    if (!value || !value.trim()) return
    const res = await renameDocument(row.id, value.trim())
    if (res.data && res.data.success === false) throw new Error(res.data.message || '重命名失败')
    row.name = value.trim()
    ElMessage.success('重命名成功')
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`重命名失败：${error.message}`)
    }
  }
}

async function exportDocument(row: any) {
  exportingId.value = row.id
  try {
    const res = await createExportTask(row.id)
    if (res.data?.success === false) throw new Error(res.data.message || '创建导出任务失败')
    ElMessage.success('导出任务已创建，完成后可到导出任务页下载')
    router.push({ name: 'excel-exports' })
  } catch (error: any) {
    if (error?.response?.status === 403) {
      ElMessage.warning('当前账号没有后台导出权限')
    } else {
      ElMessage.error(`创建导出任务失败：${error.message || error}`)
    }
  } finally {
    exportingId.value = null
  }
}

async function removeDocument(row: any) {
  try {
    await ElMessageBox.confirm(`确定将文档"${row.name}"移入回收站吗？回收站保留 90 天。`, '移入回收站', {
      confirmButtonText: '移入回收站',
      cancelButtonText: '取消',
      type: 'warning',
    })
    const res = await deleteDocument(row.id)
    if (res.data?.success === false) throw new Error(res.data.message || '移入回收站失败')
    ElMessage.success('已移入回收站')
    if (documents.value.length === 1 && pageNum.value > 1) pageNum.value -= 1
    loadDocuments()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`移入回收站失败：${error.message}`)
    }
  }
}

function handleSelectionChange(rows: any[]) {
  selectedRows.value = rows || []
}

async function batchRemoveDocuments() {
  const ids = selectedRows.value
    .filter((row) => row.canManage || isAdmin.value)
    .map((row) => row.id)
  if (ids.length === 0) {
    ElMessage.warning('请选择你有管理权限的文档')
    return
  }

  try {
    await ElMessageBox.confirm(`将选中的 ${ids.length} 个文档移入回收站吗？`, '批量移入回收站', {
      confirmButtonText: '移入回收站',
      cancelButtonText: '取消',
      type: 'warning',
    })
    const res = await batchDeleteDocuments(ids)
    const result = res.data?.data || {}
    const count = result.successCount ?? ids.length
    ElMessage.success(`已移入回收站 ${count} 个文档${result.rejectedCount ? `，${result.rejectedCount} 个被跳过` : ''}`)
    selectedRows.value = []
    loadDocuments()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`批量移入回收站失败：${error.message}`)
    }
  }
}

async function openShareDialog(row: any) {
  shareDoc.value = row
  shareUsers.value = []
  grantUsername.value = ''
  grantPermission.value = 'viewer'
  shareVisible.value = true
  await loadShareUsers()
}

async function loadShareUsers() {
  if (!shareDoc.value) return
  try {
    const res = await listDocumentAcl(shareDoc.value.id)
    const data = res.data?.data || []
    shareUsers.value = Array.isArray(data) ? data : []
  } catch (error: any) {
    ElMessage.error(error.message || '加载分享列表失败')
  }
}

async function grantShare() {
  if (!grantUsername.value.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  sharing.value = true
  try {
    const res = await shareDocument(
      shareDoc.value.id,
      null,
      grantUsername.value.trim(),
      grantPermission.value,
    )
    if (res.data?.success === false) throw new Error(res.data.message || '分享失败')
    ElMessage.success('分享成功')
    grantUsername.value = ''
    await loadShareUsers()
  } catch (error: any) {
    ElMessage.error(error.message || '分享失败')
  } finally {
    sharing.value = false
  }
}

async function revokeShare(row: any) {
  try {
    await ElMessageBox.confirm(`确定移除 ${row.username} 的访问权限吗？`, '移除分享', {
      confirmButtonText: '移除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    const res = await revokeDocumentAcl(shareDoc.value.id, row.userId)
    if (res.data?.success === false) throw new Error(res.data.message || '移除失败')
    ElMessage.success('已移除分享')
    loadShareUsers()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '移除失败')
    }
  }
}

function permissionLabel(permission: string) {
  const value = String(permission || '').toUpperCase()
  if (value === 'OWNER') return '所有者'
  if (value === 'EDITOR' || value === 'EDIT') return '可编辑'
  return '只读'
}

function permissionTagType(permission: string) {
  const value = String(permission || '').toUpperCase()
  if (value === 'OWNER') return 'danger'
  if (value === 'EDITOR' || value === 'EDIT') return 'success'
  return 'info'
}

function formatSize(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, index)).toFixed(1)} ${units[index]}`
}

function formatTime(time: string) {
  if (!time) return '-'
  return String(time).replace('T', ' ').substring(0, 19)
}
</script>

<style scoped>
.dashboard-shell {
  padding: 12px 0 48px;
}

.workspace-header {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 24px;
  align-items: end;
  padding: 0 0 26px;
}

.eyebrow {
  margin: 0 0 8px;
  color: var(--brand-accent-dark, #185c3c);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.08;
}

.summary {
  max-width: 760px;
  margin: 12px 0 0;
  color: var(--text-muted, #64748b);
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

.file-input {
  display: none;
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.metric {
  min-height: 86px;
  padding: 16px 18px;
  border: 1px solid var(--border-default, #e2e8f0);
  border-radius: 8px;
  background: var(--dl-panel, #ffffff);
}

.metric span {
  display: block;
  color: var(--text-muted, #64748b);
  font-size: 13px;
}

.metric strong {
  display: block;
  margin-top: 6px;
  font-size: 28px;
  line-height: 1;
}

.metric-accent {
  border-color: rgba(47, 125, 87, 0.24);
  background: linear-gradient(180deg, #f5faf6, var(--dl-panel, #ffffff));
}

.metric-accent strong {
  color: var(--dl-accent-strong, #185c3c);
}

.document-panel {
  border: 1px solid var(--border-default, #e2e8f0);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.panel-header h2 {
  margin: 0;
  font-size: 18px;
}

.panel-header p {
  margin: 6px 0 0;
  color: var(--text-muted, #64748b);
  font-size: 13px;
}

.document-table {
  width: 100%;
}

.doc-link {
  max-width: 100%;
  padding: 0;
  border: 0;
  color: var(--brand-primary, #4a90d9);
  background: transparent;
  cursor: pointer;
  font-weight: 650;
  text-align: left;
}

.doc-link span {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding-top: 18px;
}

.share-doc-name {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
  color: var(--text-muted, #64748b);
  font-size: 13px;
}

.share-doc-name strong {
  overflow: hidden;
  color: var(--dl-ink, #17211b);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share-grant {
  display: grid;
  grid-template-columns: 1fr 120px auto;
  gap: 8px;
  margin-bottom: 14px;
}

.share-role-select {
  width: 100%;
}

@media (max-width: 900px) {
  .workspace-header {
    grid-template-columns: 1fr;
  }

  .header-actions {
    justify-content: stretch;
  }

  .metrics-row {
    grid-template-columns: 1fr;
  }

  .share-grant {
    grid-template-columns: 1fr;
  }
}
</style>
