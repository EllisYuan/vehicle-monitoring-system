<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../../../shared/http'
import { useSessionStore } from '../store/session'
import AppShell from '../../../app/layouts/AppShell.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

// 只有管理员从管理端进入时沿用管理布局；其他角色固定监控布局，避免带 area=admin 看到管理导航
const area = computed<'monitoring' | 'admin'>(() =>
  session.isAdmin && route.query.area === 'admin' ? 'admin' : 'monitoring',
)

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const submitting = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (form.newPassword !== form.confirmPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }
  submitting.value = true
  try {
    await session.changePassword(form.oldPassword, form.newPassword)
    await router.replace({ name: 'login', query: { reason: 'password-changed' } })
  } catch (e) {
    error.value = errorMessage(e, '密码修改失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppShell :area="area">
    <section class="page">
      <h1>修改密码</h1>
      <p class="hint">
        修改成功后该账号的全部会话立即失效，需要使用新密码重新登录。
      </p>
      <form
        class="card"
        data-testid="change-password-form"
        @submit.prevent="submit"
      >
        <label>
          原密码
          <input
            v-model="form.oldPassword"
            type="password"
            data-testid="old-password"
            autocomplete="current-password"
            required
          >
        </label>
        <label>
          新密码
          <input
            v-model="form.newPassword"
            type="password"
            data-testid="new-password"
            autocomplete="new-password"
            minlength="8"
            maxlength="200"
            required
          >
        </label>
        <label>
          确认新密码
          <input
            v-model="form.confirmPassword"
            type="password"
            data-testid="confirm-password"
            autocomplete="new-password"
            minlength="8"
            maxlength="200"
            required
          >
        </label>
        <p
          v-if="error"
          class="error"
          role="alert"
          data-testid="change-password-error"
        >
          {{ error }}
        </p>
        <button
          type="submit"
          data-testid="change-password-submit"
          :disabled="submitting"
        >
          {{ submitting ? '提交中…' : '确认修改' }}
        </button>
      </form>
    </section>
  </AppShell>
</template>

<style scoped>
.page {
  padding: 1.5rem;
}

h1 {
  margin: 0 0 0.5rem;
  font-size: 1.25rem;
}

.hint {
  margin: 0 0 1rem;
  color: #5b6b7c;
  font-size: 0.875rem;
}

.card {
  display: grid;
  gap: 0.75rem;
  max-width: 360px;
  padding: 1.25rem;
  background: #fff;
  border: 1px solid #e2e8ee;
  border-radius: 8px;
}

label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: #2c3e50;
}

input {
  padding: 0.45rem 0.6rem;
  border: 1px solid #c7d0d9;
  border-radius: 6px;
  font-size: 0.9rem;
}

.error {
  margin: 0;
  color: #b42318;
  font-size: 0.85rem;
}

button {
  padding: 0.5rem 0.9rem;
  border: 1px solid #2f6fb3;
  border-radius: 6px;
  background: #2f6fb3;
  color: #fff;
  cursor: pointer;
}

button:disabled {
  opacity: 0.7;
  cursor: progress;
}
</style>
