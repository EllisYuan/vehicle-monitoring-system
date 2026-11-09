<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../layouts/AppShell.vue'

const route = useRoute()

const title = computed(() => (route.meta.title as string | undefined) ?? '功能')
const description = computed(
  () => (route.meta.description as string | undefined) ?? '该功能将在后续任务交付。',
)
const area = computed(() => ((route.meta.area as 'monitoring' | 'admin' | undefined) ?? 'monitoring'))
const ticket = computed(() => (route.meta.ticket as string | undefined) ?? '')
</script>

<template>
  <AppShell :area="area">
    <section class="page">
      <h1 data-testid="placeholder-title">
        {{ title }}
      </h1>
      <p>{{ description }}</p>
      <p
        v-if="ticket"
        class="pending"
      >
        交付任务：{{ ticket }}
      </p>
      <p class="hint">
        本页为导航与权限骨架的占位页面；服务端已按角色与数据范围校验，隐藏菜单不构成权限依据。
      </p>
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

p {
  margin: 0 0 0.5rem;
  color: #5b6b7c;
}

.pending {
  color: #2f6fb3;
}

.hint {
  margin-top: 1rem;
  font-size: 0.85rem;
  color: #8496a8;
}
</style>
