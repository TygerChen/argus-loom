<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  unit?: string
  color?: string
}>()
</script>

<template>
  <div class="stat-card" :style="{ '--stat-color': color || 'var(--brand-primary)' }">
    <div class="stat-card__value">
      <span class="stat-card__number">{{ value }}</span>
      <span v-if="unit" class="stat-card__unit">{{ unit }}</span>
    </div>
    <div class="stat-card__label">{{ label }}</div>
  </div>
</template>

<style scoped>
.stat-card {
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  position: relative;
  overflow: hidden;
  transition: box-shadow 0.25s ease, transform 0.25s ease, border-color 0.25s ease;
}
.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
  border-color: rgba(74, 144, 217, 0.2);
}
.stat-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: linear-gradient(180deg, var(--stat-color), rgba(74, 144, 217, 0.2));
  border-radius: 0 2px 2px 0;
}
.stat-card__value {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.stat-card__number {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1;
}
.stat-card__unit {
  font-size: 0.8rem;
  color: var(--text-muted);
  font-weight: 500;
}
.stat-card__label {
  font-size: 0.82rem;
  color: var(--text-secondary);
  font-weight: 500;
}
</style>