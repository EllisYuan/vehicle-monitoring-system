<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useSessionStore } from '../../identity/store/session'

const session = useSessionStore()
const router = useRouter()

async function logout() {
  await session.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <main class="home">
    <header>
      <span data-testid="current-user">{{ session.user?.username }}（{{ session.user?.roleCode }}）</span>
      <button
        type="button"
        data-testid="logout"
        @click="logout"
      >
        退出登录
      </button>
    </header>
    <section>
      <h1>实时监控</h1>
      <p>监控、轨迹与报警页面将在后续任务交付；当前仅验证登录会话与退出。</p>
    </section>
  </main>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: #f4f6f8;
}

header {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 1rem;
  padding: 0.75rem 1.5rem;
  background: #fff;
  border-bottom: 1px solid #e2e8ee;
  font-size: 0.9rem;
}

section {
  padding: 1.5rem;
}

h1 {
  margin: 0 0 0.5rem;
  font-size: 1.25rem;
}

p {
  margin: 0;
  color: #5b6b7c;
}

button {
  padding: 0.4rem 0.8rem;
  border: 1px solid #c7d0d9;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
}
</style>
