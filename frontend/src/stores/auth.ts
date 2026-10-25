import { defineStore } from 'pinia'
import {
  changePassword as changePasswordRequest,
  fetchCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  refreshSession,
  type ChangePasswordPayload,
  type CurrentUserProfile,
  type LoginPayload,
} from '../api/auth'
import http from '../api/http'

const ADMIN_HOME_PATH = '/app/admin'
const USER_HOME_PATH = '/app/groups'
const FINANCE_HOME_PATH = '/app/excel/finance'
const ACCOUNT_SECURITY_PATH = '/app/settings'
const LOGIN_PATH = '/login'
const BUSINESS_PATH_PREFIXES = ['/app/groups', '/app/documents', '/app/assistant', '/app/excel']

/**
 * 登录状态仓库（Pinia）—— 前端权限与凭证的唯一管理者。
 *
 * <p><b>对照 02 篇读</b>。核心职责三条：</p>
 * <ol>
 *   <li><b>凭证保管</b>：accessToken 只存在内存（state）里，刷新页面就没了——
 *       故意的！localStorage 能被 XSS 脚本偷走；Refresh Token 在 HttpOnly Cookie
 *       里（JS 摸不到），页面加载时 bootstrap() 凭它静默换回新 accessToken，
 *       用户无感；</li>
 *   <li><b>请求头注入</b>：setSession/clearSession 同步调用
 *       applyAuthorizationHeader，把 "Bearer xxx" 挂到 axios 全局默认头——
 *       之后所有请求自动带上，业务代码不用操心；</li>
 *   <li><b>角色路由</b>：resolveAuthorizedPath 按角色决定"登录后落在哪"——
 *       ADMIN 管理台、FINANCE 财务工作台、USER 知识库首页；用户手动改网址
 *       也会被 resolveRoleRedirect 拉回自己有权的区域（前端引导，
 *       后端才是真防线，03 篇）。</li>
 * </ol>
 *
 * <p><b>bootstrapTask 单飞（single-flight）</b>：页面初始会有多个组件同时
 * 触发 bootstrap，用一个共享 Promise 保证"换新 token"只发生一次，
 * 防止并发 refresh 互相踩（和后端 Refresh 轮换互斥是同一场攻防的前端面）。</p>
 */
interface AuthState {
  accessToken: string | null
  currentUser: CurrentUserProfile | null
  isBootstrapping: boolean
  isAuthenticating: boolean
}

let bootstrapTask: Promise<CurrentUserProfile | null> | null = null

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    currentUser: null,
    isBootstrapping: false,
    isAuthenticating: false,
  }),
  getters: {
    isAuthenticated(state): boolean {
      return state.accessToken !== null && state.currentUser !== null
    },
    isAdmin(state): boolean {
      return state.currentUser?.systemRole === 'ADMIN'
    },
    isFinance(state): boolean {
      return state.currentUser?.systemRole === 'FINANCE'
    },
    isUser(state): boolean {
      return state.currentUser?.systemRole === 'USER'
    },
    homePath(state): string {
      return resolveAuthorizedPath(state.currentUser)
    },
  },
  actions: {
    async bootstrap(force = false): Promise<CurrentUserProfile | null> {
      if (!force && this.currentUser !== null && this.accessToken !== null) {
        return this.currentUser
      }

      if (!force && bootstrapTask !== null) {
        return bootstrapTask
      }

      bootstrapTask = this.runBootstrap()
      try {
        return await bootstrapTask
      } finally {
        bootstrapTask = null
      }
    },
    async refresh(): Promise<CurrentUserProfile> {
      const session = await refreshSession()
      this.setSession(session.accessToken, session.currentUser)
      return session.currentUser
    },
    async login(payload: LoginPayload): Promise<CurrentUserProfile> {
      this.isAuthenticating = true
      try {
        const session = await loginRequest({
          loginId: payload.loginId.trim(),
          password: payload.password,
        })
        this.setSession(session.accessToken, session.currentUser)
        return session.currentUser
      } finally {
        this.isAuthenticating = false
      }
    },
    async logout(): Promise<void> {
      try {
        await logoutRequest()
      } catch {
        // 远端退出失败时仍清理本地会话，避免界面残留登录态。
      } finally {
        this.clearSession()
      }
    },
    async changePassword(payload: ChangePasswordPayload): Promise<CurrentUserProfile | null> {
      await changePasswordRequest(payload)

      if (this.currentUser !== null) {
        this.currentUser = { ...this.currentUser, mustChangePassword: false }
      }

      try {
        this.currentUser = await fetchCurrentUser()
      } catch {
        return this.currentUser
      }

      return this.currentUser
    },
    resolveLandingPath(requestedPath?: string | null): string {
      return resolveAuthorizedPath(this.currentUser, requestedPath)
    },
    resolveRedirectForPath(requestedPath: string): string | null {
      if (this.currentUser === null) {
        return LOGIN_PATH
      }

      return resolveRoleRedirect(this.currentUser, requestedPath)
    },
    clearSession() {
      this.accessToken = null
      this.currentUser = null
      applyAuthorizationHeader(null)
    },
    setSession(accessToken: string, currentUser: CurrentUserProfile) {
      this.accessToken = accessToken
      this.currentUser = currentUser
      applyAuthorizationHeader(accessToken)
    },
    async runBootstrap(): Promise<CurrentUserProfile | null> {
      this.isBootstrapping = true
      applyAuthorizationHeader(this.accessToken)

      try {
        if (this.accessToken !== null) {
          try {
            this.currentUser = await fetchCurrentUser()
            return this.currentUser
          } catch {
            return await this.refresh()
          }
        }

        return await this.refresh()
      } catch {
        this.clearSession()
        return null
      } finally {
        this.isBootstrapping = false
      }
    },
  },
})

