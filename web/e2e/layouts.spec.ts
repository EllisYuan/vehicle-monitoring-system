import { expect, test, type APIRequestContext } from '@playwright/test'

const ADMIN_USERNAME = process.env.VMS_E2E_USERNAME ?? ''
const ADMIN_PASSWORD = process.env.VMS_E2E_PASSWORD ?? ''

const suffix = Date.now().toString(36)
const fleetA = `E2E-A-${suffix}`
const fleetB = `E2E-B-${suffix}`
const monitorUser = `e2e-monitor-${suffix}`
const leaderUser = `e2e-leader-${suffix}`
const adminUser = `e2e-admin-${suffix}`
const monitorPassword = 'Monitor-Pass-2026'
const leaderPassword = 'Leader-Pass-2026'
const adminPassword = 'Admin-Pass-2026'
const adminNewPassword = 'Admin-Pass-2026-new'

async function login(request: APIRequestContext, username: string, password: string) {
  const response = await request.post('/api/v1/auth/login', { data: { username, password } })
  expect(response.ok()).toBeTruthy()
  const body = (await response.json()) as { token: string }
  return body.token
}

async function api(request: APIRequestContext, token: string, method: 'GET' | 'POST' | 'PUT', path: string, data?: object) {
  const response = await request.fetch(`/api/v1${path}`, {
    method,
    headers: { satoken: token },
    data,
  })
  return response
}

