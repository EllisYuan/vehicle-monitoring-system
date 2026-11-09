import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './app/App.vue'
import { router } from './app/router'
import { useSessionStore } from './features/identity/store/session'
import { setUnauthorizedHandler } from './shared/http'

const pinia = createPinia()

// 会话被替换、到期、撤权或停用时，服务端拒绝旧授权；前端同步清理并回到登录页
setUnauthorizedHandler((code) => {
  const session = useSessionStore(pinia)
  session.clear()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({
      name: 'login',
      query: { reason: code ?? 'unauthorized', redirect: current.fullPath },
    })
  }
})

createApp(App).use(pinia).use(router).mount('#app')
