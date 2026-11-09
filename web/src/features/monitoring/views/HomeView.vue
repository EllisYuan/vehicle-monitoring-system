<script setup lang="ts">
import { useSessionStore } from '../../identity/store/session'
import AppShell from '../../../app/layouts/AppShell.vue'

const session = useSessionStore()
</script>

<template>
  <AppShell area="monitoring">
    <section class="page">
      <h1>实时监控</h1>
      <p>地图、车辆列表与实时状态将在后续任务交付；当前已接入登录、角色导航与车队数据范围。</p>
      <p
        v-if="session.role === 'FLEET_LEADER'"
        class="scope-note"
        data-testid="leader-scope-note"
      >
        当前账号为车队长：仅能查看被授权车队的车辆、轨迹、报警和统计，且不可处置报警或下发指令。
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

.scope-note {
  padding: 0.6rem 0.8rem;
  border-left: 3px solid #d69e2e;
  background: #fffaf0;
  color: #744210;
}
</style>
