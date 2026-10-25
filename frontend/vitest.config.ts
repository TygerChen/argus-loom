import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    // jsdom 提供 DOMPurify 需要的完整 DOM API（happy-dom 的节点实现与其不兼容，
    // 会导致 sanitize 静默失效），因此测试环境统一使用 jsdom。
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
