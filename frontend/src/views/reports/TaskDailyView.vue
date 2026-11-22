<template>
  <main class="dl-page task-report-page">
    <section class="dl-shell">
      <div class="page-hero">
        <p class="hero-kicker">TASK DAILY REPORT</p>
        <h1 class="hero-title">任务进度日报</h1>
        <p class="hero-sub">
          绑定在线任务表 → 自动聚合进度与逾期 → 简洁日报（含 AI 解读）→ 定时推送钉钉。
        </p>
      </div>

      <!-- 绑定区 -->
      <el-card class="panel">
        <template #header><b>① 数据源绑定</b>（在线协作表）</template>
        <div class="bind-row">
          <el-select v-model="selectedDocId" placeholder="选择我的 Excel 文档" class="bind-select"
                     :loading="docsLoading">
            <el-option v-for="doc in myDocs" :key="doc.id" :label="doc.name" :value="doc.id" />
          </el-select>
          <el-button type="primary" :loading="binding" @click="doBind">绑定此表</el-button>
        </div>
        <p v-if="bindingSource" class="bind-info">
          已绑定：<b>{{ bindingSource.documentName }}</b> / Sheet「{{ bindingSource.sheetName }}」
        </p>
        <p v-else class="bind-info muted">尚未绑定——绑定后每日 18:00 自动生成并推送。</p>
      </el-card>

      <!-- 日报区 -->
      <el-card class="panel">
        <template #header>
          <div class="panel-head">
            <b>② 今日日报</b>
            <el-button type="primary" :loading="generating" :disabled="!bindingSource" @click="doGenerate">
              立即生成
            </el-button>
          </div>
        </template>
        <el-alert v-if="exec && exec.status === 'FAILED'" type="error" :closable="false"
                  :title="'生成失败：' + (exec.errorMessage || '未知原因')" />
        <div v-if="exec && exec.htmlContent" class="report-body">
          <div class="report-markdown" v-html="renderedReport"></div>
        </div>
        <el-empty v-else-if="!generating" description="今天还没有日报——绑定任务表后点「立即生成」。" :image-size="72" />
        <p v-if="exec" class="meta-line">
          状态 {{ exec.status }} ｜ 推送 {{ exec.pushStatus || '—' }} ｜ 耗时 {{ exec.durationMs ?? 0 }}ms
          <span v-if="exec.pushDetail">｜ {{ exec.pushDetail }}</span>
        </p>
      </el-card>

      <!-- 历史 -->
      <el-card class="panel">
        <template #header><b>③ 近 30 天历史</b></template>
        <el-table :data="history" size="small" empty-text="暂无历史记录">
          <el-table-column prop="bizDate" label="日期" width="110" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="pushStatus" label="推送" width="90" />
          <el-table-column prop="aiBriefText" label="AI 解读" min-width="240" show-overflow-tooltip />
          <el-table-column label="查看" width="80">
            <template #default="{ row }">
              <el-button link type="primary" @click="loadDate(row.bizDate)">打开</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'
import { getDocumentList } from '@/api/excel'
import {
  bindTaskTable, generateTaskReport, getBinding, getTaskReport, listTaskReports,
  type TaskReportBinding, type TaskReportExecution,
} from '@/api/taskReport'

const myDocs = ref<Array<{ id: number; name: string }>>([])
const docsLoading = ref(false)
const selectedDocId = ref<number | null>(null)
const bindingSource = ref<Record<string, string> | null>(null)
const binding = ref(false)
const generating = ref(false)
const exec = ref<TaskReportExecution | null>(null)
const history = ref<TaskReportExecution[]>([])

const renderedReport = computed(() =>
  exec.value?.htmlContent ? renderSafeMarkdown(exec.value.htmlContent) : '',
)

function unwrap<T = unknown>(payload: unknown): T {
  // 双层剥离：AxiosResponse.data = ApiResponse{success,data,message}，再取一层才是业务数据
  let current = payload
  for (let depth = 0; depth < 2; depth++) {
    if (current && typeof current === 'object' && 'data' in (current as Record<string, unknown>)) {
      current = (current as Record<string, unknown>).data
    } else {
      break
    }
  }
  return current as T
}

async function loadBinding() {
  const res = unwrap(await getBinding()) as TaskReportBinding
  const source = (res?.source ?? null) as Record<string, string> | null
  bindingSource.value = source && Object.keys(source).length ? source : null
}

async function loadDocs() {
  docsLoading.value = true
  try {
    const res = unwrap(await getDocumentList(1, 50)) as { records?: Array<{ id: number; name: string }> }
    myDocs.value = res?.records ?? []
    if (myDocs.value.length && !selectedDocId.value) {
      const first = myDocs.value[0]
      if (first) {
        selectedDocId.value = first.id
      }
    }
  } finally {
    docsLoading.value = false
  }
}

async function loadToday() {
  const res = unwrap(await getTaskReport()) as TaskReportExecution | null
  exec.value = res ?? null
}

async function loadHistory() {
  const res = unwrap(await listTaskReports()) as TaskReportExecution[]
  history.value = Array.isArray(res) ? res : []
}

async function doBind() {
  if (!selectedDocId.value) return
  binding.value = true
  try {
    const res = unwrap(await bindTaskTable(selectedDocId.value)) as TaskReportBinding
    bindingSource.value = (res?.source ?? null) as Record<string, string> | null
    await loadToday()
  } finally {
    binding.value = false
  }
}

async function doGenerate() {
  generating.value = true
  try {
    const res = unwrap(await generateTaskReport()) as TaskReportExecution
    exec.value = res
    await loadHistory()
  } finally {
    generating.value = false
  }
}

async function loadDate(date: string) {
  const res = unwrap(await getTaskReport(date)) as TaskReportExecution | null
  exec.value = res ?? null
}

onMounted(async () => {
  await Promise.all([loadBinding(), loadDocs(), loadToday(), loadHistory()])
})
</script>

<style scoped>
.page-hero {
  margin-bottom: 16px;
}
.hero-kicker {
  margin: 0 0 4px;
  color: var(--brand-primary, #4a90d9);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 2px;
}
.hero-title {
  margin: 0 0 6px;
  font-size: 30px;
}
.hero-sub {
  margin: 0;
  color: var(--text-muted, #64748b);
}
.panel {
  margin-bottom: 16px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.bind-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.bind-select {
  width: 320px;
}
.bind-info {
  margin: 10px 0 0;
  color: var(--text-muted, #64748b);
}
.bind-info.muted {
  opacity: 0.75;
}
.report-body {
  line-height: 1.8;
}
.report-markdown :deep(h2) {
  margin: 4px 0 10px;
  font-size: 18px;
}
.report-markdown :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--brand-primary, #4a90d9);
  background: color-mix(in srgb, var(--brand-primary, #4a90d9) 6%, transparent);
  border-radius: 4px;
}
.meta-line {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-muted, #64748b);
}
</style>
