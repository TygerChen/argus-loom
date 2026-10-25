<template>
  <main class="dl-page versions-page">
    <section class="dl-shell versions-shell">
      <header class="admin-header">
        <div>
          <p class="eyebrow">DataLoom Versions</p>
          <h1>文档版本</h1>
          <p class="summary">
            {{ docName ? `文档：${docName}` : '保存完整快照，随时预览和回滚。' }}
          </p>
        </div>
        <div class="admin-actions">
          <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
          <el-button type="primary" :icon="Plus" :loading="creating" @click="createVersion">
            保存当前版本
          </el-button>
          <el-button :icon="ArrowLeft" @click="goBack">返回文档列表</el-button>
        </div>
      </header>

      <section class="metrics-row">
        <div class="metric">
          <span>版本总数</span>
          <strong>{{ total }}</strong>
        </div>
        <div class="metric">
          <span>当前文档版本号</span>
          <strong>{{ doc?.version ?? '-' }}</strong>
        </div>
        <div class="metric metric-accent">
          <span>自动保留策略</span>
          <strong>最近 10 个</strong>
        </div>
      </section>

      <el-card class="admin-panel" shadow="never">
        <template #header>
          <div class="panel-header">
            <div>
              <h2>版本列表</h2>
              <p>结构保存和定时自动保存会生成快照，也可以手动保存当前状态。</p>
            </div>
            <el-tag v-if="versions.length" type="success" effect="light">
              {{ cellCountOf(versions[0]) }} 个单元格
            </el-tag>
          </div>
        </template>

        <el-table v-loading="loading" :data="versions" row-key="id" stripe empty-text="还没有版本快照。">
          <el-table-column prop="id" label="版本ID" width="90" />
          <el-table-column prop="docVersion" label="文档版本号" width="120" />
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <el-tag :type="typeColor(row.snapshotType)" size="small">{{ typeLabel(row.snapshotType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="creatorName" label="创建人" width="120">
            <template #default="{ row }">{{ row.creatorName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="summary" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="Sheet / 单元格" width="150">
            <template #default="{ row }">{{ row.sheetCount || 0 }} / {{ row.cellCount || 0 }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" :icon="View" @click="openPreview(row)">预览</el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :icon="RefreshLeft"
                :disabled="row.id === latestVersionId"
                @click="restoreVersion(row)"
              >
                回滚
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
            @current-change="loadVersions"
          />
        </div>
      </el-card>
    </section>

    <el-dialog v-model="previewVisible" title="版本预览" width="min(900px, calc(100% - 32px))">
      <template v-if="previewRow">
        <div class="preview-meta">
          <div>
            <span>版本</span>
            <strong>#{{ previewRow.id }}</strong>
          </div>
          <div>
            <span>文档版本号</span>
            <strong>{{ previewRow.docVersion }}</strong>
          </div>
          <div>
            <span>创建时间</span>
            <strong>{{ formatTime(previewRow.createTime) }}</strong>
          </div>
          <div>
            <span>单元格总数</span>
            <strong>{{ previewRow.cellCount || 0 }}</strong>
          </div>
        </div>
        <div class="preview-sheets">
          <div v-if="!previewSheets.length" class="preview-empty">该版本没有可预览数据。</div>
          <el-table v-else :data="previewSheets" size="small" stripe>
            <el-table-column prop="name" label="Sheet" min-width="140" />
            <el-table-column label="行 / 列" width="120">
              <template #default="{ row }">{{ row.row || 0 }} / {{ row.column || 0 }}</template>
            </el-table-column>
            <el-table-column label="样例单元格" min-width="320">
              <template #default="{ row }">
                <span class="sample-cells">{{ sampleText(row.celldata) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Plus, Refresh, RefreshLeft, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatTime } from '@/utils/format'
import { extractApiError } from '@/api/http'
import {
  createDocumentVersion,
  getDocument,
  listDocumentVersions,
  previewDocumentVersion,
  restoreDocumentVersion
} from '@/api/excel'

interface VersionRow {
  id: number
  docVersion: number
  snapshotType: string
  creatorName?: string
  summary?: string
  sheetCount?: number
  cellCount?: number
  createTime?: string
}

interface PreviewSheet {
  name: string
  row: number
  column: number
  celldata: unknown
}

interface VersionListData {
  records?: VersionRow[]
  total?: number
}

const props = defineProps({
  id: {
    type: String,
    required: true
  }
})

const router = useRouter()
const docId = computed(() => Number(props.id))
const doc = ref<{ version?: number; name?: string } | null>(null)
const docName = ref('')
const versions = ref<VersionRow[]>([])
const loading = ref(false)
const creating = ref(false)
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20

const previewVisible = ref(false)
const previewRow = ref<VersionRow | null>(null)
const previewSheets = ref<PreviewSheet[]>([])

const latestVersionId = computed(() => versions.value[0]?.id ?? null)

onMounted(() => loadAll())

async function loadAll() {
  await Promise.all([loadDocument(), loadVersions()])
}

async function loadDocument() {
  try {
    const response = await getDocument(docId.value)
    const data = (response.data?.data ?? {}) as { version?: number; name?: string }
    doc.value = data
    docName.value = doc.value?.name || ''
  } catch (error) {
    ElMessage.error(extractApiError(error, '加载文档失败'))
  }
}

async function loadVersions() {
  loading.value = true
  try {
    const response = await listDocumentVersions(docId.value, pageNum.value, pageSize)
    const data = (response.data?.data ?? {}) as VersionListData
    versions.value = data?.records ?? []
    total.value = data?.total ?? 0
  } catch (error) {
    ElMessage.error(extractApiError(error, '加载版本失败'))
  } finally {
    loading.value = false
  }
}

async function createVersion() {
  try {
    const { value } = await ElMessageBox.prompt('为这个版本写一句说明，方便以后识别。', '保存当前版本', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: `${new Date().toLocaleString()} 手动快照`,
      inputValidator: (val: string) => (val && val.trim() ? true : '版本说明不能为空')
    })
    if (!value || !value.trim()) return

    creating.value = true
    await createDocumentVersion(docId.value, value.trim())
    ElMessage.success('版本已保存')
    pageNum.value = 1
    await loadAll()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(extractApiError(error, '版本保存失败'))
    }
  } finally {
    creating.value = false
  }
}

async function openPreview(row: VersionRow) {
  previewVisible.value = true
  previewRow.value = row
  previewSheets.value = []
  try {
    const response = await previewDocumentVersion(docId.value, row.id)
    const data = (response.data?.data ?? {}) as { sheets?: PreviewSheet[] }
    previewSheets.value = data.sheets ?? []
  } catch (error) {
    ElMessage.error(extractApiError(error, '预览失败'))
  }
}

async function restoreVersion(row: VersionRow) {
  try {
    await ElMessageBox.confirm(
      `确定将文档回滚到版本 #${row.id}（文档版本号 ${row.docVersion}）吗？回滚前会自动保存当前状态。`,
      '版本回滚确认',
      { confirmButtonText: '回滚', cancelButtonText: '取消', type: 'warning' }
    )
    await restoreDocumentVersion(docId.value, row.id)
    ElMessage.success('版本回滚成功，其他在线端已自动重新加载')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(extractApiError(error, '版本回滚失败'))
    }
  }
}

function typeLabel(type: string) {
  const map: Record<string, string> = {
    MANUAL: '手动保存',
    STRUCTURE: '结构保存',
    CELL: '自动保存',
    ROLLBACK_POINT: '回滚前'
  }
  return map[type] || type
}

function typeColor(type: string) {
  const map: Record<string, string> = {
    MANUAL: 'primary',
    STRUCTURE: 'success',
    CELL: 'warning',
    ROLLBACK_POINT: 'info'
  }
  return map[type] || 'info'
}

function cellCountOf(record: VersionRow | undefined) {
  return record?.cellCount ?? 0
}

function sampleText(celldata: unknown) {
  if (!Array.isArray(celldata) || celldata.length === 0) return '-'
  const values = celldata
    .slice(0, 6)
    .map((cell) => {
      const value = (cell as { v?: unknown } | null | undefined)?.v
      if (value && typeof value === 'object' && Object.prototype.hasOwnProperty.call(value, 'v')) {
        return String((value as { v: unknown }).v)
      }
      return String(value ?? '')
    })
    .filter((text) => text !== '')
  return values.length ? values.join(' · ') : '（空值）'
}


function goBack() {
  router.push({ name: 'excel' })
}
</script>

<style scoped>
.versions-shell {
  padding: 36px 0 56px;
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
  color: var(--dl-accent-strong);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.admin-header h1 {
  margin: 0;
  font-size: 40px;
  line-height: 1.1;
  letter-spacing: 0;
}

.summary {
  max-width: 680px;
  margin: 12px 0 0;
  color: var(--dl-muted);
  font-size: 14px;
  line-height: 1.65;
}

.admin-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.metric {
  min-height: 84px;
  padding: 16px 18px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: var(--dl-panel);
  box-shadow: var(--dl-shadow);
}

.metric span {
  display: block;
  color: var(--dl-muted);
  font-size: 13px;
}

.metric strong {
  display: block;
  margin-top: 6px;
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

.admin-panel {
  border: 1px solid var(--dl-line);
  box-shadow: var(--dl-shadow);
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
  letter-spacing: 0;
}

.panel-header p {
  margin: 6px 0 0;
  color: var(--dl-muted);
  font-size: 13px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 18px;
}

.preview-meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.preview-meta > div {
  padding: 12px 14px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: #fafbf9;
}

.preview-meta span,
.preview-meta strong {
  display: block;
}

.preview-meta span {
  color: var(--dl-muted);
  font-size: 12px;
}

.preview-meta strong {
  margin-top: 5px;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-empty {
  padding: 24px;
  border: 1px dashed var(--dl-line);
  border-radius: 8px;
  color: var(--dl-muted);
  text-align: center;
}

.sample-cells {
  color: var(--dl-muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 760px) {
  .admin-header {
    align-items: start;
    flex-direction: column;
  }

  .admin-header h1 {
    font-size: 32px;
  }

  .metrics-row,
  .preview-meta {
    grid-template-columns: 1fr;
  }
}
</style>