export function resolveAuthorizedPath(
  user: CurrentUserProfile | null,
  requestedPath?: string | null,
): string {
  if (user === null) {
    return LOGIN_PATH
  }

  if (user.mustChangePassword) {
    return ACCOUNT_SECURITY_PATH
  }

  const safeRequestedPath = normalizeRequestedPath(requestedPath)

  if (user.systemRole === 'ADMIN') {
    return isAdminPath(safeRequestedPath) || safeRequestedPath === ACCOUNT_SECURITY_PATH
      ? safeRequestedPath
      : ADMIN_HOME_PATH
  }

  if (user.systemRole === 'FINANCE') {
    const financePath = '/app/excel/finance'
    if (safeRequestedPath === financePath || safeRequestedPath?.startsWith('/app/excel') === true) {
      return safeRequestedPath
    }
    return FINANCE_HOME_PATH
  }

  return isBusinessPath(safeRequestedPath) || safeRequestedPath === ACCOUNT_SECURITY_PATH
    ? safeRequestedPath
    : USER_HOME_PATH
}

export function resolveRoleRedirect(user: CurrentUserProfile, requestedPath: string): string | null {
  if (user.mustChangePassword && requestedPath !== ACCOUNT_SECURITY_PATH) {
    return ACCOUNT_SECURITY_PATH
  }

  if (requestedPath === '/app/excel/finance' && user.systemRole !== 'FINANCE') {
    return user.systemRole === 'ADMIN' ? ADMIN_HOME_PATH : USER_HOME_PATH
  }

  return null
}

function applyAuthorizationHeader(accessToken: string | null) {
  /**
   * 把 accessToken 写进 axios 全局默认头（或登出时删掉）。
   * 相比每个请求手动带 token，全局头的好处是"改一处、全生效"，
   * 且登出后不会残留旧凭证。
   */
  if (accessToken === null) {
    delete http.defaults.headers.common.Authorization
    return
  }

  http.defaults.headers.common.Authorization = `Bearer ${accessToken}`
}

function normalizeRequestedPath(path?: string | null): string | null {
  /** 防开放重定向：只接受站内相对路径（拒绝 //evil.com 这类协议相对地址）。 */
  if (typeof path !== 'string' || path.length === 0) {
    return null
  }

  if (!path.startsWith('/') || path.startsWith('//') || path.startsWith(LOGIN_PATH)) {
    return null
  }

  return path
}

function isAdminPath(path: string | null): path is string {
  return path === '/app/admin' || path?.startsWith('/app/admin/') === true
}

function isBusinessPath(path: string | null): path is string {
  return BUSINESS_PATH_PREFIXES.some((prefix) => path === prefix || path?.startsWith(`${prefix}/`))
}
