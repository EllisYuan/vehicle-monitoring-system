import { defineStore } from 'pinia'
import { http, isUnauthorized, setTokenProvider } from '../../../shared/http'

export type RoleCode = 'ADMIN' | 'MONITOR' | 'FLEET_LEADER'

export type RestoreResult = 'authenticated' | 'unauthenticated' | 'unavailable'

export interface CurrentUser {
  id: number
  username: string
  roleCode: RoleCode
}

interface LoginResponse {
  token: string
  username: string
  roleCode: RoleCode
  expiresIn: number
}

const TOKEN_KEY = 'vms.session.token'

export const ROLE_LABELS: Record<RoleCode, string> = {
  ADMIN: '管理员',
  MONITOR: '监控员',
  FLEET_LEADER: '车队长',
}

function readStoredToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

function storeToken(token: string | null) {
  try {
    if (token === null) {
      sessionStorage.removeItem(TOKEN_KEY)
    } else {
      sessionStorage.setItem(TOKEN_KEY, token)
    }
  } catch {
    // 存储被禁用时仅保留内存会话
  }
}

export const useSessionStore = defineStore('session', {
  state: () => ({
    token: readStoredToken(),
    user: null as CurrentUser | null,
  }),
  getters: {
    isAuthenticated: (state) => state.token !== null && state.user !== null,
    role: (state): RoleCode | null => state.user?.roleCode ?? null,
    isAdmin: (state): boolean => state.user?.roleCode === 'ADMIN',
    canDisposeOrCommand: (state): boolean =>
      state.user?.roleCode === 'ADMIN' || state.user?.roleCode === 'MONITOR',
  },
  actions: {
    async login(username: string, password: string) {
      const { data } = await http.post<LoginResponse>('/auth/login', { username, password })
      this.token = data.token
      storeToken(data.token)
      return this.fetchMe()
    },

    async fetchMe(options: { suppressUnauthorized?: boolean } = {}): Promise<CurrentUser> {
      const { data } = await http.get<CurrentUser>('/auth/me', options)
      this.user = data
      return data
    },

    async restore(): Promise<RestoreResult> {
      if (!this.token) {
        return 'unauthenticated'
      }
      try {
        // 401 由守卫统一处理，避免全局处理器与守卫同时导航、丢失深链 redirect
        await this.fetchMe({ suppressUnauthorized: true })
        return 'authenticated'
      } catch (e) {
        if (isUnauthorized(e)) {
          this.clear()
          return 'unauthenticated'
        }
        // 后端短暂不可用或网络错误：保留 token，由登录页提示稍后重试，不当作会话失效
        return 'unavailable'
      }
    },

    async logout() {
      try {
        await http.post('/auth/logout')
      } catch {
        // 网络失败也要完成本地退出；服务端会话到期后自然失效
      }
      this.clear()
    },

    async changePassword(oldPassword: string, newPassword: string) {
      await http.post('/auth/password', { oldPassword, newPassword })
      // 服务端已撤销该账号全部会话
      this.clear()
    },

    clear() {
      this.token = null
      this.user = null
      storeToken(null)
    },
  },
})

setTokenProvider(() => useSessionStore().token)
