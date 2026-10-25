<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { fetchGroups } from '@/api/group'
import { extractApiError } from '@/api/http'
import {
  createAssistantSession,
  deleteAssistantSession,
  fetchAssistantSessions,
  fetchAssistantSessionDetail,
  fetchAssistantConversationContext,
  renameAssistantSession,
  archiveSession,
  restoreSession,
  streamAssistantMessage,
} from '@/api/assistant'
import type {
  AssistantChatStreamEvent,
  AssistantCitationItem,
  AssistantMessageItem,
  AssistantSessionListItem,
  AssistantToolMode,
  KbRouteStrategy,
} from '@/types/assistant'
import type { DocumentItem } from '@/api/document'
import DocumentPreviewModal from '@/components/DocumentPreviewModal.vue'
import AssistantSidebar from './components/AssistantSidebar.vue'
import AssistantMemoryPanel from './components/AssistantMemoryPanel.vue'
import AssistantTranscript from './components/AssistantTranscript.vue'
import AssistantComposer from './components/AssistantComposer.vue'
import AssistantEmpty from './components/AssistantEmpty.vue'
import type { UiAssistantMessage } from './components/AssistantMessage.vue'

const appStore = useAppStore()
const authStore = useAuthStore()

// ── Sessions ──
const sessions = ref<AssistantSessionListItem[]>([])
const activeSessionId = ref<number | null>(null)
const sessionsLoading = ref(false)
const messages = ref<UiAssistantMessage[]>([])
const loadingHistory = ref(false)
const currentMemory = ref<{ compactSummary?: string; sessionMemory?: string } | null>(null)
const hasMoreMessages = ref(false)
const loadingMore = ref(false)
const oldestMessageId = ref<number | null>(null)
const showArchived = ref(false)

const activeSession = computed(() =>
  sessions.value.find((s) => s.sessionId === activeSessionId.value) ?? null,
)

// ── Mode + KB ──
const mode = ref<AssistantToolMode>('CHAT')
const selectedGroupIds = ref<number[]>([])
const routeStrategy = ref<KbRouteStrategy>('AUTO')

const selectedGroupName = computed(() => {
  const g = appStore.visibleGroups.find((x) => x.groupId === selectedGroupIds.value[0])
  return g?.groupName ?? ''
})

// ── Streaming state ──
const streaming = ref(false)
let streamAbort: AbortController | null = null
let lastAskPayload: {
  text: string
  mode: AssistantToolMode
  groupIds: number[]
  routeStrategy: KbRouteStrategy
} | null = null

// ── Helpers ──
function localId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function toUi(m: AssistantMessageItem): UiAssistantMessage {
  return {
    localId: `srv-${m.messageId}`,
    messageId: m.messageId,
    role: m.role,
    content: m.content ?? '',
    toolMode: m.toolMode,
    groupId: m.groupId,
    groupIds: m.groupIds ?? (m.groupId !== null ? [m.groupId] : []),
    routeStrategy: m.routeStrategy,
    createdAt: m.createdAt,
    toolCallMessages: [],
    citations: [],
  }
}

// ── Data loaders ──
async function loadSessions(silent = false) {
  if (!silent) sessionsLoading.value = true
  try {
    sessions.value = await fetchAssistantSessions(showArchived.value)
  } catch (err) {
    console.error('Load sessions failed:', extractApiError(err, ''))
  } finally {
    sessionsLoading.value = false
  }
}

async function loadGroupsIfNeeded() {
  if (appStore.visibleGroups.length > 0) return
  try {
    const result = await fetchGroups()
    appStore.applyGroupQueryResult(result)
  } catch (err) {
    console.error('Load groups failed:', extractApiError(err, ''))
  }
}

async function loadSessionHistory(sessionId: number) {
  loadingHistory.value = true
  try {
    const ctx = await fetchAssistantConversationContext(sessionId)
    const msgs = ctx.recentMessages
    if (msgs && msgs.length > 0) {
      messages.value = msgs.map(toUi)
    } else {
      // 会话存在但 Context 里没消息 —— 可能刚创建，回退查询详情
      const session = await fetchAssistantSessionDetail(sessionId)
      if (session?.messages?.length) {
        messages.value = session.messages.map(toUi)
      } else {
        messages.value = []
      }
      currentMemory.value = null
      hasMoreMessages.value = false
      oldestMessageId.value = null
      loadingHistory.value = false
      return
    }
    currentMemory.value = {
      compactSummary: ctx.compactSummary,
      sessionMemory: ctx.sessionMemory,
    }
    if (ctx.recentMessages.length >= 20) {
      hasMoreMessages.value = true
      oldestMessageId.value = ctx.recentMessages[0]!.messageId
    } else {
      hasMoreMessages.value = false
      oldestMessageId.value = null
    }
  } catch {
    messages.value = []
    currentMemory.value = null
    hasMoreMessages.value = false
    oldestMessageId.value = null
  } finally {
    loadingHistory.value = false
  }
}

