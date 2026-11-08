import axios from 'axios'

/**
 * 会话 token 由 session store 提供，只通过 Authorization 头传递，不写 URL、Cookie 或日志。
 */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

let tokenProvider: () => string | null = () => null

export function setTokenProvider(provider: () => string | null) {
  tokenProvider = provider
}

http.interceptors.request.use((config) => {
  const token = tokenProvider()
  if (token) {
    config.headers.set('satoken', token)
  }
  return config
})

export function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401
}
