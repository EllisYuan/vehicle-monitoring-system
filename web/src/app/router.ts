import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../features/identity/store/session'

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
      name: 'home',
      component: () => import('../features/monitoring/views/HomeView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const session = useSessionStore()
  if (!session.isAuthenticated && !(await session.restore())) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})