// ── Session actions ──
async function handleNewSession() {
  try {
    const detail = await createAssistantSession()
    const item: AssistantSessionListItem = {
      sessionId: detail.sessionId,
      title: detail.title,
      lastMessageAt: detail.lastMessageAt,
    }
    sessions.value.unshift(item)
    activeSessionId.value = detail.sessionId
    messages.value = []
  } catch (err) {
    console.error('Create session failed:', extractApiError(err, '创建会话失败'))
  }
}

async function handleSelectSession(id: number) {
  if (activeSessionId.value === id) return
  if (streaming.value) abortStream()
  activeSessionId.value = id
  hasMoreMessages.value = false
  oldestMessageId.value = null
  currentMemory.value = null
  await loadSessionHistory(id)
}

async function handleRenameSession(id: number, title: string) {
  try {
    const detail = await renameAssistantSession(id, title)
    const target = sessions.value.find((s) => s.sessionId === id)
    if (target) target.title = detail.title
  } catch (err) {
    console.error('Rename session failed:', extractApiError(err, '重命名失败'))
  }
}

async function handleDeleteSession(id: number) {
  const target = sessions.value.find((s) => s.sessionId === id)
  if (!target) return
  try {
    await ElMessageBox.confirm(
      `确定删除会话「${target.title}」吗？会话下的所有消息都会被清除。`,
      '删除会话',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteAssistantSession(id)
    sessions.value = sessions.value.filter((s) => s.sessionId !== id)
    if (activeSessionId.value === id) {
      activeSessionId.value = null
      messages.value = []
    }
  } catch (err) {
    console.error('Delete session failed:', extractApiError(err, '删除失败'))
  }
}

async function loadMoreMessages() {
  if (!oldestMessageId.value || activeSessionId.value === null) return
  loadingMore.value = true
  try {
    const moreContext = await fetchAssistantConversationContext(
      activeSessionId.value,
      20,
      oldestMessageId.value,
    )
    if (moreContext.recentMessages.length > 0) {
      messages.value = [...moreContext.recentMessages.map(toUi), ...messages.value]
      oldestMessageId.value = moreContext.recentMessages[0]!.messageId
      hasMoreMessages.value = moreContext.recentMessages.length >= 20
    } else {
      hasMoreMessages.value = false
    }
  } catch (err) {
    console.error('Load more failed:', extractApiError(err, '加载失败'))
  } finally {
    loadingMore.value = false
  }
}

async function handleArchiveSession(sessionId: number) {
  try {
    await ElMessageBox.confirm('确定归档此会话？归档后可从"已归档"列表恢复。', '归档会话')
    await archiveSession(sessionId)
    sessions.value = sessions.value.filter((s) => s.sessionId !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = null
      messages.value = []
    }
    ElMessage.success('已归档')
  } catch { /* 用户取消 */ }
}

async function handleRestoreSession(sessionId: number) {
  try {
    await restoreSession(sessionId)
    ElMessage.success('已恢复')
    await loadSessions()
  } catch (err) {
    console.error('Restore session failed:', extractApiError(err, '恢复失败'))
  }
}

// ── Ensure a session exists before asking ──
async function ensureSession(): Promise<number | null> {
  if (activeSessionId.value !== null) return activeSessionId.value
  try {
    const detail = await createAssistantSession()
    const item: AssistantSessionListItem = {
      sessionId: detail.sessionId,
      title: detail.title,
      lastMessageAt: detail.lastMessageAt,
    }
    sessions.value.unshift(item)
    activeSessionId.value = detail.sessionId
    messages.value = []
    return detail.sessionId
  } catch (err) {
    console.error('Create session failed:', extractApiError(err, '创建会话失败'))
    return null
  }
}

// ── Streaming ask ──
async function handleAsk(text: string) {
  if (!text.trim() || streaming.value) return

  // KB manual mode requires at least one selected group
  if (
    mode.value === 'KB_SEARCH' &&
    routeStrategy.value === 'SELECTED' &&
    selectedGroupIds.value.length === 0
  ) {
    return
  }
  if (!authStore.accessToken) {
    console.warn('No access token; cannot stream.')
    return
  }

  const sessionId = await ensureSession()
  if (sessionId === null) return

  const isKb = mode.value === 'KB_SEARCH'
  const groupIdsForPayload = isKb ? [...selectedGroupIds.value] : []
  const routeStrategyForPayload: KbRouteStrategy = isKb ? routeStrategy.value : 'AUTO'
  const groupIdForPayload = groupIdsForPayload[0] ?? null

  lastAskPayload = {
    text,
    mode: mode.value,
    groupIds: groupIdsForPayload,
    routeStrategy: routeStrategyForPayload,
  }

  // Push user bubble (optimistic)
  const nowIso = new Date().toISOString()
  const userMsg: UiAssistantMessage = {
    localId: localId(),
    messageId: null,
    role: 'USER',
    content: text,
    toolMode: mode.value,
    groupId: groupIdForPayload,
    groupIds: groupIdsForPayload,
    routeStrategy: routeStrategyForPayload,
    createdAt: nowIso,
    toolCallMessages: [],
  }
  messages.value.push(userMsg)

  // Push assistant placeholder
  const assistantMsg: UiAssistantMessage = {
    localId: localId(),
    messageId: null,
    role: 'ASSISTANT',
    content: '',
    toolMode: mode.value,
    groupId: groupIdForPayload,
    groupIds: groupIdsForPayload,
    routeStrategy: routeStrategyForPayload,
    createdAt: new Date().toISOString(),
    streaming: true,
    citations: [],
    toolCallMessages: [],
  }
  messages.value.push(assistantMsg)
  // 关键：push 后 Vue 会把元素包一层 Proxy，只有通过 Proxy 改属性才会触发响应式。
  // 直接保留 push 前的原始对象会"写到原始内存但 Vue 不知道"，打字机效果失效。
  const target = messages.value[messages.value.length - 1]!

  streaming.value = true
  streamAbort = new AbortController()

  try {
    await streamAssistantMessage(
      {
        sessionId,
        message: text,
        toolMode: mode.value,
        groupId: groupIdForPayload,
        groupIds: groupIdsForPayload,
        routeStrategy: routeStrategyForPayload,
      },
      authStore.accessToken,
      {
        signal: streamAbort.signal,
        onEvent: (ev) => onStreamEvent(ev, target),
      },
    )
  } catch (err) {
    if ((err as { name?: string })?.name === 'AbortError') {
      // User aborted — preserve whatever content we already have
      target.streaming = false
      if (!target.content) {
        target.content = '_(已中断)_'
      } else {
        target.content += '\n\n_(已中断)_'
      }
    } else {
      target.streaming = false
      target.failed = true
      target.failureMessage = extractApiError(err, '流式请求失败')
    }
  } finally {
    streaming.value = false
    streamAbort = null
    // Bump the session in the local list (move to top, update lastMessageAt)
    bumpSession(sessionId)
    // Silently reload sessions so auto-rename takes effect
    loadSessions(true)
  }
}

function onStreamEvent(ev: AssistantChatStreamEvent, target: UiAssistantMessage) {
  switch (ev.event) {
    case 'start':
      target.streaming = true
      break
    case 'delta':
      if (ev.delta) target.content += ev.delta
      break
    case 'done':
      if (ev.reply != null && ev.reply.length > 0) {
        // Prefer authoritative final reply
        target.content = ev.reply
      }
      if (ev.messageId != null) {
        target.messageId = ev.messageId
        target.localId = `srv-${ev.messageId}`
      }
      target.citations = ev.citations ?? []
      target.streaming = false
      break
    case 'tool_call':
      target.toolCallMessages.push({
        toolName: ev.toolName ?? 'unknown',
        stage: ev.toolStage ?? 'UNKNOWN',
      })
      break
    case 'error':
      target.streaming = false
      target.failed = true
      target.failureMessage = ev.error ?? '生成失败'
      break
  }
}

function abortStream() {
  if (streamAbort) {
    streamAbort.abort()
  }
}

function bumpSession(sessionId: number) {
  const idx = sessions.value.findIndex((s) => s.sessionId === sessionId)
  if (idx < 0) return
  const [item] = sessions.value.splice(idx, 1)
  if (item) {
    item.lastMessageAt = new Date().toISOString()
    sessions.value.unshift(item)
  }
}

async function handleRetry() {
  if (!lastAskPayload || streaming.value) return
  // Drop the trailing failed assistant message; keep user message
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'ASSISTANT' && last.failed) {
    messages.value.pop()
  }
  // Restore mode + group (in case user changed them before retry)
  const previous = lastAskPayload
  mode.value = previous.mode
  if (previous.mode === 'KB_SEARCH') {
    routeStrategy.value = previous.routeStrategy
    selectedGroupIds.value = [...previous.groupIds]
  }
  await handleAsk(previous.text)
}

