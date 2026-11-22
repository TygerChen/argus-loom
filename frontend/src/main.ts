import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 暗色变量：与 useTheme 的 html.dark 类配合，让 EP 组件跟随主题切换
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { pinia } from './stores/pinia'
import './assets/main.css'
// Excel/财务视图的 --dl-* 暖色系变量与 .dl-page 布局基类（此前从未被导入，
// 四个页面全靠 var() 的 fallback 值渲染——暗色映射也因此整体失效）
import './styles/app.css'
import { useTheme } from './composables/useTheme'

// 在应用启动时初始化主题：不能只在 DefaultLayout 里调用——
// 登录页/首页不经过该布局，否则已保存的主题偏好在公开页不生效。
useTheme()

const app = createApp(App)

app.use(pinia)
app.use(router)
app.use(ElementPlus)

app.mount('#app')
