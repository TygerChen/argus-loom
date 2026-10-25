<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import type { AssistantToolMode, KbRouteStrategy } from '@/types/assistant'
import type { VisibleGroup } from '@/stores/app'
import ModeSwitcher from './ModeSwitcher.vue'

const props = defineProps<{
  disabled: boolean
  streaming: boolean
  mode: AssistantToolMode
  groups: VisibleGroup[]
  selectedGroupIds: number[]
  routeStrategy: KbRouteStrategy
}>()

const emit = defineEmits<{
  submit: [text: string]
  abort: []
  'update:mode': [mode: AssistantToolMode]
  'update:selectedGroupIds': [val: number[]]
  'update:routeStrategy': [val: KbRouteStrategy]
}>()

const text = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

const isKb = computed(() => props.mode === 'KB_SEARCH')
const isManual = computed(() => props.routeStrategy === 'SELECTED')
const kbBlocked = computed(() => isKb.value && isManual.value && props.selectedGroupIds.length === 0)

function setRoute(v: KbRouteStrategy) {
  if (props.streaming || props.disabled) return
  emit('update:routeStrategy', v)
}

function toggleGroup(groupId: number) {
  if (props.streaming || props.disabled) return
  const next = props.selectedGroupIds.includes(groupId)
    ? props.selectedGroupIds.filter((id) => id !== groupId)
    : [...props.selectedGroupIds, groupId]
  emit('update:selectedGroupIds', next)
}

const placeholder = computed(() => {
  if (props.disabled) return '请先在左侧选择或新建一个会话'
  if (kbBlocked.value) return '请先选择要检索的知识库群组'
  if (props.streaming) return '正在接收回答…（按 Esc 中断）'
  return props.mode === 'CHAT'
    ? '和 Argus 聊点什么？按 Enter 发送 / Shift + Enter 换行'
    : props.routeStrategy === 'AUTO'
      ? '在知识库中提问，将自动路由到最相关的知识库'
      : '在选定的知识库中提问，答案会附带证据引用'
})

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

watch(text, () => nextTick(autoResize))

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
    return
  }
  if (e.key === 'Escape' && props.streaming) {
    e.preventDefault()
    emit('abort')
  }
}

function submit() {
  const v = text.value.trim()
  if (!v || props.disabled || props.streaming || kbBlocked.value) return
  emit('submit', v)
  text.value = ''
  nextTick(autoResize)
}

function focus() {
  textareaRef.value?.focus()
}

function setText(value: string) {
  text.value = value
  nextTick(() => {
    autoResize()
    textareaRef.value?.focus()
  })
}

defineExpose({ focus, setText })
</script>