const composerRef = ref<InstanceType<typeof AssistantComposer> | null>(null)

function handleStarterPick(prompt: string, starterMode: AssistantToolMode) {
  mode.value = starterMode
  composerRef.value?.setText(prompt)
}

// ── Citation preview bridge ──
const previewVisible = ref(false)
const previewDocument = ref<DocumentItem | null>(null)

function openCitation(c: AssistantCitationItem) {
  if (c.documentId === null) return
  // Citation carries the source group id in multi-KB mode; fallback to current selection
  const groupId = c.groupId ?? selectedGroupIds.value[0] ?? null
  if (groupId === null) return
  const idx = c.fileName.lastIndexOf('.')
  const ext = idx >= 0 ? c.fileName.slice(idx + 1).toLowerCase() : null
  previewDocument.value = {
    documentId: c.documentId,
    groupId,
    fileName: c.fileName,
    fileExt: ext,
    contentType: null,
    fileSize: 0,
    status: 'READY',
    failureReason: null,
    uploadedAt: '',
    uploaderUserId: null,
    uploaderDisplayName: null,
    uploaderUserCode: null,
    previewText: c.snippet,
  }
  previewVisible.value = true
}

// ── Init ──
watch(showArchived, () => {
  sessions.value = []
  activeSessionId.value = null
  messages.value = []
  loadSessions()
})

