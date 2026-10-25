import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // ── 公开页面（无侧边栏布局） ──
    {
      path: '/',
      name: 'home',
      component: () => import('../views/HomeView.vue'),
      meta: { title: '数据洞察中心' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { title: '登录 - 数据洞察中心' },
    },

    // ── 认证后的工作区（含侧边栏布局） ──
    {
      path: '/app',
      component: () => import('../layouts/DefaultLayout.vue'),
      redirect: '/app/groups',
      children: [
        {
          path: 'documents',
          name: 'documents',
          component: () => import('../views/documents/DocumentListView.vue'),
          meta: { title: '文档管理' },
        },
        {
          path: 'qa',
          redirect: '/app/assistant',
          meta: { title: 'AI 助手' },
        },
        {
          path: 'assistant',
          name: 'assistant',
          component: () => import('../views/assistant/AssistantView.vue'),
          meta: { title: 'AI 助手' },
        },
        {
          path: 'assistant/:sessionId',
          name: 'assistant-chat',
          component: () => import('../views/assistant/AssistantView.vue'),
          meta: { title: 'AI 助手' },
        },
        {
          path: 'groups',
          name: 'groups',
          component: () => import('../views/groups/GroupsPage.vue'),
          meta: { title: '协作小组' },
        },
        {
          path: 'admin',
          name: 'admin-dashboard',
          component: () => import('../views/admin/AdminDashboard.vue'),
          meta: { title: '系统总览', requireAdmin: true },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: () => import('../views/admin/UserManagementView.vue'),
          meta: { title: '用户管理', requireAdmin: true },
        },
        {
          path: 'admin/metrics',
          name: 'admin-metrics',
          component: () => import('../views/admin/MetricsView.vue'),
          meta: { title: '使用统计', requireAdmin: true },
        },
        {
          path: 'excel',
          name: 'excel',
          component: () => import('../views/excel/ExcelDashboard.vue'),
          meta: { title: 'Excel 编辑器' },
        },
        {
          path: 'excel/tasks',
          name: 'excel-exports',
          component: () => import('../views/excel/ExcelExportTasks.vue'),
          meta: { title: '导出任务' },
        },
        {
          path: 'excel/finance',
          name: 'excel-finance',
          component: () => import('../views/excel/FinanceDashboard.vue'),
          meta: { title: '财务数据中心', requireFinance: true },
        },
        {
          path: 'finance/workbench',
          name: 'finance-workbench',
          component: () => import('../views/excel/FinanceWorkbenchView.vue'),
          meta: { title: '财务工作台', requireFinance: true },
        },
        {
          path: 'finance/my-payroll',
          name: 'finance-my-payroll',
          component: () => import('../views/excel/MyPayrollView.vue'),
          meta: { title: '我的薪酬' },
        },
        {
          path: 'excel/operations',
          name: 'excel-ops',
          component: () => import('../views/excel/ExcelOperationCenter.vue'),
          meta: { title: 'Excel 运营中心', requireAdmin: true },
        },
        {
          path: 'excel/:id',
          name: 'excel-editor',
          component: () => import('../views/excel/SheetEditor.vue'),
          meta: { title: '编辑文档' },
        },
        {
          path: 'excel/:id/versions',
          name: 'excel-versions',
          component: () => import('../views/excel/DocumentVersions.vue'),
          props: true,
          meta: { title: '版本历史' },
        },
        {
          path: 'excel/recycle-bin',
          name: 'excel-recycle',
          component: () => import('../views/excel/RecycleBin.vue'),
          meta: { title: '回收站' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('../views/settings/SettingsView.vue'),
          meta: { title: '系统设置' },
        },
      ],
    },

    // ── 404 ──
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面未找到' },
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

// 全局标题守卫
router.afterEach((to) => {
  document.title = (to.meta.title as string) || '数据洞察中心'
})

/**
 * 全局认证守卫 —— 每次路由切换前的五连问（对照 02/03 篇读）：
 * ① bootstrap 恢复会话（HttpOnly Cookie 换新 token，内部单飞去重）；
 * ② 已登录访问公开页 → 送回角色对应的首页；
 * ③ 未登录访问受保护页 → 踢到登录页；
 * ④ 角色路径纠偏（财务工作台只许 FINANCE 进）；
 * ⑤ meta.requireAdmin/requireFinance 的声明式拦截。
 * 注意：这只是前端的路由门禁，防的是"误入"；绕过前端直接打接口时，
 * 后端的权限校验才是真防线——两层永远不能只信一层。
 */
router.beforeEach(async (to, _from) => {
  const authStore = useAuthStore()
  const isPublic = to.path === '/' || to.path === '/login' || to.name === 'not-found'

  // 尝试恢复会话（内部有去重，不会重复请求）
  await authStore.bootstrap()

  // 已登录用户访问公开页面 → 跳转到工作区首页
  if (authStore.isAuthenticated && isPublic) {
    return authStore.homePath
  }

  // 未登录用户访问受保护页面 → 跳转到登录页
if (!authStore.isAuthenticated && !isPublic) {
      return '/login'
    }

  // 角色/权限检查
  if (authStore.isAuthenticated && !isPublic) {
    const redirect = authStore.resolveRedirectForPath(to.path)
    if (redirect) {
      return redirect
    }
  }

  // 管理员页面：普通用户无权访问
  if (to.meta.requireAdmin && !authStore.isAdmin) {
    return authStore.homePath
  }

  // 财务工作台：仅财务/领导角色可访问
  if (to.meta.requireFinance && !authStore.isFinance) {
    return authStore.homePath
  }
})

export default router