<template>
  <div class="acx">
    <div class="acx__wrap">
      <div class="acx__shell" :class="{ 'is-disabled': disabled, 'is-kb': isKb, 'is-streaming': streaming }">
        <!-- Meta bar -->
        <div class="acx__meta">
          <ModeSwitcher :mode="mode" @update:mode="(m) => emit('update:mode', m)" />

          <!-- KB picker + route strategy (only in KB_SEARCH) -->
          <div v-if="isKb" class="acx__kb">
            <svg class="acx__kb-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <ellipse cx="12" cy="5" rx="9" ry="3" />
              <path d="M3 5v7c0 1.7 4 3 9 3s9-1.3 9-3V5" />
              <path d="M3 12v7c0 1.7 4 3 9 3s9-1.3 9-3v-7" />
            </svg>
            <div class="acx__route" role="group">
              <button
                type="button"
                class="acx__route-btn"
                :class="{ 'is-active': routeStrategy === 'AUTO' }"
                @click="setRoute('AUTO')"
              >
                自动
              </button>
              <button
                type="button"
                class="acx__route-btn"
                :class="{ 'is-active': routeStrategy === 'SELECTED' }"
                @click="setRoute('SELECTED')"
              >
                手动
              </button>
            </div>
            <span v-if="routeStrategy === 'AUTO'" class="acx__kb-count">
              {{ groups.length }} 个可用
            </span>
            <span v-else class="acx__kb-count" :class="{ 'is-empty': selectedGroupIds.length === 0 }">
              已选 {{ selectedGroupIds.length }}
            </span>
          </div>

          <div class="acx__spacer" />

          <!-- Status indicator -->
          <div v-if="streaming" class="acx__status">
            <span class="acx__status-dot" />
            <span>streaming</span>
          </div>
        </div>

        <!-- KB multi-select chips (manual mode) -->
        <div v-if="isKb && isManual && groups.length > 0" class="acx__chips">
          <button
            v-for="g in groups"
            :key="g.groupId"
            type="button"
            class="acx__chip"
            :class="{ 'is-active': selectedGroupIds.includes(g.groupId) }"
            @click="toggleGroup(g.groupId)"
          >
            <svg v-if="selectedGroupIds.includes(g.groupId)" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <svg v-else width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="9" />
            </svg>
            <span class="acx__chip-name">{{ g.groupName }}</span>
            <span class="acx__chip-role">{{ g.relation === 'OWNER' ? '管理员' : '成员' }}</span>
          </button>
        </div>

        <!-- Textarea -->
        <textarea
          ref="textareaRef"
          v-model="text"
          :disabled="disabled"
          class="acx__input"
          rows="1"
          :placeholder="placeholder"
          @keydown="onKeydown"
        />

        <!-- Action bar -->
        <div class="acx__bar">
          <div class="acx__hints">
            <kbd>Enter</kbd><span>发送</span>
            <span class="acx__sep">·</span>
            <kbd>Shift + Enter</kbd><span>换行</span>
            <template v-if="streaming">
              <span class="acx__sep">·</span>
              <kbd>Esc</kbd><span>中断</span>
            </template>
          </div>

          <button
            v-if="streaming"
            class="acx__btn acx__btn--abort"
            type="button"
            @click="emit('abort')"
          >
            <span class="acx__btn-square" />
            <span>停止</span>
          </button>
          <button
            v-else
            class="acx__btn acx__btn--send"
            type="button"
            :disabled="!text.trim() || disabled || kbBlocked"
            @click="submit"
          >
            <span>发送</span>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <line x1="5" y1="12" x2="19" y2="12" />
              <polyline points="12 5 19 12 12 19" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.acx {
  flex-shrink: 0;
  padding: 18px 24px 22px;
  background: linear-gradient(to top, #fff 70%, rgba(255, 255, 255, 0));
  position: relative;
  z-index: 4;
}

.acx__wrap {
  max-width: 900px;
  margin: 0 auto;
}

/* Shell */
.acx__shell {
  background: #fff;
  border: 1.5px solid var(--border-default);
  border-radius: 16px;
  padding: 12px 14px 10px;
  box-shadow:
    0 4px 14px rgba(0, 0, 0, 0.04),
    0 1px 2px rgba(0, 0, 0, 0.03);
  transition: all 0.25s ease;
}

.acx__shell:focus-within {
  border-color: var(--brand-primary);
  box-shadow:
    0 0 0 4px rgba(74, 144, 217, 0.08),
    0 8px 20px rgba(74, 144, 217, 0.08);
}

.acx__shell.is-kb {
  border-color: rgba(92, 201, 193, 0.35);
}

.acx__shell.is-kb:focus-within {
  border-color: var(--brand-accent-dark);
  box-shadow:
    0 0 0 4px rgba(92, 201, 193, 0.1),
    0 8px 20px rgba(92, 201, 193, 0.1);
}

.acx__shell.is-streaming {
  border-color: var(--brand-accent);
  box-shadow:
    0 0 0 3px rgba(92, 201, 193, 0.15),
    0 8px 20px rgba(0, 0, 0, 0.05);
  animation: acx-breathing 2.5s ease-in-out infinite;
}

@keyframes acx-breathing {
  0%, 100% { box-shadow: 0 0 0 3px rgba(92, 201, 193, 0.15), 0 8px 20px rgba(0, 0, 0, 0.05); }
  50%     { box-shadow: 0 0 0 5px rgba(92, 201, 193, 0.22), 0 8px 20px rgba(0, 0, 0, 0.05); }
}

.acx__shell.is-disabled {
  opacity: 0.7;
  background: var(--surface-subtle);
}

/* Meta */
.acx__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--border-subtle);
  flex-wrap: wrap;
}

.acx__kb {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px 4px 8px;
  background: rgba(92, 201, 193, 0.08);
  border: 1px solid rgba(92, 201, 193, 0.25);
  border-radius: 100px;
  animation: acx-kb-in 0.25s ease-out;
}

@keyframes acx-kb-in {
  from { opacity: 0; transform: translateX(-4px); }
  to   { opacity: 1; transform: translateX(0); }
}

