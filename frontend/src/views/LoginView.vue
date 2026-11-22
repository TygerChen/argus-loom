<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { extractApiError } from '@/api/http'
import type { LoginPayload } from '@/api/auth'

const router = useRouter()
const authStore = useAuthStore()

const form = reactive<LoginPayload>({ loginId: '', password: '' })
const loading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  if (!form.loginId.trim() || !form.password.trim()) {
    errorMsg.value = '请输入登录标识和密码'
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    await authStore.login({ loginId: form.loginId.trim(), password: form.password })
    router.push(authStore.resolveLandingPath())
  } catch (err) {
    errorMsg.value = extractApiError(err, '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-bg"></div>
    <div class="login-center">
      <a href="/" class="login-logo">
        <svg width="30" height="30" viewBox="0 0 32 32" fill="none">
          <defs>
            <linearGradient id="lgGrad" x1="2" y1="2" x2="30" y2="30"><stop stop-color="#4A90D9"/><stop offset="1" stop-color="#5CC9C1"/></linearGradient>
            <path id="lgPetal" d="M16 3C17.5 6 20 9 22.5 10C20 11 17.5 11.5 16 13C14.5 11.5 12 11 9.5 10C12 9 14.5 6 16 3Z" fill="url(#lgGrad)" opacity="0.7"/>
          </defs>
          <use href="#lgPetal"/><use href="#lgPetal" transform="rotate(90 16 16)"/><use href="#lgPetal" transform="rotate(180 16 16)"/><use href="#lgPetal" transform="rotate(270 16 16)"/>
          <circle cx="16" cy="16" r="5.5" fill="url(#lgGrad)"/>
        </svg>
        <span>数据洞察中心</span>
      </a>

      <form class="login-card" @submit.prevent="handleLogin">
        <h1>欢迎回来</h1>
        <p class="sub">使用您的账号登录工作台</p>

        <input v-model="form.loginId" type="text" placeholder="用户名或邮箱"
               autocomplete="username" class="login-input" />
        <input v-model="form.password" type="password" placeholder="输入密码"
               autocomplete="current-password" class="login-input" />

        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

        <button type="submit" class="btn-login" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
        <p class="hint">账号由系统管理员统一创建 · <a href="/" class="back">返回首页</a></p>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #f0f4fa 0%, #f5f7fb 40%, #f0f6fc 100%);
  position: relative;
}
.login-bg {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(74,144,217,0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(74,144,217,0.03) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 60% 60% at 50% 50%, black 20%, transparent 75%);
  pointer-events: none;
}
.login-center {
  position: relative; z-index: 1;
  display: flex; flex-direction: column; align-items: center;
  width: min(380px, calc(100% - 48px));
}
.login-logo {
  display: flex; align-items: center; gap: 10px;
  font-weight: 700; font-size: 16px; color: var(--text-primary);
  margin-bottom: 36px;
}
.login-card {
  width: 100%;
  padding: 36px 32px;
  border-radius: 16px;
  background: rgba(255,255,255,0.88);
  border: 1px solid rgba(74,144,217,0.12);
  box-shadow: 0 4px 24px rgba(74,144,217,0.06), 0 1px 4px rgba(0,0,0,0.02);
  text-align: center;
}
.login-card h1 {
  font-size: 24px; font-weight: 700; color: var(--text-primary);
  margin: 0 0 6px;
}
.sub { font-size: 14px; color: var(--text-muted); margin: 0 0 28px; }
.login-input {
  width: 100%;
  padding: 12px 16px;
  border-radius: 10px;
  border: 1px solid var(--border-default);
  font-size: 14px;
  margin-bottom: 14px;
  transition: border-color 0.2s;
  outline: none;
}
.login-input:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(74,144,217,0.08);
}
.error {
  color: #ef4444; font-size: 13px; margin: 0 0 12px;
}
.btn-login {
  width: 100%;
  padding: 13px;
  border-radius: 10px;
  background: var(--brand-primary); color: #fff;
  font-size: 15px; font-weight: 600; border: none; cursor: pointer;
  transition: background 0.2s;
  margin-top: 4px;
}
.btn-login:hover { background: var(--brand-primary-dark); }
.btn-login:disabled { opacity: 0.6; cursor: not-allowed; }
.hint { font-size: 12.5px; color: var(--text-muted); margin: 16px 0 0; }
.hint .back { color: var(--brand-primary); text-decoration: none; }
</style>
