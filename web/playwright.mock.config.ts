import { defineConfig, devices } from '@playwright/test'

// 17：前端实时订阅状态与版本顺序的浏览器测试；由 Playwright 自行启动 Vite，不依赖真实后端。
const WEB_PORT = Number(process.env.VMS_MOCK_WEB_PORT ?? 5174)

export default defineConfig({
  testDir: './e2e',
  testMatch: 'monitoring-stream.spec.ts',
  timeout: 45_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `pnpm dev --port ${WEB_PORT} --strictPort`,
    url: `http://localhost:${WEB_PORT}`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...(process.env.VMS_E2E_CHROMIUM ? { launchOptions: { executablePath: process.env.VMS_E2E_CHROMIUM } } : {}),
      },
    },
  ],
})