<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ROLE_LABELS, useSessionStore } from '../../features/identity/store/session'

const props = defineProps<{ area: 'monitoring' | 'admin' }>()

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

interface NavItem {
  label: string
  to: string
  testId: string
  roles?: string[]
}

const monitoringNav: NavItem[] = [
  { label: '实时监控', to: '/', testId: 'nav-monitor-home' },
  { label: '车队', to: '/monitor/fleets', testId: 'nav-fleets' },
  { label: '车辆与终端', to: '/monitor/archives', testId: 'nav-archives' },
  { label: '司机', to: '/monitor/drivers', testId: 'nav-drivers' },
  { label: '轨迹回放', to: '/monitor/tracks', testId: 'nav-tracks' },
  { label: '报警管理', to: '/monitor/alarms', testId: 'nav-alarms' },
  { label: '电子围栏', to: '/monitor/geofences', testId: 'nav-geofences', roles: ['ADMIN', 'MONITOR'] },
  { label: '指令记录', to: '/monitor/commands', testId: 'nav-commands', roles: ['ADMIN', 'MONITOR'] },
  { label: '统计', to: '/monitor/stats', testId: 'nav-stats' },
]

const adminNav: NavItem[] = [
  { label: '车队管理', to: '/admin/fleets', testId: 'nav-admin-fleets' },
  { label: '车辆档案', to: '/admin/archives', testId: 'nav-admin-archives' },
  { label: '司机档案', to: '/admin/drivers', testId: 'nav-admin-drivers' },
  { label: '报警规则', to: '/admin/alarm-rules', testId: 'nav-admin-alarm-rules' },
  { label: '统计报表', to: '/admin/reports', testId: 'nav-admin-reports' },
  { label: '账号管理', to: '/admin/users', testId: 'nav-admin-users' },
  { label: '系统审计', to: '/admin/audit', testId: 'nav-admin-audit' },
]

const role = computed(() => session.role)

const items = computed(() =>
  (props.area === 'monitoring' ? monitoringNav : adminNav).filter(
    (item) => !item.roles || (role.value !== null && item.roles.includes(role.value)),
  ),
)

const roleLabel = computed(() => (role.value ? ROLE_LABELS[role.value] : ''))

async function logout() {
  await session.logout()
  await router.replace({ name: 'login' })
}

function isActive(item: NavItem): boolean {
  return route.path === item.to
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-title">车辆监控系统</span>
        <span
          class="area-badge"
          data-testid="layout-area"
        >{{ area === 'monitoring' ? '监控端' : '管理端' }}</span>
      </div>
      <nav class="nav">
        <RouterLink
          v-for="item in items"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ active: isActive(item) }"
          :data-testid="item.testId"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
      <div class="account">
        <span data-testid="current-user">{{ session.user?.username }}（{{ roleLabel }}）</span>
        <RouterLink
          v-if="session.isAdmin && area === 'monitoring'"
          to="/admin/fleets"
          class="switch-link"
          data-testid="switch-to-admin"
        >
          管理端
        </RouterLink>
        <RouterLink
          v-if="session.isAdmin && area === 'admin'"
          to="/"
          class="switch-link"
          data-testid="switch-to-monitoring"
        >
          监控端
        </RouterLink>
        <RouterLink
          :to="{ name: 'change-password', query: { area } }"
          class="switch-link"
          data-testid="change-password"
        >
          修改密码
        </RouterLink>
        <button
          type="button"
          data-testid="logout"
          @click="logout"
        >
          退出登录
        </button>
      </div>
    </header>
    <main class="content">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: #f4f6f8;
  color: #1f2d3d;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  padding: 0.6rem 1.5rem;
  background: #1f2d3d;
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  white-space: nowrap;
}

.brand-title {
  font-weight: 600;
}

.area-badge {
  padding: 0.1rem 0.45rem;
  border-radius: 4px;
  background: rgb(255 255 255 / 15%);
  font-size: 0.75rem;
}

.nav {
  display: flex;
  gap: 0.25rem;
  flex: 1;
  overflow-x: auto;
}

.nav-link {
  padding: 0.35rem 0.6rem;
  border-radius: 4px;
  color: #c9d4df;
  text-decoration: none;
  font-size: 0.875rem;
  white-space: nowrap;
}

.nav-link:hover {
  background: rgb(255 255 255 / 10%);
  color: #fff;
}

.nav-link.active {
  background: #2f6fb3;
  color: #fff;
}

.account {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.85rem;
  white-space: nowrap;
}

.switch-link {
  color: #cfe2f7;
  text-decoration: none;
}

.switch-link:hover {
  color: #fff;
  text-decoration: underline;
}

button {
  padding: 0.3rem 0.7rem;
  border: 1px solid rgb(255 255 255 / 40%);
  border-radius: 4px;
  background: transparent;
  color: #fff;
  cursor: pointer;
}

button:hover {
  background: rgb(255 255 255 / 10%);
}
</style>
