/**
 * 主题切换 —— 深浅两套 token 的总开关。
 *
 * 双类名机制：html.theme-dark 翻转项目自定义 token（surface、text、dl 等前缀的变量），
 * html.dark 翻转 Element Plus 内置变量（main.ts 引入的 dark/css-vars.css）。
 * 默认恒为浅色——暗色是渐进增强，个别页面仍有硬编码色，切换后如发现
 * 局部未跟随，优先把该处颜色 token 化而不是往这里堆特例。
 */
import { ref, watchEffect } from 'vue'

const THEME_KEY = 'argus-theme'

export type ThemeMode = 'light' | 'dark'

function initTheme(): ThemeMode {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    // localStorage 不可用（隐私模式等）时回落默认值
  }
  return 'light'
}

const theme = ref<ThemeMode>(initTheme())

function applyTheme(mode: ThemeMode) {
  const root = document.documentElement
  root.classList.toggle('theme-dark', mode === 'dark')
  root.classList.toggle('dark', mode === 'dark')
}

watchEffect(() => applyTheme(theme.value))

export function useTheme() {
  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
    try {
      localStorage.setItem(THEME_KEY, theme.value)
    } catch {
      // 持久化失败只影响下次启动的默认值，不影响本次切换
    }
  }

  return { theme, toggleTheme }
}
