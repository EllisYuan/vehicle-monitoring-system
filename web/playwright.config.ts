import { defineConfig, devices } from '@playwright/test'

const WEB_PORT = Number(process.env.VMS_E2E_WEB_PORT ?? 5173)

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  // 账号单有效会话：并行 worker 互相挤掉登录态，按文件串行执行
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'retain-on-failure',
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
