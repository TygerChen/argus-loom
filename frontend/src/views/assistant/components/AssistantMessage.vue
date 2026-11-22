<script setup lang="ts">
/**
 * 助手对话的单条消息。与 qa 的 QaMessage 相比多了三样：
 * toolCallMessages（Agent 调了哪些工具的折叠展示——"我查了知识库"这种
 * 过程透明化）、streaming（打字机进行中光标）、failed（失败态与原因展示，
 * 失败也要好好说话，01 篇的产品原则）。
 * 内容同样强制 renderSafeMarkdown 消毒。
 */
import { computed, ref, watch, nextTick } from 'vue'
import type {
  AssistantCitationItem,
  AssistantMessageRole,
  AssistantToolMode,
  PendingProposal,
} from '@/types/assistant'
import AssistantCitationBar from './AssistantCitationBar.vue'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'
import { enhanceCodeBlocks, normalizeFences } from '@/utils/codeBlock'

export interface UiAssistantMessage {
  localId: string
  messageId: number | null
  role: AssistantMessageRole
  content: string
  toolMode: AssistantToolMode | null
  groupId: number | null
  groupIds?: number[]
  routeStrategy?: 'SELECTED' | 'AUTO'
  createdAt: string
  streaming?: boolean
  failed?: boolean
  failureMessage?: string | null
  citations?: AssistantCitationItem[]
  toolCallMessages: UiToolCallMessage[]
  pendingProposal?: PendingProposal | null
}

export interface UiToolCallMessage {
  toolName: string
  stage: string
}

const props = defineProps<{
  message: UiAssistantMessage
  groups?: Array<{ groupId: number; groupName: string }>
}>()

const emit = defineEmits<{
  'inspect-citation': [citation: AssistantCitationItem]
  retry: []
  'confirm-proposal': [proposal: PendingProposal]
  'cancel-proposal': [proposal: PendingProposal]
}>()

// ── 写提案确认卡片（第十一步） ──
// 提案状态：后端为源头（REGISTERED/EXECUTED/CANCELLED），点击后前端先乐观置状态
type ProposalUiState = 'IDLE' | 'CONFIRMING' | 'CONFIRMED' | 'CANCELLED' | 'FAILED'
const proposalUiState = ref<ProposalUiState>('IDLE')
const proposalError = ref('')

const proposal = computed(() => props.message.pendingProposal ?? null)
const proposalExpired = computed(() => {
  if (!proposal.value) return false
  return Date.now() > proposal.value.expiresAt
})
const proposalActionable = computed(
  () => proposal.value && proposal.value.status === 'REGISTERED' && !proposalExpired.value && proposalUiState.value === 'IDLE',
)

function onConfirmProposal() {
  if (!proposal.value || !proposalActionable.value) return
  proposalUiState.value = 'CONFIRMING'
  proposalError.value = ''
  emit('confirm-proposal', proposal.value)
}

function onCancelProposal() {
  if (!proposal.value || !proposalActionable.value) return
  proposalUiState.value = 'CANCELLED'
  emit('cancel-proposal', proposal.value)
}

/** 由父组件回写结果（成功/失败），驱动卡片终态展示。 */
function setProposalResult(state: ProposalUiState, error?: string) {
  proposalUiState.value = state
  if (error) proposalError.value = error
}

defineExpose({ setProposalResult })

const rendered = computed(() => {
  if (props.message.role === 'USER' || props.message.role === 'TOOL') return ''
  // 先规整围栏再消毒渲染：LLM 输出的 ``` 常不处于行首（见 normalizeFences）
  return renderSafeMarkdown(normalizeFences(props.message.content || ''))
})

const toolPayload = computed(() => {
  if (props.message.role !== 'TOOL') return ''
  return props.message.content.trim()
})

