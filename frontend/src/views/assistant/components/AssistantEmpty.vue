<script setup lang="ts">
import type { AssistantToolMode } from '@/types/assistant'

defineProps<{
  mode: AssistantToolMode
  hasKb: boolean
}>()

const emit = defineEmits<{
  pick: [prompt: string, mode: AssistantToolMode]
}>()

const chatStarters = [
  '帮我围绕 XXX 展开 5 个不同角度的思考',
  '用通俗的方式讲解 XXX，加一个生活化的比喻',
  '这段代码有什么可以优化的地方？',
  '对比 A 和 B 两种方案的差异，帮我做选择',
]

const kbStarters = [
  '「新增核算项目」的操作步骤是什么？请给出文档依据',
  '「审核人」和「制单人」有什么区别？',
  '保存单据提示校验不通过，手册里的处理方法是什么？',
  '月末结账前需要完成哪些检查？列出清单并标明出处',
]
</script>

<template>
  <div class="aempty">
    <div class="aempty__inner">
      <p class="aempty__greeting">
        {{ mode === 'CHAT' ? '有什么可以帮你？' : '想查什么？' }}
      </p>
      <p v-if="mode === 'KB_SEARCH' && !hasKb" class="aempty__warn">
        请先在下方选择一个知识库。
      </p>

      <div class="aempty__starters">
        <button
          v-for="(prompt, i) in (mode === 'CHAT' ? chatStarters : kbStarters)"
          :key="`${mode}-${i}`"
          class="aempty__starter"
          type="button"
          :disabled="mode === 'KB_SEARCH' && !hasKb"
          @click="emit('pick', prompt, mode)"
        >{{ prompt }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.aempty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;
  background: var(--surface-white);
}
.aempty__inner {
  width: 100%;
  max-width: 640px;
  text-align: center;
}
.aempty__greeting {
  font-size: 1.8rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 20px;
  letter-spacing: -0.01em;
}
.aempty__warn {
  font-size: 0.85rem;
  color: #b45309;
  margin: -12px 0 16px;
}
.aempty__starters {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.aempty__starter {
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid var(--border-default);
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.84rem;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s;
  line-height: 1.5;
  text-align: left;
}
.aempty__starter:hover:not(:disabled) {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: rgba(74, 144, 217, 0.04);
}
.aempty__starter:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
