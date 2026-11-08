import { defineStore } from 'pinia'
import { http, setTokenProvider } from '../../../shared/http'

export interface CurrentUser {
  id: number
  username: string
  roleCode: string
}

interface LoginResponse {
  token: string
  username: string
  roleCode: string
  expiresIn: number
}

const TOKEN_KEY = 'vms.session.token'

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
  },
  actions: {
    async login(username: string, password: string) {
      const { data } = await http.post<LoginResponse>('/auth/login', { username, password })
      this.token = data.token
      storeToken(data.token)
      return this.fetchMe()
    },

    async fetchMe(): Promise<CurrentUser> {
      const { data } = await http.get<CurrentUser>('/auth/me')
      this.user = data
      return data
    },

    async restore(): Promise<boolean> {
      if (!this.token) {
        return false
      }
      try {
        await this.fetchMe()
        return true
      } catch {
        this.clear()
        return false
      }
    },

    async logout() {
      try {
        await http.post('/auth/logout')
      } finally {
        this.clear()
      }
    },

    clear() {
      this.token = null
      this.user = null
      storeToken(null)
    },
  },
})

setTokenProvider(() => useSessionStore().token)
