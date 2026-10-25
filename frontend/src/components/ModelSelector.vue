<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchModels, type ModelInfo } from '@/api/models'

defineProps<{
  disabled: boolean
}>()

const model = defineModel<string>()

const models = ref<ModelInfo[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const list = await fetchModels()
    models.value = list.filter(m => m.enabled !== false)
  } catch {
    models.value = []
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="model-selector" :class="{ 'is-disabled': disabled || loading }">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
    </svg>
    <select
      v-model="model"
      class="model-selector__select"
      :disabled="disabled || loading"
    >
      <option v-for="m in models" :key="m.id" :value="m.id">
        {{ m.name }}{{ m.isDefault ? '（默认）' : '' }}
      </option>
    </select>
  </div>
</template>

<style scoped>
.model-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--surface-subtle);
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  transition: border-color 0.2s;
}

.model-selector:hover { border-color: rgba(74, 144, 217, 0.3); }

.model-selector svg {
  color: var(--brand-primary);
  flex-shrink: 0;
}

.model-selector__select {
  border: none;
  background: transparent;
  font-size: 0.78rem;
  color: var(--text-secondary);
  outline: none;
  cursor: pointer;
  min-width: 0;
  padding: 0;
}

.model-selector__select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.model-selector__select option {
  color: var(--text-primary);
  background: var(--surface-white);
}
</style>
