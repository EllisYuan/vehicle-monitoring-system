import { expect, test, type Page } from '@playwright/test'

const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const suffix = Date.now().toString(36)

async function signIn(page: Page, name: string, secret: string) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(name)
  await page.getByLabel('密码', { exact: true }).fill(secret)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('current-user')).toContainText(name)
}

async function tokenFor(page: Page) {
  const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
  if (!token) throw new Error('登录后缺少会话')
  return token
}

test.describe('车辆、终端档案与历史绑定', () => {
  test.skip(!username || !password, '需要真实测试后端管理员')

  test('管理员通过页面登记、启停、换绑、解绑、转队并保留历史', async ({ page, request }) => {
    await signIn(page, username, password)
    await page.getByTestId('switch-to-admin').click()
    for (const code of [`ARC-A-${suffix}`, `ARC-B-${suffix}`]) {
      await page.getByTestId('fleet-create-code').fill(code)
      await page.getByTestId('fleet-create-name').fill(`模拟车队 ${code}`)
      await page.getByTestId('fleet-create-submit').click()
      await expect(page.getByTestId(`fleet-row-${code}`)).toBeVisible()
    }
    await page.getByTestId(`fleet-edit-ARC-A-${suffix}`).click()
    await page.getByTestId('fleet-edit-name').fill(`模拟一队 ${suffix}`)
    await page.getByTestId('fleet-edit-save').click()
    await expect(page.getByTestId(`fleet-row-ARC-A-${suffix}`)).toContainText(`模拟一队 ${suffix}`)
    await page.getByTestId(`fleet-toggle-ARC-A-${suffix}`).click()
    await expect(page.getByTestId(`fleet-toggle-ARC-A-${suffix}`)).toHaveText('启用')
    await page.getByTestId(`fleet-toggle-ARC-A-${suffix}`).click()
    await expect(page.getByTestId(`fleet-toggle-ARC-A-${suffix}`)).toHaveText('停用')
    const token = await tokenFor(page)
    const headers = { satoken: token }
    const fleetsResponse = await request.get('/api/v1/fleets', { headers })
    expect(fleetsResponse.ok()).toBeTruthy()
    const fleets = await fleetsResponse.json() as { id: number; fleetCode: string }[]
    const a = fleets.find(f => f.fleetCode === `ARC-A-${suffix}`)!.id
    const b = fleets.find(f => f.fleetCode === `ARC-B-${suffix}`)!.id

    await page.getByTestId('nav-admin-archives').click()
    await page.getByTestId('vehicle-create-plate').fill(`模拟-${suffix}`)
    await page.getByTestId('vehicle-create-fleet').selectOption(String(a))
    await page.getByTestId('vehicle-create-submit').click()
    const row = page.getByTestId(`vehicle-row-模拟-${suffix}`)
    await expect(row).toBeVisible()
    const numbers = [`${Date.now()}0000001`, `${Date.now()}0000002`]
    for (const number of numbers) {
      await page.getByTestId('terminal-create-number').fill(number)
      await page.getByTestId('terminal-create-submit').click()
      await expect(page.getByTestId(`terminal-row-${number}`)).toBeVisible()
    }
    const vehicleResponse = await request.get(`/api/v1/vehicles?fleetId=${a}`, { headers })
    expect(vehicleResponse.ok()).toBeTruthy()
    const vehicles = await vehicleResponse.json() as { id: number; plateNo: string }[]
    const vehicle = vehicles.find(v => v.plateNo === `模拟-${suffix}`)!.id
    const terminalsResponse = await request.get('/api/v1/terminals', { headers })
    expect(terminalsResponse.ok()).toBeTruthy()
    const terminals = await terminalsResponse.json() as { id: number; terminalNo: string }[]
    const first = terminals.find(t => t.terminalNo === numbers[0])!.id
    const second = terminals.find(t => t.terminalNo === numbers[1])!.id
    await page.getByTestId(`terminal-toggle-${first}`).click()
    await expect(page.getByTestId(`terminal-row-${numbers[0]}`)).toContainText('停用')
    await page.getByTestId(`terminal-toggle-${first}`).click()
    await expect(page.getByTestId(`terminal-toggle-${first}`)).toHaveText('停用')
    await page.getByTestId(`vehicle-edit-${vehicle}`).click()
    await page.getByTestId('vehicle-bind-terminal').selectOption(String(first))
    await page.getByTestId('vehicle-bind-submit').click()
    await expect(row).toContainText(numbers[0]!)
    await page.getByTestId('vehicle-bind-terminal').selectOption(String(second))
    await page.getByTestId('vehicle-bind-submit').click()
    await expect(row).toContainText(numbers[1]!)
    await page.getByTestId(`vehicle-history-${vehicle}`).click()
    await expect(page.getByTestId('archive-history')).toContainText(numbers[0]!)
    await expect(page.getByTestId('archive-history')).toContainText(numbers[1]!)
    await page.getByTestId('vehicle-bind-terminal').selectOption('')
    await page.getByTestId('vehicle-bind-submit').click()
    await expect(row).toContainText('未绑定')
    await page.getByTestId('vehicle-transfer-fleet').selectOption(String(b))
    await page.getByTestId('vehicle-transfer-submit').click()
    await expect(row).toContainText(`模拟车队 ARC-B-${suffix}`)
    await page.getByTestId(`vehicle-history-${vehicle}`).click()
    await expect(page.getByTestId('archive-history')).toContainText(`模拟一队 ${suffix}`)
    await expect(page.getByTestId('archive-history')).toContainText(`模拟车队 ARC-B-${suffix}`)
    await page.getByTestId(`vehicle-toggle-${vehicle}`).click()
    await expect(page.getByTestId(`vehicle-toggle-${vehicle}`)).toHaveText('启用')
    await page.screenshot({ path: 'test-results/archive-history.png', fullPage: true })
  })

  test('监控员与车队长通过页面只读且按车队限定档案', async ({ page, request }) => {
    await signIn(page, username, password)
    const headers = { satoken: await tokenFor(page) }
    async function create(path: string, data: object) {
      const response = await request.post(`/api/v1${path}`, { headers, data })
      expect(response.status()).toBe(201)
      return await response.json() as { id: number }
    }
    const a = await create('/fleets', { fleetCode: `RO-A-${suffix}`, fleetName: '模拟授权队' })
    const b = await create('/fleets', { fleetCode: `RO-B-${suffix}`, fleetName: '模拟其他队' })
    await create('/vehicles', { plateNo: `授权-${suffix}`, fleetId: a.id })
    await create('/vehicles', { plateNo: `其他-${suffix}`, fleetId: b.id })
    for (const role of ['MONITOR', 'FLEET_LEADER']) {
      const name = `arc-${role}-${suffix}`
      await create('/users', { username: name, password: 'Archive-Read-2026', roleCode: role,
        fleetIds: role === 'FLEET_LEADER' ? [a.id] : [] })
      await signIn(page, name, 'Archive-Read-2026')
      await page.getByTestId('nav-archives').click()
      await expect(page.getByTestId(`vehicle-row-授权-${suffix}`)).toBeVisible()
      await expect(page.getByTestId('vehicle-create-form')).toHaveCount(0)
      await expect(page.getByTestId('terminal-create-form')).toHaveCount(0)
      if (role === 'MONITOR') await expect(page.getByTestId(`vehicle-row-其他-${suffix}`)).toBeVisible()
      else await expect(page.getByTestId(`vehicle-row-其他-${suffix}`)).toHaveCount(0)
      await page.getByTestId('logout').click()
    }
  })
})
