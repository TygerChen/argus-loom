<script setup lang="ts">
import { reactive, ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { extractApiError } from '@/api/http'
import { fetchModels, type ModelInfo } from '@/api/models'
import {
  getModelConfig,
  saveModelConfig,
  clearModelConfig,
  testModelConnection,
  type ModelConfigView,
  type TestConnectionResult,
} from '@/api/settings'

const router = useRouter()
const authStore = useAuthStore()

const mustChange = computed(() => authStore.currentUser?.mustChangePassword ?? false)

const models = ref<ModelInfo[]>([])
const enabledModels = computed(() => models.value.filter(m => m.enabled ?? true))
const selectedModel = ref(localStorage.getItem('argus-selected-model') || '')
const modelsLoading = ref(false)

// ── 模型设置（BYOK）：自带 API 地址/密钥/模型 ID，服务端加密落库 ──
const modelForm = reactive({
  baseUrl: '',
  apiKey: '',
  modelId: '',
  enabled: true,
})
const modelMaskedKey = ref<string | null>(null)
const modelUpdatedAt = ref<string | null>(null)
const modelLoading = ref(false)
const modelSaving = ref(false)
const modelTesting = ref(false)
const modelMsg = ref('')
const modelErr = ref('')

// 主流 OpenAI 兼容服务商预设（baseUrl 为各官方文档公布的 OpenAI 兼容端点）；
// 一键填充 baseUrl 与模型 ID，密钥仍需用户自行填写
const modelPresets = [
  { label: 'Agnes（平台默认）', baseUrl: 'https://api.agnes-ai.cn', modelId: 'agnes-3.0-flash' },
  { label: 'AMD MiniCPM-V', baseUrl: 'https://developer.amd.com.cn/radeon/api', modelId: 'MiniCPM-V-4.7-35B-A3B' },
  { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', modelId: 'deepseek-chat' },
  { label: '通义千问 Qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode', modelId: 'qwen-plus' },
  { label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas', modelId: 'glm-4-flash' },
  { label: 'Kimi (Moonshot)', baseUrl: 'https://api.moonshot.cn', modelId: 'moonshot-v1-8k' },
  { label: '硅基流动 SiliconFlow', baseUrl: 'https://api.siliconflow.cn', modelId: 'Qwen/Qwen2.5-7B-Instruct' },
  { label: 'OpenAI', baseUrl: 'https://api.openai.com', modelId: 'gpt-4o-mini' },
  { label: 'Ollama（本机）', baseUrl: 'http://localhost:11434', modelId: 'qwen2.5' },
]

function applyModelPreset(preset: { label: string; baseUrl: string; modelId: string }) {
  modelForm.baseUrl = preset.baseUrl
  modelForm.modelId = preset.modelId
  modelMsg.value = `已填入 ${preset.label} 预设，请补充 API Key 后保存`
  modelErr.value = ''
}
const testResult = ref<TestConnectionResult | null>(null)

async function loadModelConfig() {
  modelLoading.value = true
  modelErr.value = ''
  try {
    const view: ModelConfigView = await getModelConfig()
    modelForm.baseUrl = view.baseUrl ?? ''
    modelForm.modelId = view.modelId ?? ''
    modelForm.enabled = view.enabled
    modelMaskedKey.value = view.maskedKey
    modelUpdatedAt.value = view.updatedAt
    // 已有配置时密钥留空 = 沿用；新配置才要求输入
  } catch (err) {
    modelErr.value = extractApiError(err, '加载模型配置失败')
  } finally {
    modelLoading.value = false
  }
}

async function handleSaveModelConfig() {
  modelErr.value = ''
  modelMsg.value = ''
  if (!modelForm.baseUrl.trim() || !modelForm.modelId.trim()) {
    modelErr.value = '请填写 API 地址与模型 ID'
    return
  }
  if (!modelMaskedKey.value && !modelForm.apiKey.trim()) {
    modelErr.value = '请填写 API Key'
    return
  }
  modelSaving.value = true
  try {
    await saveModelConfig({
      baseUrl: modelForm.baseUrl.trim(),
      apiKey: modelForm.apiKey.trim() || undefined,
      modelId: modelForm.modelId.trim(),
      enabled: modelForm.enabled,
    })
    modelMsg.value = '模型配置已保存，问答与 AI 助手将优先使用这套模型'
    modelForm.apiKey = ''
    await loadModelConfig()
  } catch (err) {
    modelErr.value = extractApiError(err, '保存模型配置失败')
  } finally {
    modelSaving.value = false
  }
}

async function handleClearModelConfig() {
  modelErr.value = ''
  modelMsg.value = ''
  modelSaving.value = true
  try {
    await clearModelConfig()
    modelMsg.value = '已清除，恢复使用平台默认模型'
    modelForm.baseUrl = ''
    modelForm.modelId = ''
    modelForm.apiKey = ''
    modelMaskedKey.value = null
    modelUpdatedAt.value = null
  } catch (err) {
    modelErr.value = extractApiError(err, '清除模型配置失败')
  } finally {
    modelSaving.value = false
  }
}

async function handleTestModelConfig() {
  modelErr.value = ''
  modelMsg.value = ''
  testResult.value = null
  if (!modelForm.baseUrl.trim() || !modelForm.modelId.trim()) {
    modelErr.value = '请先填写 API 地址与模型 ID'
    return
  }
  modelTesting.value = true
  try {
    testResult.value = await testModelConnection({
      baseUrl: modelForm.baseUrl.trim(),
      apiKey: modelForm.apiKey.trim() || undefined,
      modelId: modelForm.modelId.trim(),
      enabled: modelForm.enabled,
    })
  } catch (err) {
    modelErr.value = extractApiError(err, '测试请求失败')
  } finally {
    modelTesting.value = false
  }
}

onMounted(async () => {
  modelsLoading.value = true
  try {
    models.value = await fetchModels()
    if (!selectedModel.value && enabledModels.value.length > 0) {
      const firstModel = enabledModels.value[0]
      if (firstModel) selectedModel.value = firstModel.id
    }
  } catch { /* ignore */ }
  finally { modelsLoading.value = false }
  loadModelConfig()
})

watch(selectedModel, (val) => {
  if (val) localStorage.setItem('argus-selected-model', val)
})

const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

async function handleChangePassword() {
  errorMsg.value = ''
  successMsg.value = ''

  if (!form.currentPassword.trim() || !form.newPassword.trim() || !form.confirmPassword.trim()) {
    errorMsg.value = '请填写所有密码字段'
    return
  }

  if (form.newPassword.length < 6) {
    errorMsg.value = '新密码长度至少 6 个字符'
    return
  }

  if (form.newPassword !== form.confirmPassword) {
    errorMsg.value = '两次输入的新密码不一致'
    return
  }

  if (form.currentPassword === form.newPassword) {
    errorMsg.value = '新密码不能与当前密码相同'
    return
  }

  loading.value = true
  try {
    await authStore.changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
    })
    form.currentPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    successMsg.value = '密码修改成功'

    if (mustChange.value) {
      setTimeout(() => {
        router.push(authStore.homePath)
      }, 1200)
    }
  } catch (err) {
    errorMsg.value = extractApiError(err, '修改密码失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <h1>系统设置</h1>
      <p>账号安全与密码管理</p>
    </div>

    <!-- 强制修改密码提示 -->
    <div v-if="mustChange" class="must-change-banner">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5"/>
        <path d="M12 8V12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        <circle cx="12" cy="16" r="0.5" fill="currentColor" stroke="none"/>
      </svg>
      <span>出于安全考虑，您需要先修改密码后才能继续使用系统</span>
    </div>

    <!-- 模型选择 -->
    <div class="settings-card">
      <h2 class="card-title">AI 模型</h2>
      <p class="card-desc">选择默认的对话和问答模型</p>

      <!-- 可用模型 -->
      <div class="model-category">
        <h3 class="category-label">📝 对话与问答模型</h3>
        <select v-model="selectedModel" :disabled="modelsLoading" class="model-select">
          <option v-for="m in enabledModels" :key="m.id" :value="m.id">
            {{ m.name }}{{ m.isDefault ? '（默认）' : '' }}
          </option>
        </select>
        <p v-if="selectedModel" class="model-desc">
          {{ enabledModels.find(m => m.id === selectedModel)?.description ?? '' }}
        </p>
      </div>
      <p v-if="modelsLoading" class="model-desc">正在加载模型列表...</p>
    </div>

    <!-- 模型设置（BYOK）：自带 API 地址/密钥/模型 ID -->
    <div class="settings-card">
      <div class="card-title-row">
        <h2 class="card-title">模型设置</h2>
        <span v-if="modelMaskedKey" class="model-status-tag" :class="{ 'is-on': modelForm.enabled }">
          {{ modelForm.enabled ? '已启用' : '已停用' }}
        </span>
      </div>
      <p class="card-desc">
        自带模型（OpenAI 兼容接口）：配置后问答与 AI 助手优先使用这套模型；
        密钥加密保存在服务端，未配置时使用平台默认。
      </p>

      <div v-if="modelLoading" class="model-desc">正在加载配置...</div>
      <template v-else>
        <div class="model-form-grid">
          <div class="input-group">
            <label>API 地址（OpenAI 兼容，不含 /v1 以外路径也支持）</label>
            <input
              v-model="modelForm.baseUrl"
              type="text"
              placeholder="如 https://api.agnes-ai.cn"
              autocomplete="off"
            />
          </div>
          <div class="input-group">
            <label>模型 ID</label>
            <input
              v-model="modelForm.modelId"
              type="text"
              placeholder="如 agnes-3.0-flash / gpt-4o-mini / deepseek-chat"
              autocomplete="off"
            />
          </div>
          <div class="input-group">
            <label>API Key {{ modelMaskedKey ? `（已保存 ${modelMaskedKey}，留空 = 沿用）` : '' }}</label>
            <input
              v-model="modelForm.apiKey"
              type="password"
              :placeholder="modelMaskedKey ? '留空则沿用已保存的密钥' : 'sk-...'"
              autocomplete="new-password"
            />
          </div>
          <label class="model-enabled-row">
            <input v-model="modelForm.enabled" type="checkbox" />
            <span>启用这套模型（关闭后保留配置但不生效）</span>
          </label>
        </div>

        <p v-if="modelUpdatedAt" class="model-desc">上次更新：{{ new Date(modelUpdatedAt).toLocaleString('zh-CN') }}</p>

        <div class="preset-row">
          <span class="preset-row__label">快速导入：</span>
          <button
            v-for="preset in modelPresets"
            :key="preset.label"
            type="button"
            class="preset-chip"
            @click="applyModelPreset(preset)"
          >
            {{ preset.label }}
          </button>
        </div>

        <div class="model-actions">
          <button class="btn-primary" :disabled="modelSaving || modelTesting" @click="handleSaveModelConfig">
            {{ modelSaving ? '保存中...' : '保存配置' }}
          </button>
          <button class="btn-ghost" :disabled="modelTesting || modelSaving" @click="handleTestModelConfig">
            {{ modelTesting ? '测试中...' : '测试连接' }}
          </button>
          <button v-if="modelMaskedKey" class="btn-danger-ghost" :disabled="modelSaving" @click="handleClearModelConfig">
            恢复平台默认
          </button>
        </div>

        <p v-if="testResult" class="test-result" :class="testResult.ok ? 'is-ok' : 'is-fail'">
          {{ testResult.ok
            ? `✓ 连接成功${testResult.latencyMs != null ? `（${testResult.latencyMs}ms）` : ''}${testResult.reply ? ` · 模型回复：${testResult.reply}` : ''}`
            : `✗ ${testResult.message}` }}
        </p>
        <p v-if="modelMsg" class="model-msg-ok">{{ modelMsg }}</p>
        <p v-if="modelErr" class="model-msg-err">{{ modelErr }}</p>
      </template>
    </div>

    <!-- 修改密码 -->
    <div class="settings-card">
      <h2 class="card-title">修改密码</h2>
      <form class="password-form" @submit.prevent="handleChangePassword">
        <div class="input-group">
          <label>当前密码</label>
          <input
            v-model="form.currentPassword"
            type="password"
            placeholder="输入当前密码"
            autocomplete="current-password"
          />
        </div>
        <div class="input-group">
          <label>新密码</label>
          <input
            v-model="form.newPassword"
            type="password"
            placeholder="输入新密码（至少 6 位）"
            autocomplete="new-password"
          />
        </div>
        <div class="input-group">
          <label>确认新密码</label>
          <input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入新密码"
            autocomplete="new-password"
          />
        </div>

        <p v-if="errorMsg" class="form-error">{{ errorMsg }}</p>
        <p v-if="successMsg" class="form-success">{{ successMsg }}</p>

        <button type="submit" class="btn-submit" :disabled="loading">
          <span v-if="!loading">保存修改</span>
          <span v-else>保存中...</span>
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 28px;
}
.page-header h1 {
  font-size: 24px;
  font-weight: 800;
  color: var(--text-primary);
  margin-bottom: 4px;
}
.page-header p {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

/* ── 强制修改密码横幅 ── */
.must-change-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.08), rgba(245, 158, 11, 0.03));
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: var(--radius-sm);
  color: #b45309;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 24px;
}
.must-change-banner svg {
  flex-shrink: 0;
  color: #f59e0b;
}

/* ── 模型选择 ── */
.model-select-group { margin-top: 12px; }
.model-select {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-sm, 8px);
  font-size: 14px;
  color: var(--text-primary);
  background: var(--surface-white);
  cursor: pointer;
}
.model-select:disabled { opacity: 0.5; cursor: not-allowed; }
.model-category { margin-top: 16px; }
.model-category:first-child { margin-top: 12px; }
.category-label { font-size: 13px; font-weight: 600; color: var(--text-primary); margin-bottom: 8px; }
.model-tag-list { display: flex; flex-wrap: wrap; gap: 8px; }
.model-tag {
  display: flex; flex-direction: column; gap: 2px;
  padding: 8px 12px; border-radius: var(--radius-sm, 8px);
  background: var(--surface-hover, #f8fafc);
  border: 1px solid var(--border-color, #e2e8f0);
  font-size: 13px; font-weight: 500; color: var(--text-primary);
}
.tag-desc { font-size: 11px; font-weight: 400; color: var(--text-secondary); }
.model-desc {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.card-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: -8px 0 0 0;
}

/* ── 设置卡片 ── */
.settings-card {
  max-width: 480px;
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: 28px 32px;
}

.card-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 22px;
}

/* ── 表单 ── */
.password-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.input-group label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.input-group input {
  padding: 10px 13px;
  border: 1.5px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-family: inherit;
  color: var(--text-primary);
  background: var(--surface-white);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
  outline: none;
}

.input-group input::placeholder {
  color: #94a3b8;
}

.input-group input:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(74, 144, 217, 0.1);
}

