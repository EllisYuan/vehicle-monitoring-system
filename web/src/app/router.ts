import { createRouter, createWebHistory } from 'vue-router'
import type { RoleCode } from '../features/identity/store/session'
import { useSessionStore } from '../features/identity/store/session'

const PlaceholderView = () => import('../app/views/PlaceholderView.vue')

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../features/identity/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      name: 'monitor-home',
      component: () => import('../features/monitoring/views/HomeView.vue'),
    },
    {
      path: '/monitor/fleets',
      name: 'monitor-fleets',
      component: () => import('../features/fleets/views/FleetListView.vue'),
      meta: { area: 'monitoring' },
    },
    {
      path: '/monitor/archives',
      name: 'monitor-archives',
      component: () => import('../features/archives/views/ArchiveListView.vue'),
      meta: { area: 'monitoring' },
    },
    {
      path: '/monitor/drivers',
      name: 'monitor-drivers',
      component: () => import('../features/drivers/views/DriverListView.vue'),
      meta: { area: 'monitoring' },
    },
    {
      path: '/monitor/tracks',
      name: 'tracks',
      component: PlaceholderView,
      meta: {
        title: '轨迹回放',
        description: '按车辆与时间范围查询轨迹、分段与回放将在轨迹任务中交付。',
        area: 'monitoring',
        ticket: '10 单车轨迹查询与回放',
      },
    },
    {
      path: '/monitor/alarms',
      name: 'alarms',
      component: PlaceholderView,
      meta: {
        title: '报警管理',
        description: '报警列表、详情与处置将在报警任务中交付；车队长只读查看授权范围。',
        area: 'monitoring',
        ticket: '13 报警处置闭环',
      },
    },
    {
      path: '/monitor/geofences',
      name: 'geofences',
      component: PlaceholderView,
      meta: {
        title: '电子围栏',
        description: '围栏绘制、车辆绑定与进出事件在围栏任务中交付，仅管理员和监控员可操作。',
        area: 'monitoring',
        roles: ['ADMIN', 'MONITOR'] satisfies RoleCode[],
        ticket: '15 围栏绘制与进出事件',
      },
    },
    {
      path: '/monitor/commands',
      name: 'commands',
      component: PlaceholderView,
      meta: {
        title: '指令记录',
        description: '文本与临时跟踪指令的提交、应答与超时在指令任务中交付，车队长不可下发。',
        area: 'monitoring',
        roles: ['ADMIN', 'MONITOR'] satisfies RoleCode[],
        ticket: '16 文本与临时跟踪指令',
      },
    },
    {
      path: '/monitor/stats',
      name: 'stats',
      component: PlaceholderView,
      meta: {
        title: '监控统计',
        description: '在线、报警与处理率统计在统计任务中交付，按角色数据范围过滤。',
        area: 'monitoring',
        ticket: '19 有权限的监控统计',
      },
    },
    {
      path: '/account/password',
      name: 'change-password',
      component: () => import('../features/identity/views/ChangePasswordView.vue'),
    },
    {
      path: '/admin/fleets',
      name: 'admin-fleets',
      component: () => import('../features/fleets/views/FleetListView.vue'),
      meta: { roles: ['ADMIN'] satisfies RoleCode[], area: 'admin' },
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('../features/accounts/views/AccountListView.vue'),
      meta: { roles: ['ADMIN'] satisfies RoleCode[], area: 'admin' },
    },
    {
      path: '/admin/archives',
      name: 'admin-archives',
      component: () => import('../features/archives/views/ArchiveListView.vue'),
      meta: { area: 'admin', roles: ['ADMIN'] satisfies RoleCode[] },
    },
    {
      path: '/admin/drivers',
      name: 'admin-drivers',
      component: () => import('../features/drivers/views/DriverListView.vue'),
      meta: { area: 'admin', roles: ['ADMIN'] satisfies RoleCode[] },
    },
    {
      path: '/admin/alarm-rules',
      name: 'admin-alarm-rules',
      component: PlaceholderView,
      meta: {
        title: '报警规则',
        description: '全局默认与车队覆盖两级报警规则在报警任务中交付，仅管理员可维护。',
        area: 'admin',
        roles: ['ADMIN'] satisfies RoleCode[],
        ticket: '12 可配置超速报警与持续事件',
      },
    },
    {
      path: '/admin/reports',
      name: 'admin-reports',
      component: PlaceholderView,
      meta: {
        title: '统计报表与导出',
        description: '有权限的统计聚合与三类 Excel 导出在后续任务中交付。',
        area: 'admin',
        roles: ['ADMIN'] satisfies RoleCode[],
        ticket: '19 统计、20 导出',
      },
    },
    {
      path: '/admin/audit',
      name: 'admin-audit',
      component: PlaceholderView,
      meta: {
        title: '系统审计',
        description: '登录、账号与授权变更审计的查询页面在审计任务中交付；本期已写入审计。',
        area: 'admin',
        roles: ['ADMIN'] satisfies RoleCode[],
        ticket: '21 管理员审计与运行记录查询',
      },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const session = useSessionStore()
  if (!session.isAuthenticated) {
    const restored = await session.restore()
    if (restored !== 'authenticated') {
      const query: Record<string, string> = { redirect: to.fullPath }
      if (restored === 'unavailable') {
        // 后端暂时不可用：token 仍保留，登录页提示稍后重试而非会话已失效
        query.reason = 'service-unavailable'
      }
      return { name: 'login', query }
    }
  }
  const roles = to.meta.roles as RoleCode[] | undefined
  if (roles && session.user && !roles.includes(session.user.roleCode)) {
    // 隐藏菜单不是权限依据：越权路由在客户端也回到默认监控页，服务端另有强制校验
    return { path: '/' }
  }
  return true
})
