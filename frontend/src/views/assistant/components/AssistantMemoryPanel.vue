<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  compactSummary: string | null
  sessionMemory: string | null
}>()

const expanded = ref(false)

const hasContent = computed(() => {
  return !!(props.compactSummary || props.sessionMemory)
})
</script>

<template>
  <div v-if="hasContent" class="memory-panel">
    <div class="memory-panel__header" @click="expanded = !expanded">
      <span>AI 当前记忆</span>
      <span>{{ expanded ? '收起' : '展开' }}</span>
    </div>
    <div v-show="expanded" class="memory-panel__body">
      <div v-if="sessionMemory" class="memory-panel__section">
        <div class="memory-panel__label">会话记忆</div>
        <div class="memory-panel__text">{{ sessionMemory }}</div>
      </div>
      <div v-if="compactSummary" class="memory-panel__section">
        <div class="memory-panel__label">紧凑摘要</div>
        <div class="memory-panel__text">{{ compactSummary }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-panel {
  background: #f5f7fa;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
  font-size: 13px;
}
.memory-panel__header {
  display: flex;
  justify-content: space-between;
  cursor: pointer;
  color: #606266;
  font-weight: 500;
}
.memory-panel__body {
  margin-top: 10px;
}
.memory-panel__section {
  margin-bottom: 8px;
}
.memory-panel__label {
  color: #909399;
  font-size: 12px;
  margin-bottom: 4px;
}
.memory-panel__text {
  color: #303133;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>