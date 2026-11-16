import { expect, test, type APIRequestContext } from '@playwright/test'

const ADMIN_USERNAME = process.env.VMS_E2E_USERNAME ?? ''
const ADMIN_PASSWORD = process.env.VMS_E2E_PASSWORD ?? ''

const suffix = Date.now().toString(36)
const ghostUser = `e2e-audit-ghost-${suffix}`
const monitorUser = `e2e-audit-monitor-${suffix}`
const monitorPassword = 'Monitor-Pass-2026'

async function login(request: APIRequestContext, username: string, password: string) {
  const response = await request.post('/api/v1/auth/login', { data: { username, password } })
  expect(response.ok()).toBeTruthy()
  return ((await response.json()) as { token: string }).token
}

async function api(
  request: APIRequestContext,
  token: string,
  method: 'GET' | 'POST',
  path: string,
  data?: object,
) {
  return request.fetch(`/api/v1${path}`, {
    method,
    headers: { satoken: token },
    data,
  })
}

async function loginAsAdmin(page: import('@playwright/test').Page) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(ADMIN_USERNAME)
  await page.getByLabel('密码').fill(ADMIN_PASSWORD)
  await page.getByTestId('login-submit').click()
  await page.getByTestId('switch-to-admin').click()
  await page.getByTestId('nav-admin-audit').click()
  await expect(page.getByTestId('audit-table')).toBeVisible()
}

test.describe('管理员审计与运行记录查询', () => {
  test.skip(!ADMIN_USERNAME || !ADMIN_PASSWORD, '需要 VMS_E2E_USERNAME / VMS_E2E_PASSWORD 指向真实管理员')

  test.beforeAll(async ({ request }) => {
    const adminToken = await login(request, ADMIN_USERNAME, ADMIN_PASSWORD)
    // 登录失败审计由认证流程实际写入；业务审计由车队创建实际写入
    const badLogin = await api(request, adminToken, 'POST', '/auth/login', {
      username: ghostUser,
      password: 'Wrong-Pass-2026',
    })
    expect(badLogin.status()).toBe(401)
    const fleet = await api(request, adminToken, 'POST', '/fleets', {
      fleetCode: `E2E-AUD-${suffix}`,
      fleetName: `审计车队 ${suffix}`,
    })
    expect(fleet.status()).toBe(201)
    expect((await api(request, adminToken, 'POST', '/users', {
      username: monitorUser,
      password: monitorPassword,
      roleCode: 'MONITOR',
      fleetIds: [],
    })).status()).toBe(201)
  })

  test('管理员筛选登录失败审计并确认只读与脱敏', async ({ page }) => {
    await loginAsAdmin(page)

    await page.getByTestId('audit-actor').fill(ghostUser)
    await page.getByTestId('audit-result').selectOption('FAILURE')
    await page.getByTestId('audit-search').click()

    await expect(page.getByTestId('audit-total')).toHaveText('共 1 条')
    const row = page.getByTestId('audit-table').locator('tbody tr').first()
    await expect(row).toContainText('LOGIN')
    await expect(row).toContainText(ghostUser)
    await expect(row).toContainText('FAILURE')
    await expect(row).toContainText('UNKNOWN_ACCOUNT')
    await expect(page.getByTestId('audit-table')).not.toContainText('Wrong-Pass-2026')
    // 只读：表格行内没有操作按钮，页面也没有删除或修改入口
    await expect(page.getByTestId('audit-table').getByRole('button')).toHaveCount(0)
    await expect(page.getByTestId('audit-delete')).toHaveCount(0)
    await expect(page.getByTestId('audit-edit')).toHaveCount(0)
    await page.screenshot({ path: 'test-results/admin-audit-page.png', fullPage: true })
  })

  test('运行记录展示 outbox 状态计数与 DEAD 表', async ({ page }) => {
    await loginAsAdmin(page)
    await expect(page.getByTestId('audit-outbox-counts')).toBeVisible()
    await expect(page.getByTestId('audit-outbox-counts')).toContainText('DEAD')
    await expect(page.getByTestId('audit-dead-table')).toBeVisible()
    await expect(page.getByTestId('audit-dead-table')).toContainText('DEAD 状态的 outbox 事件')
  })

  test('监控员页面与接口均不能访问审计', async ({ page, request }) => {
    const monitorToken = await login(request, monitorUser, monitorPassword)
    const rejected = await api(request, monitorToken, 'GET', '/audit/logs')
    expect(rejected.status()).toBe(403)

    await page.goto('/login')
    await page.getByLabel('用户名').fill(monitorUser)
    await page.getByLabel('密码').fill(monitorPassword)
    await page.getByTestId('login-submit').click()
    await expect(page.getByTestId('layout-area')).toHaveText('监控端')
    await expect(page.getByTestId('nav-admin-audit')).toHaveCount(0)

    await page.goto('/admin/audit')
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByTestId('audit-table')).toHaveCount(0)
  })
})