function formatTime(iso: string): string {
  if (!iso) {
    const d = new Date()
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  const d = new Date(iso)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

// ── 代码块增强：语言标签 + 复制按钮（流式渲染下反复调用安全，见 codeBlock.ts） ──
const mdRef = ref<HTMLElement | null>(null)
const copiedAll = ref(false)

watch(
  () => props.message.content,
  async () => {
    await nextTick()
    enhanceCodeBlocks(mdRef.value)
  },
  { immediate: true },
)

async function copyAll() {
  try {
    await navigator.clipboard.writeText(props.message.content)
  } catch {
    // 剪贴板不可用时静默失败：不打断阅读流
    return
  }
  copiedAll.value = true
  window.setTimeout(() => {
    copiedAll.value = false
  }, 1600)
}
</script>

<template>
  <article class="amsg" :class="`amsg--${message.role.toLowerCase()}`">
    <!-- Avatar -->
    <div class="amsg__avatar">
      <template v-if="message.role === 'USER'">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
          <circle cx="12" cy="7" r="4" />
        </svg>
      </template>
      <template v-else-if="message.role === 'ASSISTANT'">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
        </svg>
      </template>
      <template v-else>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
        </svg>
      </template>
    </div>

    <!-- Body -->
    <div class="amsg__body">
      <header class="amsg__head">
        <span class="amsg__role">
          {{ message.role === 'USER' ? 'You' : message.role === 'ASSISTANT' ? 'Argus' : 'Tool' }}
        </span>
        <span v-if="message.toolMode" class="amsg__mode-tag" :class="`amsg__mode-tag--${message.toolMode.toLowerCase()}`">
          {{ message.toolMode === 'CHAT' ? 'chat' : 'kb_search' }}
        </span>
        <span class="amsg__time">{{ formatTime(message.createdAt) }}</span>
        <button
          v-if="message.role === 'ASSISTANT' && message.content && !message.streaming"
          class="amsg__copy"
          type="button"
          :title="copiedAll ? '已复制' : '复制全文'"
          @click="copyAll"
        >
          <svg v-if="!copiedAll" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="9" y="9" width="13" height="13" rx="2" />
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
          </svg>
          <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="20 6 9 17 4 12" />
          </svg>
        </button>
      </header>

      <!-- USER -->
      <div v-if="message.role === 'USER'" class="amsg__user-text">
        {{ message.content }}
      </div>

      <!-- TOOL (collapsed code-style) -->
      <details v-else-if="message.role === 'TOOL'" class="amsg__tool">
        <summary>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="16 18 22 12 16 6" />
            <polyline points="8 6 2 12 8 18" />
          </svg>
          <span>tool output</span>
        </summary>
        <pre class="amsg__tool-body"><code>{{ toolPayload || '(empty)' }}</code></pre>
      </details>

      <!-- ASSISTANT failed -->
      <div v-else-if="message.failed" class="amsg__fail">
        <div class="amsg__fail-head">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <span>生成失败</span>
        </div>
        <p class="amsg__fail-text">
          {{ message.failureMessage || '请求过程中出现异常，请重试或换一种提问方式。' }}
        </p>
        <button class="amsg__fail-retry" type="button" @click="emit('retry')">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="23 4 23 10 17 10" />
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
          </svg>
          <span>重试</span>
        </button>
      </div>

      <!-- 工具调用可视化 -->
      <div v-if="message.role === 'ASSISTANT' && message.toolCallMessages.length > 0" class="amsg__tool-calls">
        <div v-for="(tc, idx) in message.toolCallMessages" :key="idx" class="amsg__tool-call-item">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>
          <span class="amsg__tool-call-name">{{ tc.toolName }}</span>
          <span class="amsg__tool-call-stage"> {{ tc.stage === 'START' ? '⏳ 调用中' : '✅ 完成' }}</span>
        </div>
      </div>
      <!-- ASSISTANT streaming or done -->
      <template v-else>
        <div v-if="message.streaming && !message.content" class="amsg__thinking">
          <span class="amsg__thinking-dot" />
          <span class="amsg__thinking-dot" />
          <span class="amsg__thinking-dot" />
          <span class="amsg__thinking-label">Agent 正在思考…</span>
        </div>

        <div v-else class="amsg__md" :class="{ 'is-streaming': message.streaming }">
          <div ref="mdRef" class="amsg__md-content" v-html="rendered" />
          <span v-if="message.streaming" class="amsg__cursor" />
        </div>

        <AssistantCitationBar
          v-if="!message.streaming && message.citations && message.citations.length > 0"
          :citations="message.citations"
          :groups="groups ?? []"
          @inspect="(c) => emit('inspect-citation', c)"
        />

        <!-- 写提案确认卡片（第十一步确认门） -->
        <div v-if="!message.streaming && proposal" class="amsg__proposal">
          <div class="amsg__proposal-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <path d="M8 10h8M8 14h5" />
            </svg>
          </div>
          <div class="amsg__proposal-body">
            <p class="amsg__proposal-summary">{{ proposal.summary }}</p>
            <p v-if="proposalExpired && proposal.status === 'REGISTERED'" class="amsg__proposal-hint is-warn">
              提案已过期，请重新发起
            </p>
            <p v-else-if="proposalUiState === 'CONFIRMING'" class="amsg__proposal-hint">正在执行…</p>
            <p v-else-if="proposalUiState === 'CONFIRMED'" class="amsg__proposal-hint is-ok">✅ 已执行</p>
            <p v-else-if="proposalUiState === 'CANCELLED' || proposal.status === 'CANCELLED'" class="amsg__proposal-hint">已取消</p>
            <p v-else-if="proposal.status === 'EXECUTED'" class="amsg__proposal-hint is-ok">✅ 已执行</p>
            <p v-else-if="proposalUiState === 'FAILED'" class="amsg__proposal-hint is-warn">{{ proposalError || '执行失败，请重试' }}</p>

            <div v-if="proposalActionable" class="amsg__proposal-actions">
              <button class="amsg__proposal-btn amsg__proposal-btn--confirm" type="button" @click="onConfirmProposal">
                确认执行
              </button>
              <button class="amsg__proposal-btn" type="button" @click="onCancelProposal">取消</button>
            </div>
          </div>
        </div>
      </template>
    </div>
  </article>
</template>

<style scoped>
.amsg {
  display: flex;
  gap: 16px;
  padding: 22px 32px 22px 28px;
  animation: amsg-in 0.35s cubic-bezier(0.16, 1, 0.3, 1);
  color: var(--text-primary);
}

@keyframes amsg-in {
  from { opacity: 0; transform: translateY(6px); }
  to   { opacity: 1; transform: translateY(0); }
}

.amsg--user {
  background: transparent;
  /* 聊天式布局：用户消息整体靠右，头像在右侧 */
  flex-direction: row-reverse;
}

.amsg--user .amsg__body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex: 0 1 auto;
  max-width: min(70%, 640px);
  margin-left: auto;
}

.amsg--user .amsg__head {
  flex-direction: row-reverse;
}

.amsg--user .amsg__user-text {
  padding: 10px 14px;
  border-radius: 12px 12px 2px 12px;
  background: linear-gradient(135deg, rgba(74, 144, 217, 0.1), rgba(74, 144, 217, 0.05));
  border: 1px solid rgba(74, 144, 217, 0.18);
}

.amsg--assistant {
  background: linear-gradient(180deg, color-mix(in srgb, var(--surface-subtle) 60%, transparent), color-mix(in srgb, var(--surface-subtle) 30%, transparent));
  border-top: 1px solid var(--border-subtle);
  border-bottom: 1px solid var(--border-subtle);
}

.amsg--tool {
  padding: 6px 32px 6px 28px;
  background: transparent;
  opacity: 0.85;
}

/* Avatar */
.amsg__avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.amsg__avatar svg {
  width: 16px;
  height: 16px;
}

.amsg--user .amsg__avatar {
  background: linear-gradient(135deg, var(--text-secondary), var(--surface-footer));
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.2);
}

