import axios from 'axios'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 该请求的 401 由调用方处理，不触发全局会话失效跳转。 */
    suppressUnauthorized?: boolean
  }
}

/**
 * 会话 token 由 session store 提供，只通过 satoken 请求头传递，不写 URL、Cookie 或日志。
 */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

let tokenProvider: () => string | null = () => null
let unauthorizedHandler: ((code: string | null) => void) | null = null

export function setTokenProvider(provider: () => string | null) {
  tokenProvider = provider
}

/** 会话被替换、到期、撤权或账号停用时统一清理并回到登录页。 */
export function setUnauthorizedHandler(handler: (code: string | null) => void) {
  unauthorizedHandler = handler
}

http.interceptors.request.use((config) => {
  const token = tokenProvider()
  if (token) {
    config.headers.set('satoken', token)
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      const isLogin = error.config?.url?.endsWith('/auth/login')
      // 仅标记了 suppressUnauthorized 的请求（如守卫的会话恢复）由调用方自行处理
      if (!isLogin && !error.config?.suppressUnauthorized) {
        const data = error.response.data as { code?: string } | undefined
        unauthorizedHandler?.(data?.code ?? null)
      }
    }
    return Promise.reject(error)
  },
)

export function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401
}

export function errorCode(error: unknown): string | null {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { code?: string } | undefined
    return data?.code ?? null
  }
  return null
}

export function errorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { detail?: string } | undefined
    if (data?.detail) {
      return data.detail
    }
  }
  return fallback
}
