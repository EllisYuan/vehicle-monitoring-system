<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isUnauthorized } from '../../../shared/http'
import { useSessionStore } from '../store/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await session.login(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = isUnauthorized(error) ? '用户名或密码错误' : '登录服务暂时不可用，请稍后重试'
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
        请使用分配的管理员账号登录
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