.amsg--assistant .amsg__avatar {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  box-shadow: 0 2px 8px rgba(74, 144, 217, 0.25);
}

.amsg--tool .amsg__avatar {
  background: var(--surface-muted);
  color: var(--text-muted);
}

/* Body */
.amsg__body {
  flex: 1;
  min-width: 0;
}

.amsg__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}

.amsg__role {
  font-family: 'Poppins', sans-serif;
  font-size: 0.78rem;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.02em;
}

.amsg__mode-tag {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.62rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  padding: 1px 7px;
  border-radius: 4px;
  text-transform: uppercase;
}

.amsg__mode-tag--chat {
  color: var(--brand-primary);
  background: rgba(74, 144, 217, 0.1);
}

.amsg__mode-tag--kb_search {
  color: var(--brand-accent-dark);
  background: rgba(92, 201, 193, 0.12);
}

.amsg__time {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.7rem;
  color: var(--text-muted);
  margin-left: auto;
}

/* 复制全文：hover 消息行时浮现，避免常驻噪音 */
.amsg__copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  opacity: 0;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.amsg:hover .amsg__copy,
.amsg__copy:focus-visible {
  opacity: 1;
}

.amsg__copy:hover {
  color: var(--brand-primary);
  background: var(--surface-accent);
  border-color: rgba(74, 144, 217, 0.2);
}

