<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginModal from '@/components/LoginModal.vue'

const router = useRouter()
const authStore = useAuthStore()
const showLoginModal = ref(false)

function go() {
  if (authStore.isAuthenticated) {
    router.push(authStore.homePath)
  } else {
    showLoginModal.value = true
  }
}
</script>

<template>
  <div class="landing">
    <LoginModal v-model:visible="showLoginModal" />

    <!-- 极简导航：logo + 登录 -->
    <header class="navbar">
      <a href="/" class="nav-brand">
        <svg width="26" height="26" viewBox="0 0 32 32" fill="none">
          <defs>
            <linearGradient id="ng" x1="2" y1="2" x2="30" y2="30"><stop stop-color="#4A90D9"/><stop offset="1" stop-color="#5CC9C1"/></linearGradient>
            <path id="np" d="M16 3C17.5 6 20 9 22.5 10C20 11 17.5 11.5 16 13C14.5 11.5 12 11 9.5 10C12 9 14.5 6 16 3Z" fill="url(#ng)" opacity="0.7"/>
          </defs>
          <use href="#np"/><use href="#np" transform="rotate(90 16 16)"/><use href="#np" transform="rotate(180 16 16)"/><use href="#np" transform="rotate(270 16 16)"/>
          <circle cx="16" cy="16" r="5.5" fill="url(#ng)"/>
        </svg>
        <span>数据洞察中心</span>
      </a>
      <div class="nav-actions">
        <button class="btn-login" @click="go">{{ authStore.isAuthenticated ? '进入工作台' : '登录' }}</button>
      </div>
    </header>

    <!-- 全屏居中 Hero -->
    <section class="hero">
      <div class="hero-bg">
        <div class="hero-grid"></div>
        <div class="hero-glow"></div>
      </div>
      <div class="hero-center">
        <h1 class="tagline">让每一次提问<br><span class="grad">都有据可查</span></h1>
        <p class="sub">RAG 知识库问答 · Excel 在线协同 · 财务工作台</p>

        <!-- 聊天输入框（视觉主角） -->
        <div class="chat-input" @click="go">
          <span class="chat-placeholder">问点什么，一起探索…</span>
          <span class="chat-arrow">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M12 19V5M12 5L5 12M12 5L19 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
        </div>

        <div class="hero-actions">
          <button class="btn-main" @click="go">开始使用</button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.landing {
  min-height: 100vh;
  background: linear-gradient(160deg, #f0f4fa 0%, #f5f7fb 40%, #f0f6fc 100%);
  display: flex;
  flex-direction: column;
}

/* 导航 */
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 32px;
  position: fixed;
  top: 0; left: 0; right: 0;
  z-index: 10;
  background: color-mix(in srgb, #f5f7fb 80%, transparent);
  backdrop-filter: blur(12px);
}
.nav-brand {
  display: flex; align-items: center; gap: 9px;
  font-weight: 700; font-size: 16px; color: var(--text-primary);
}
.nav-actions { display: flex; gap: 8px; }
.btn-login {
  padding: 8px 22px; border-radius: 8px;
  background: var(--brand-primary); color: #fff;
  font-size: 14px; font-weight: 600; border: none; cursor: pointer;
  transition: background 0.2s;
}
.btn-login:hover { background: var(--brand-primary-dark); }

/* Hero 全屏居中 */
.hero {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}
.hero-bg { position: absolute; inset: 0; pointer-events: none; }
.hero-grid {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(74,144,217,0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(74,144,217,0.03) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 60% 60% at 50% 45%, black 20%, transparent 75%);
}
.hero-glow {
  position: absolute;
  width: 600px; height: 400px;
  background: radial-gradient(ellipse, rgba(74,144,217,0.06) 0%, transparent 70%);
  top: 20%; left: 50%; transform: translateX(-50%);
}
.hero-center {
  text-align: center;
  position: relative;
  z-index: 1;
  max-width: 620px;
  padding: 0 24px;
}

/* 大字标语 */
.tagline {
  font-size: clamp(40px, 5.5vw, 60px);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--text-primary);
  margin: 0 0 14px;
}
.grad {
  background: linear-gradient(120deg, var(--brand-primary), var(--brand-accent));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.sub {
  font-size: 15px;
  color: var(--text-muted);
  margin: 0 0 36px;
}

/* 聊天输入框（视觉主角） */
.chat-input {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  border-radius: 16px;
  background: rgba(255,255,255,0.85);
  border: 1px solid rgba(74,144,217,0.15);
  box-shadow: 0 4px 24px rgba(74,144,217,0.08), 0 1px 4px rgba(0,0,0,0.03);
  cursor: pointer;
  transition: all 0.25s ease;
  margin-bottom: 28px;
}
.chat-input:hover {
  border-color: rgba(74,144,217,0.3);
  box-shadow: 0 8px 32px rgba(74,144,217,0.12);
  transform: translateY(-1px);
}
.chat-placeholder {
  color: var(--text-muted);
  font-size: 15px;
}
.chat-arrow {
  width: 36px; height: 36px;
  border-radius: 50%;
  background: var(--brand-primary);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}

/* 按钮 */
.hero-actions { display: flex; justify-content: center; }
.btn-main {
  padding: 12px 36px; border-radius: 10px;
  background: var(--brand-primary); color: #fff;
  font-size: 15px; font-weight: 600; border: none; cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 4px 16px rgba(74,144,217,0.2);
}
.btn-main:hover {
  background: var(--brand-primary-dark);
  transform: translateY(-1px);
  box-shadow: 0 6px 24px rgba(74,144,217,0.28);
}

@keyframes fade-in {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}
.tagline, .sub, .chat-input, .hero-actions {
  animation: fade-in 0.6s ease both;
}
.sub { animation-delay: 0.1s; }
.chat-input { animation-delay: 0.2s; }
.hero-actions { animation-delay: 0.3s; }
</style>