.acx__kb-icon {
  color: var(--brand-accent-dark);
  flex-shrink: 0;
}

.acx__kb-select {
  font-family: inherit;
  font-size: 0.78rem;
  font-weight: 500;
  color: var(--text-primary);
  background: transparent;
  border: none;
  outline: none;
  cursor: pointer;
  max-width: 200px;
  padding-right: 4px;
}

.acx__kb-select:disabled {
  cursor: not-allowed;
}

.acx__kb-select.is-empty {
  color: #b45309;
}

.acx__route {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  background: rgba(15, 23, 42, 0.05);
  border-radius: 100px;
}

.acx__route-btn {
  padding: 3px 10px;
  font-family: inherit;
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-radius: 100px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.acx__route-btn.is-active {
  color: #fff;
  background: var(--brand-accent-dark);
  box-shadow: 0 2px 8px rgba(92, 201, 193, 0.35);
}

.acx__kb-count {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--brand-accent-dark);
  white-space: nowrap;
}

.acx__kb-count.is-empty {
  color: #b45309;
}

.acx__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 2px 0;
}

.acx__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 200px;
  padding: 4px 10px;
  font-family: inherit;
  font-size: 0.74rem;
  font-weight: 500;
  color: var(--text-secondary);
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: 100px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.acx__chip:hover {
  border-color: var(--brand-accent);
}

.acx__chip.is-active {
  color: var(--brand-accent-dark);
  background: rgba(92, 201, 193, 0.1);
  border-color: rgba(92, 201, 193, 0.45);
}

.acx__chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.acx__chip-role {
  font-size: 0.64rem;
  color: var(--text-muted);
  flex-shrink: 0;
}

.acx__kb-select option {
  background: #fff;
  color: var(--text-primary);
}

.acx__spacer {
  flex: 1;
}

.acx__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.68rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--brand-accent-dark);
}

.acx__status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-accent);
  box-shadow: 0 0 8px rgba(92, 201, 193, 0.8);
  animation: acx-pulse 1s ease-in-out infinite;
}

@keyframes acx-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%     { opacity: 0.4; transform: scale(0.75); }
}

/* Input */
.acx__input {
  width: 100%;
  min-height: 44px;
  max-height: 200px;
  margin-top: 8px;
  padding: 4px 2px 0;
  border: none;
  outline: none;
  resize: none;
  font-family: inherit;
  font-size: 0.96rem;
  line-height: 1.6;
  color: var(--text-primary);
  background: transparent;
  caret-color: var(--brand-primary);
}

.acx__input::placeholder {
  color: var(--text-muted);
}

.acx__input:disabled {
  cursor: not-allowed;
}

/* Bar */
.acx__bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-subtle);
}

.acx__hints {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 0.72rem;
  color: var(--text-muted);
}

.acx__hints kbd {
  display: inline-block;
  padding: 2px 7px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.68rem;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  border: 1px solid var(--border-default);
  border-radius: 4px;
  box-shadow: 0 1px 0 var(--border-default);
}

.acx__sep {
  opacity: 0.5;
  margin: 0 2px;
}

/* Buttons */
.acx__btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 18px;
  font-family: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  letter-spacing: 0.01em;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.acx__btn--send {
  color: #fff;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  box-shadow: 0 4px 12px rgba(74, 144, 217, 0.25);
}

.acx__shell.is-kb .acx__btn--send {
  background: linear-gradient(135deg, var(--brand-accent), var(--brand-accent-dark));
  box-shadow: 0 4px 12px rgba(92, 201, 193, 0.3);
}

.acx__btn--send:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 8px 18px rgba(74, 144, 217, 0.35);
}

.acx__shell.is-kb .acx__btn--send:hover:not(:disabled) {
  box-shadow: 0 8px 18px rgba(92, 201, 193, 0.4);
}

.acx__btn--send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background: linear-gradient(135deg, #94a3b8, #64748b);
  box-shadow: none;
}

.acx__btn--abort {
  color: #dc2626;
  background: #fff;
  border: 1px solid rgba(239, 68, 68, 0.35);
}

.acx__btn--abort:hover {
  background: rgba(239, 68, 68, 0.06);
  border-color: #ef4444;
}

.acx__btn-square {
  display: inline-block;
  width: 9px;
  height: 9px;
  background: #ef4444;
  border-radius: 2px;
}
</style>