onMounted(async () => {
  await Promise.all([loadSessions(), loadGroupsIfNeeded()])
  if (sessions.value.length > 0 && activeSessionId.value === null) {
    const firstSession = sessions.value[0]
    if (firstSession) {
      activeSessionId.value = firstSession.sessionId
      await loadSessionHistory(firstSession.sessionId)
    }
  }
  if (selectedGroupIds.value.length === 0) {
    const initial = appStore.currentGroupId ?? appStore.visibleGroups[0]?.groupId ?? null
    if (initial !== null) {
      selectedGroupIds.value = [initial]
    }
  }
})
</script>

<template>
  <div class="asst-page">
    <AssistantSidebar
      :sessions="sessions"
      :active-session-id="activeSessionId"
      :loading="sessionsLoading"
      :show-archived="showArchived"
      @update:show-archived="(v) => (showArchived = v)"
      @new-session="handleNewSession"
      @select-session="handleSelectSession"
      @rename-session="handleRenameSession"
      @delete-session="handleDeleteSession"
      @archive="handleArchiveSession"
      @restore="handleRestoreSession"
      @refresh="() => loadSessions()"
    />

    <main class="asst-page__main">
      <template v-if="activeSessionId !== null && (messages.length > 0 || loadingHistory)">
        <div class="asst-page__memory-bar">
          <div v-if="hasMoreMessages" class="load-more-wrap">
            <el-button :loading="loadingMore" @click="loadMoreMessages" size="small" text>
              加载更早消息
            </el-button>
          </div>
          <AssistantMemoryPanel
            :compact-summary="currentMemory?.compactSummary ?? null"
            :session-memory="currentMemory?.sessionMemory ?? null"
          />
        </div>
        <AssistantTranscript
          :messages="messages"
          :session-id="activeSessionId"
          :session-title="activeSession?.title ?? ''"
          :mode="mode"
          :group-name="selectedGroupName"
          :groups="appStore.visibleGroups"
          :loading-history="loadingHistory"
          @update:mode="(m) => (mode = m)"
          @inspect-citation="openCitation"
          @retry="handleRetry"
        />
      </template>
      <template v-else>
        <AssistantEmpty
          :mode="mode"
          :has-kb="selectedGroupIds.length > 0"
          @pick="(prompt, starterMode) => handleStarterPick(prompt, starterMode)"
        />
      </template>

      <AssistantComposer
        ref="composerRef"
        v-model:mode="mode"
        v-model:selected-group-ids="selectedGroupIds"
        v-model:route-strategy="routeStrategy"
        :disabled="false"
        :streaming="streaming"
        :groups="appStore.visibleGroups"
        @submit="handleAsk"
        @abort="abortStream"
      />
    </main>

    <DocumentPreviewModal
      :visible="previewVisible"
      :document="previewDocument"
      @update:visible="(v: boolean) => (previewVisible = v)"
    />
  </div>
</template>

<style scoped>
.asst-page {
  display: flex;
  height: 100%;
  min-height: 600px;
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.03);
  position: relative;
}

.asst-page__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
}

.asst-page__memory-bar {
  max-width: 900px;
  margin: 0 auto;
  width: 100%;
  padding: 8px 0 0;
}

.load-more-wrap {
  text-align: center;
  padding: 6px 0;
}

@media (max-width: 900px) {
  .asst-page {
    flex-direction: column;
    height: auto;
    min-height: 100vh;
    border-radius: 0;
  }
}
</style>
