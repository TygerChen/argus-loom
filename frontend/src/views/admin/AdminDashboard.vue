<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchAdminUsers } from '@/api/admin-user'
import { fetchGroups } from '@/api/group'
import {
  fetchMetricsOverview,
  fetchPlatformStats,
  type MetricsOverview,
  type Period,
  type UsageStats,
} from '@/api/metrics'
import { fetchDocuments } from '@/api/document'
import { extractApiError } from '@/api/http'
import StatCard from '@/components/StatCard.vue'

// ── 数据状态 ──
const overview = ref<MetricsOverview | null>(null)
const usage = ref<UsageStats | null>(null)
const userCount = ref(0)
const groupCount = ref(0)
const documentCount = ref(0)
const loading = ref(true)
const error = ref('')

// ── 快捷操作卡片 ──
const quickActions = [
  { title: '用户管理', desc: '查看和禁用用户账号', icon: 'users', color: '#4A90D9', to: '/app/admin/users' },
  { title: '用量统计', desc: 'LLM 调用量与成本分析', icon: 'chart', color: '#5CC9C1', to: '/app/admin/metrics' },
  { title: '文档管理', desc: '管理知识库文档', icon: 'doc', color: '#F5A623', to: '/app/documents' },
  { title: '群组管理', desc: '查看所有协作小组', icon: 'group', color: '#9B59B6', to: '/app/groups' },
]

// ── 系统状态指示器 ──
const systemStatus = [
  { label: 'LLM 服务', ok: true, desc: 'agnes-3.0-flash 在线' },
  { label: '向量数据库', ok: true, desc: 'PGvector 正常' },
  { label: '搜索引擎', ok: true, desc: 'Elasticsearch 健康' },
  { label: '对象存储', ok: true, desc: 'MinIO 可用' },
  { label: '数据库', ok: true, desc: 'PostgreSQL 连接正常' },
]