/* USER content */
.amsg__user-text {
  font-size: 0.95rem;
  line-height: 1.65;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

/* TOOL */
.amsg__tool {
  margin: 2px 0;
  background: var(--surface-subtle);
  border: 1px solid var(--border-default);
  border-radius: 8px;
  overflow: hidden;
}

.amsg__tool summary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.72rem;
  color: var(--text-secondary);
  cursor: pointer;
  user-select: none;
  list-style: none;
}

.amsg__tool summary::-webkit-details-marker {
  display: none;
}

.amsg__tool summary svg {
  color: var(--text-muted);
}

.amsg__tool-body {
  margin-top: 4px;
  padding: 8px 12px;
  background: var(--surface-muted);
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
  white-space: pre-wrap;
}

.amsg__tool-calls {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0;
}
.amsg__tool-call-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: color-mix(in srgb, var(--brand-primary) 8%, transparent);
  border-radius: 6px;
  font-size: 13px;
  color: var(--brand-primary);
}

/* Thinking */
.amsg__thinking {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: 100px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.amsg__thinking-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-primary);
  animation: amsg-think 1.3s ease-in-out infinite;
}

.amsg__thinking-dot:nth-child(2) { animation-delay: 0.15s; }
.amsg__thinking-dot:nth-child(3) { animation-delay: 0.3s; }

@keyframes amsg-think {
  0%, 80%, 100% { transform: scale(0.5); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.amsg__thinking-label {
  margin-left: 6px;
  font-size: 0.8rem;
  color: var(--text-secondary);
  letter-spacing: 0.01em;
}

/* Fail */
.amsg__fail {
  padding: 14px 16px;
  background: rgba(239, 68, 68, 0.04);
  border: 1px solid rgba(239, 68, 68, 0.15);
  border-radius: 10px;
}

.amsg__fail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-family: 'Poppins', sans-serif;
  font-size: 0.85rem;
  font-weight: 700;
  color: #dc2626;
}

.amsg__fail-text {
  margin: 0 0 8px;
  font-size: 0.87rem;
  color: var(--text-secondary);
  line-height: 1.55;
}

.amsg__fail-retry {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 12px;
  font-family: inherit;
  font-size: 0.78rem;
  font-weight: 600;
  color: #dc2626;
  background: var(--surface-white);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 100px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.amsg__fail-retry:hover {
  background: rgba(239, 68, 68, 0.08);
  border-color: #dc2626;
}

/* Markdown */
.amsg__md {
  position: relative;
  font-size: 0.93rem;
  line-height: 1.7;
  color: var(--text-primary);
  word-break: break-word;
}

.amsg__md-content {
  display: inline;
}

/* Streaming cursor */
.amsg__cursor {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 1px;
  background: var(--brand-primary);
  vertical-align: middle;
  animation: amsg-cursor 1.1s ease-in-out infinite;
  border-radius: 1px;
  box-shadow: 0 0 6px rgba(74, 144, 217, 0.5);
}

@keyframes amsg-cursor {
  0%, 45% { opacity: 1; }
  55%, 100% { opacity: 0; }
}

.amsg__md :deep(h1),
.amsg__md :deep(h2),
.amsg__md :deep(h3),
.amsg__md :deep(h4) {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.3;
  margin: 16px 0 8px;
}

.amsg__md :deep(h1) { font-size: 1.4rem; }
.amsg__md :deep(h2) { font-size: 1.2rem; }
.amsg__md :deep(h3) { font-size: 1.05rem; }
.amsg__md :deep(h4) { font-size: 0.95rem; }

.amsg__md :deep(p) { margin: 10px 0; }

.amsg__md :deep(ul),
.amsg__md :deep(ol) {
  margin: 10px 0;
  padding-left: 24px;
}

.amsg__md :deep(ul) { list-style: disc; }
.amsg__md :deep(ol) { list-style: decimal; }

.amsg__md :deep(li) { margin: 4px 0; padding-left: 3px; }

.amsg__md :deep(li::marker) {
  color: var(--brand-primary);
}

.amsg__md :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.84em;
  padding: 1px 6px;
  background: color-mix(in srgb, var(--text-primary) 6%, transparent);
  border-radius: 4px;
  color: var(--brand-primary-dark);
}

.amsg__md :deep(pre) {
  margin: 0;
  padding: 14px 16px;
  background: var(--surface-footer);
  overflow-x: auto;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.82rem;
  line-height: 1.6;
  color: #e2e8f0;
  border: none;
}

.amsg__md :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
}