test.describe('两布局导航与三角色权限', () => {
  test.skip(!ADMIN_USERNAME || !ADMIN_PASSWORD, '需要 VMS_E2E_USERNAME / VMS_E2E_PASSWORD 指向真实管理员')

  let adminToken = ''

  test.beforeAll(async ({ request }) => {
    adminToken = await login(request, ADMIN_USERNAME, ADMIN_PASSWORD)
    const fleetAResponse = await api(request, adminToken, 'POST', '/fleets', {
      fleetCode: fleetA,
      fleetName: `E2E 一队 ${suffix}`,
    })
    const fleetBResponse = await api(request, adminToken, 'POST', '/fleets', {
      fleetCode: fleetB,
      fleetName: `E2E 二队 ${suffix}`,
    })
    expect(fleetAResponse.status()).toBe(201)
    expect(fleetBResponse.status()).toBe(201)
    const fleetAId = ((await fleetAResponse.json()) as { id: number }).id
    const fleetBId = ((await fleetBResponse.json()) as { id: number }).id

    expect((await api(request, adminToken, 'POST', '/users', {
      username: monitorUser,
      password: monitorPassword,
      roleCode: 'MONITOR',
      fleetIds: [],
    })).status()).toBe(201)
    expect((await api(request, adminToken, 'POST', '/users', {
      username: leaderUser,
      password: leaderPassword,
      roleCode: 'FLEET_LEADER',
      fleetIds: [fleetAId, fleetBId],
    })).status()).toBe(201)
    expect((await api(request, adminToken, 'POST', '/users', {
      username: adminUser,
      password: adminPassword,
      roleCode: 'ADMIN',
      fleetIds: [],
    })).status()).toBe(201)
  })

  test('管理员可切换管理端并维护车队与账号页面', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(ADMIN_USERNAME)
    await page.getByLabel('密码').fill(ADMIN_PASSWORD)
    await page.getByTestId('login-submit').click()

    await expect(page.getByTestId('layout-area')).toHaveText('监控端')
    await page.getByTestId('switch-to-admin').click()
    await expect(page.getByTestId('layout-area')).toHaveText('管理端')
    await expect(page.getByTestId('fleet-table')).toBeVisible()
    await expect(page.getByTestId(`fleet-row-${fleetA}`)).toBeVisible()

    await page.getByTestId('nav-admin-users').click()
    await expect(page.getByTestId('account-table')).toBeVisible()
    await expect(page.getByTestId(`account-row-${leaderUser}`)).toBeVisible()
    await page.screenshot({ path: 'test-results/admin-account-page.png', fullPage: true })
  })

  test('管理员改密沿用管理布局，且不能停用自己', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(adminUser)
    await page.getByLabel('密码').fill(adminPassword)
    await page.getByTestId('login-submit').click()

    await page.getByTestId('switch-to-admin').click()
    await page.getByTestId('nav-admin-users').click()
    await expect(page.getByTestId('account-table')).toBeVisible()
    await expect(page.getByTestId(`account-row-${adminUser}`)).toBeVisible()
    // 自己的停用按钮不可见，其他账号仍可操作
    await expect(page.getByTestId(`account-toggle-${adminUser}`)).toHaveCount(0)
    await expect(page.getByTestId(`account-toggle-${leaderUser}`)).toBeVisible()

    await page.getByTestId('change-password').click()
    await expect(page).toHaveURL(/\/account\/password\?area=admin/)
    await expect(page.getByTestId('layout-area')).toHaveText('管理端')
    await expect(page.getByTestId('nav-admin-users')).toBeVisible()

    await page.getByTestId('old-password').fill(adminPassword)
    await page.getByTestId('new-password').fill(adminNewPassword)
    await page.getByTestId('confirm-password').fill(adminNewPassword)
    await page.getByTestId('change-password-submit').click()

    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByTestId('login-reason')).toContainText('密码已修改')
  })

  test('监控员只有监控导航，不能进入管理端', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(monitorUser)
    await page.getByLabel('密码').fill(monitorPassword)
    await page.getByTestId('login-submit').click()

    await expect(page.getByTestId('layout-area')).toHaveText('监控端')
    await expect(page.getByTestId('nav-geofences')).toBeVisible()
    await expect(page.getByTestId('nav-commands')).toBeVisible()
    await expect(page.getByTestId('switch-to-admin')).toHaveCount(0)

    await page.goto('/admin/users')
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByTestId('layout-area')).toHaveText('监控端')
  })

  test('监控员携带 area=admin 进入改密页仍使用监控布局', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(monitorUser)
    await page.getByLabel('密码').fill(monitorPassword)
    await page.getByTestId('login-submit').click()

    await expect(page.getByTestId('current-user')).toContainText(monitorUser)
    await page.goto('/account/password?area=admin')
    await expect(page.getByTestId('layout-area')).toHaveText('监控端')
    await expect(page.getByTestId('nav-admin-users')).toHaveCount(0)
    await expect(page.getByTestId('nav-geofences')).toBeVisible()
  })

  test('车队长导航不含处置类功能，直接改 URL 也被拒绝', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(leaderUser)
    await page.getByLabel('密码').fill(leaderPassword)
    await page.getByTestId('login-submit').click()

    await expect(page.getByTestId('current-user')).toContainText('车队长')
    await expect(page.getByTestId('leader-scope-note')).toBeVisible()
    await expect(page.getByTestId('nav-geofences')).toHaveCount(0)
    await expect(page.getByTestId('nav-commands')).toHaveCount(0)
    await expect(page.getByTestId('switch-to-admin')).toHaveCount(0)

    // 车队长只看到被授权车队的车队列表，且为只读（无新增与停用操作）
    await page.getByTestId('nav-fleets').click()
    await expect(page.getByTestId(`fleet-row-${fleetA}`)).toBeVisible()
    await expect(page.getByTestId(`fleet-row-${fleetB}`)).toBeVisible()
    await expect(page.getByTestId('fleet-create-form')).toHaveCount(0)

    await page.goto('/monitor/commands')
    await expect(page).toHaveURL(/\/$/)

    await page.goto('/admin/fleets')
    await expect(page).toHaveURL(/\/$/)
  })

  test('后登录替换旧会话并在旧页面给出明确提示', async ({ page, request }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(ADMIN_USERNAME)
    await page.getByLabel('密码').fill(ADMIN_PASSWORD)
    await page.getByTestId('login-submit').click()
    await page.getByTestId('switch-to-admin').click()
    await expect(page.getByTestId('fleet-table')).toBeVisible()

    // 同一账号在另一来源登录：服务端挤掉旧会话
    await login(request, ADMIN_USERNAME, ADMIN_PASSWORD)

    await page.getByTestId('nav-admin-users').click()
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByTestId('login-reason')).toContainText('其他位置登录')
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/admin/users')
  })
})