.form-error {
  font-size: 13px;
  color: var(--el-color-danger);
  margin: 0;
}

.form-success {
  font-size: 13px;
  color: var(--el-color-success);
  margin: 0;
}

.btn-submit {
  padding: 11px 24px;
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s ease;
  box-shadow: 0 4px 14px rgba(74, 144, 217, 0.25);
  margin-top: 4px;
}

.btn-submit:hover:not(:disabled) {
  box-shadow: 0 6px 20px rgba(74, 144, 217, 0.35);
  transform: translateY(-1px);
}

.btn-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
/* ── 模型设置（BYOK） ── */
.card-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.model-status-tag {
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 2px 10px;
  border-radius: 100px;
  background: var(--surface-muted);
  color: var(--text-muted);
}
.model-status-tag.is-on {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
}
.model-form-grid {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin: 14px 0 4px;
}
.model-enabled-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.85rem;
  color: var(--text-secondary);
  cursor: pointer;
}
.model-enabled-row input {
  width: 15px;
  height: 15px;
  accent-color: var(--brand-primary);
}
.preset-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.preset-row__label {
  font-size: 0.78rem;
  color: var(--text-muted);
  flex-shrink: 0;
}
.preset-chip {
  padding: 3px 12px;
  font-family: inherit;
  font-size: 0.74rem;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  border: 1px solid var(--border-default);
  border-radius: 100px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.preset-chip:hover {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: var(--surface-accent);
  transform: translateY(-1px);
}
.model-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.model-actions button {
  padding: 8px 18px;
  font-family: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.18s ease;
  border: 1px solid transparent;
}
.model-actions .btn-primary {
  color: #fff;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  box-shadow: 0 3px 10px rgba(74, 144, 217, 0.25);
}
.model-actions .btn-primary:hover:not(:disabled) { transform: translateY(-1px); }
.model-actions .btn-ghost {
  color: var(--brand-primary);
  background: var(--surface-accent);
  border-color: rgba(74, 144, 217, 0.25);
}
.model-actions .btn-ghost:hover:not(:disabled) { border-color: var(--brand-primary); }
.model-actions .btn-danger-ghost {
  color: var(--el-color-danger);
  background: transparent;
  border-color: rgba(239, 68, 68, 0.3);
}
.model-actions .btn-danger-ghost:hover:not(:disabled) { background: rgba(239, 68, 68, 0.06); }
.model-actions button:disabled { opacity: 0.55; cursor: not-allowed; }
.test-result {
  margin: 12px 0 0;
  font-size: 0.82rem;
  padding: 9px 14px;
  border-radius: var(--radius-sm);
  line-height: 1.5;
  word-break: break-all;
}
.test-result.is-ok { background: rgba(16, 185, 129, 0.08); color: #059669; border: 1px solid rgba(16, 185, 129, 0.2); }
.test-result.is-fail { background: rgba(239, 68, 68, 0.06); color: #dc2626; border: 1px solid rgba(239, 68, 68, 0.18); }
.model-msg-ok { margin: 8px 0 0; font-size: 0.8rem; color: #059669; }
.model-msg-err { margin: 8px 0 0; font-size: 0.8rem; color: var(--el-color-danger); }
</style>