.amsg__md :deep(blockquote) {
  margin: 12px 0;
  padding: 8px 16px;
  border-left: 3px solid var(--brand-primary);
  background: rgba(74, 144, 217, 0.06);
  border-radius: 0 6px 6px 0;
  color: var(--text-secondary);
  font-style: italic;
}

.amsg__md :deep(a) {
  color: var(--brand-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
  text-decoration-thickness: 1px;
  transition: color 0.15s ease;
}

.amsg__md :deep(a:hover) {
  color: var(--brand-primary-dark);
}

.amsg__md :deep(strong) {
  font-weight: 700;
  color: var(--text-primary);
}

.amsg__md :deep(hr) {
  margin: 18px 0;
  border: none;
  border-top: 1px dashed var(--border-default);
}

.amsg__md :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  font-size: 0.85rem;
}

.amsg__md :deep(th),
.amsg__md :deep(td) {
  padding: 8px 12px;
  border: 1px solid var(--border-default);
  text-align: left;
}

.amsg__md :deep(th) {
  background: var(--surface-subtle);
  font-weight: 700;
  color: var(--text-primary);
}

.amsg__md :deep(img) {
  max-width: 100%;
  border-radius: 8px;
  margin: 12px 0;
}

/* 写提案确认卡片（第十一步） */
.amsg__proposal {
  display: flex;
  gap: 12px;
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid rgba(74, 144, 217, 0.25);
  border-radius: 12px;
  background: rgba(74, 144, 217, 0.04);
}
.amsg__proposal-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--brand-primary);
  color: #fff;
}
.amsg__proposal-body {
  flex: 1;
  min-width: 0;
}
.amsg__proposal-summary {
  margin: 0 0 6px;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.5;
}
.amsg__proposal-hint {
  margin: 0 0 8px;
  font-size: 0.78rem;
  color: var(--text-muted);
}
.amsg__proposal-hint.is-ok {
  color: #16a34a;
}
.amsg__proposal-hint.is-warn {
  color: #dc2626;
}
.amsg__proposal-actions {
  display: flex;
  gap: 8px;
}
.amsg__proposal-btn {
  padding: 6px 16px;
  font-family: inherit;
  font-size: 0.82rem;
  font-weight: 600;
  border-radius: 8px;
  border: 1px solid var(--border-default);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.amsg__proposal-btn:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}
.amsg__proposal-btn--confirm {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: #fff;
}
.amsg__proposal-btn--confirm:hover {
  background: var(--brand-primary-dark);
  color: #fff;
}

@media (max-width: 720px) {
  .amsg {
    padding: 18px 18px 18px 16px;
    gap: 12px;
  }
}
</style>
