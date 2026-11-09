<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import axios from 'axios'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../store/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

const reasonMessages: Record<string, string> = {
  SESSION_REPLACED: '账号已在其他位置登录，当前会话已失效，请重新登录',
  SESSION_EXPIRED: '登录已过期，请重新登录',
  SESSION_REVOKED: '账号状态或车队授权已变更，请重新登录',
  ACCOUNT_DISABLED: '账号已停用，请联系管理员',
  'password-changed': '密码已修改，请使用新密码重新登录',
  'service-unavailable': '登录服务暂时不可用，请稍后重试',
}

const reasonMessage = computed(() => {
  const reason = typeof route.query.reason === 'string' ? route.query.reason : ''
  return reasonMessages[reason] ?? ''
})

function describeError(error: unknown): string {
  if (axios.isAxiosError(error) && error.response) {
    const status = error.response.status
    if (status === 401) {
      // 锁定期间的响应与凭证错误一致，不额外提示，避免暴露账号是否被锁定
      return '用户名或密码错误'
    }
    if (status === 429) {
      return '登录尝试过于频繁，请稍后再试'
    }
  }
  return '登录服务暂时不可用，请稍后重试'
}

function resolveRedirect(): string {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  // 只接受站内路径，拒绝 //host 与 /\host 形式的外部跳转
  return redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.startsWith('/\\')
    ? redirect
    : '/'
}

async function submit() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await session.login(form.username, form.password)
    await router.replace(resolveRedirect())
  } catch (error) {
    errorMessage.value = describeError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <form
      class="login-card"
      data-testid="login-form"
      @submit.prevent="submit"
    >
      <h1>车辆监控系统</h1>
      <p class="subtitle">
        请使用分配的账号登录
      </p>

      <p
        v-if="reasonMessage"
        class="info"
        role="status"
        data-testid="login-reason"
      >
        {{ reasonMessage }}
      </p>

      <label for="username">用户名</label>
      <input
        id="username"
        v-model="form.username"
        name="username"
        autocomplete="username"
        required
        maxlength="64"
      >

      <label for="password">密码</label>
      <input
        id="password"
        v-model="form.password"
        name="password"
        type="password"
        autocomplete="current-password"
        required
        maxlength="200"
      >

      <p
        v-if="errorMessage"
        class="error"
        role="alert"
        data-testid="login-error"
      >
        {{ errorMessage }}
      </p>

      <button
        type="submit"
        :disabled="submitting"
        data-testid="login-submit"
      >
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  place-items: center;
  min-height: 100vh;
  background: #f4f6f8;
}

.login-card {
  display: grid;
  gap: 0.5rem;
  width: min(360px, 90vw);
  padding: 2rem;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgb(15 35 60 / 10%);
}

h1 {
  margin: 0;
  font-size: 1.375rem;
}

.subtitle {
  margin: 0 0 0.75rem;
  color: #5b6b7c;
  font-size: 0.875rem;
}

label {
  font-size: 0.875rem;
  color: #2c3e50;
}

input {
  padding: 0.55rem 0.7rem;
  border: 1px solid #c7d0d9;
  border-radius: 6px;
  font-size: 0.95rem;
}

input:focus {
  outline: 2px solid #2f6fb3;
  outline-offset: 1px;
}

.error {
  margin: 0;
  color: #b42318;
  font-size: 0.875rem;
}

.info {
  margin: 0 0 0.25rem;
  padding: 0.5rem 0.65rem;
  border-left: 3px solid #2f6fb3;
  background: #eef5fc;
  color: #1f4f7d;
  font-size: 0.85rem;
}

button {
  margin-top: 0.75rem;
  padding: 0.6rem;
  border: 0;
  border-radius: 6px;
  background: #2f6fb3;
  color: #fff;
  font-size: 0.95rem;
  cursor: pointer;
}

button:disabled {
  opacity: 0.7;
  cursor: progress;
}
</style>