async function loadDashboard() {
  loading.value = true
  error.value = ''
  try {
    const [users, groups, docs, stats, platform] = await Promise.all([
      fetchAdminUsers().catch(() => []),
      fetchGroups().catch(() => ({ ownedGroups: [], joinedGroups: [], pendingInvitations: [] })),
      fetchDocuments({}).catch(() => []),
      fetchMetricsOverview().catch(() => null),
      fetchPlatformStats('LAST_7_DAYS' as Period).catch(() => null),
    ])
    userCount.value = Array.isArray(users) ? users.length : 0
    groupCount.value = Array.isArray(groups.ownedGroups) && Array.isArray(groups.joinedGroups)
      ? groups.ownedGroups.length + groups.joinedGroups.length : 0
    documentCount.value = Array.isArray(docs) ? docs.length : 0
    overview.value = stats
    usage.value = platform
  } catch (err: unknown) {
    error.value = extractApiError(err, '加载仪表盘失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)

function formatNumber(n: number): string {
  return n.toLocaleString('zh-CN')
}

function formatCost(n: number | undefined): string {
  if (n == null) return '--'
  return '¥' + n.toFixed(4)
}
</script>

<template>
  <div class="admin-dashboard">
    <!-- Error -->
    <div v-if="error" class="admin-dashboard__error">{{ error }}</div>

    <!-- Loading skeleton -->
    <div v-if="loading && !error" class="admin-dashboard__skeleton">
      <div class="skeleton" style="height:100px;width:100%;border-radius:12px;margin-bottom:20px" />
      <div class="skeleton" style="height:200px;width:100%;border-radius:12px" />
    </div>

    <!-- ════════ 统计卡片 ════════ -->
    <section v-if="!loading" class="admin-dashboard__stats">
      <StatCard label="平台用户" :value="formatNumber(userCount)" unit="人" color="#4A90D9">
        <template #icon>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" />
            <path d="M23 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" />
          </svg>
        </template>
      </StatCard>
      <StatCard label="协作群组" :value="formatNumber(groupCount)" unit="个" color="#5CC9C1">
        <template #icon>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <circle cx="8" cy="7" r="3" /><circle cx="16" cy="7" r="3" />
            <path d="M2 20V19C2 16.7909 3.79086 15 6 15H10C12.2091 15 14 16.7909 14 19V20" />
            <path d="M14 15H16C18.2091 15 20 16.7909 20 19V20" />
          </svg>
        </template>
      </StatCard>
      <StatCard label="知识库文档" :value="formatNumber(documentCount)" unit="份" color="#F5A623">
        <template #icon>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><polyline points="14 2 14 8 20 8" />
          </svg>
        </template>
      </StatCard>
      <StatCard label="今日成本" :value="formatCost(overview?.todayCost)" unit="CNY" color="#9B59B6">
        <template #icon>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <line x1="12" y1="1" x2="12" y2="23" /><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
          </svg>
        </template>
      </StatCard>
    </section>

    <!-- ════════ 快捷操作 & 系统状态 ════════ -->
    <div class="admin-dashboard__grid">
      <!-- 快捷操作 -->
      <section class="admin-dashboard__panel">
        <h3 class="admin-dashboard__panel-title">快捷操作</h3>
        <div class="admin-dashboard__actions">
          <router-link
            v-for="action in quickActions"
            :key="action.to"
            :to="action.to"
            class="admin-dashboard__action-card"
            :style="{ '--action-color': action.color }"
          >
            <span class="admin-dashboard__action-dot" />
            <div class="admin-dashboard__action-body">
              <h4>{{ action.title }}</h4>
              <p>{{ action.desc }}</p>
            </div>
            <svg class="admin-dashboard__action-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </router-link>
        </div>
      </section>

      <!-- 系统状态 -->
      <section class="admin-dashboard__panel">
        <h3 class="admin-dashboard__panel-title">系统状态</h3>
        <div class="admin-dashboard__status-list">
          <div
            v-for="svc in systemStatus"
            :key="svc.label"
            class="admin-dashboard__status-item"
          >
            <span class="admin-dashboard__status-dot" :class="{ 'is-ok': svc.ok }" />
            <div class="admin-dashboard__status-body">
              <strong>{{ svc.label }}</strong>
              <span>{{ svc.desc }}</span>
            </div>
            <span class="admin-dashboard__status-badge" :class="{ 'is-ok': svc.ok }">
              {{ svc.ok ? '正常' : '异常' }}
            </span>
          </div>
        </div>
      </section>
    </div>

    <!-- ════════ LLM 调用概览 ════════ -->
    <section v-if="overview || usage" class="admin-dashboard__panel">
      <h3 class="admin-dashboard__panel-title">LLM 调用概览（近 7 天）</h3>
      <div class="admin-dashboard__llm-grid">
        <div class="admin-dashboard__llm-card">
          <span class="admin-dashboard__llm-label">总调用次数</span>
          <span class="admin-dashboard__llm-value">{{ formatNumber(usage?.totalRequests ?? 0) }}</span>
        </div>
        <div class="admin-dashboard__llm-card">
          <span class="admin-dashboard__llm-label">总 Token</span>
          <span class="admin-dashboard__llm-value">{{ formatNumber(usage?.totalTokens ?? 0) }}</span>
        </div>
        <div class="admin-dashboard__llm-card">
          <span class="admin-dashboard__llm-label">成功率</span>
          <span class="admin-dashboard__llm-value admin-dashboard__llm-value--ok">
            <!-- 后端 SQL 已按 0-100 百分数返回（ROUND(*100.0/COUNT,2)），直接展示；MetricsView.formatPercent 同口径 -->
            {{ usage?.successRate != null ? Number(usage.successRate).toFixed(1) + '%' : '--' }}
          </span>
        </div>
        <div class="admin-dashboard__llm-card">
          <span class="admin-dashboard__llm-label">平均延迟</span>
          <span class="admin-dashboard__llm-value">
            {{ usage?.avgLatencyMs != null ? (usage.avgLatencyMs / 1000).toFixed(1) + 's' : '--' }}
          </span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin-dashboard { display: flex; flex-direction: column; gap: 24px; padding: 0 0 40px; }

.admin-dashboard__error {
  padding: 14px 18px; border-radius: var(--radius-md);
  background: rgba(239, 68, 68, 0.08); color: #dc2626; font-size: 0.85rem;
}

/* Stats row */
.admin-dashboard__stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

/* Two-column grid */
.admin-dashboard__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
@media (max-width: 860px) { .admin-dashboard__grid { grid-template-columns: 1fr; } }

/* Panel */
.admin-dashboard__panel {
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: 20px 24px;
}
.admin-dashboard__panel-title {
  margin: 0 0 14px;
  font-family: 'Poppins', sans-serif;
  font-size: 0.82rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-secondary);
  text-transform: uppercase;
}

/* Quick actions */
.admin-dashboard__actions { display: flex; flex-direction: column; gap: 8px; }
.admin-dashboard__action-card {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 16px;
  border-radius: var(--radius-md);
  background: var(--surface-subtle);
  border: 1px solid var(--border-subtle);
  text-decoration: none;
  transition: all 0.2s ease;
}
.admin-dashboard__action-card:hover {
  border-color: rgba(74, 144, 217, 0.2);
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  transform: translateX(3px);
}
.admin-dashboard__action-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  background: var(--action-color);
  flex-shrink: 0;
}
.admin-dashboard__action-body { flex: 1; min-width: 0; }
.admin-dashboard__action-body h4 {
  margin: 0 0 2px; font-size: 0.88rem; font-weight: 600; color: var(--text-primary);
}
.admin-dashboard__action-body p {
  margin: 0; font-size: 0.76rem; color: var(--text-muted);
}
.admin-dashboard__action-arrow {
  width: 16px; height: 16px; color: var(--text-muted); flex-shrink: 0;
}

/* Status list */
.admin-dashboard__status-list { display: flex; flex-direction: column; gap: 8px; }
.admin-dashboard__status-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-subtle);
}
.admin-dashboard__status-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #ef4444; flex-shrink: 0;
}
.admin-dashboard__status-dot.is-ok { background: #22c55e; }
.admin-dashboard__status-body { flex: 1; display: flex; flex-direction: column; }
.admin-dashboard__status-body strong { font-size: 0.82rem; color: var(--text-primary); }
.admin-dashboard__status-body span { font-size: 0.72rem; color: var(--text-muted); }
.admin-dashboard__status-badge {
  font-size: 0.7rem; font-weight: 600; padding: 2px 10px; border-radius: 100px;
  background: rgba(239,68,68,0.1); color: #dc2626;
}
.admin-dashboard__status-badge.is-ok { background: rgba(34,197,94,0.1); color: #16a34a; }

/* LLM grid */
.admin-dashboard__llm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}
.admin-dashboard__llm-card {
  display: flex; flex-direction: column; gap: 4px;
  padding: 16px;
  border-radius: var(--radius-md);
  background: var(--surface-subtle);
}
.admin-dashboard__llm-label { font-size: 0.74rem; color: var(--text-muted); }
.admin-dashboard__llm-value {
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.5rem; font-weight: 700; color: var(--text-primary);
}
.admin-dashboard__llm-value--ok { color: #16a34a; }
</style>
