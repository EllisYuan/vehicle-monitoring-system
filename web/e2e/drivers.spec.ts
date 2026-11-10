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

test.describe('司机档案与关联历史', () => {
  test.skip(!username || !password, '需要真实测试后端管理员')

  test('管理员通过页面登记司机、关联与解除并保留历史区间', async ({ page, request }) => {
    await signIn(page, username, password)
    const headers = { satoken: await tokenFor(page) }
    async function create(path: string, data: object) {
      const response = await request.post(`/api/v1${path}`, { headers, data })
      expect(response.status()).toBe(201)
      return await response.json() as { id: number }
    }
    const fleet = await create('/fleets', { fleetCode: `DRV-${suffix}`, fleetName: `司机车队 ${suffix}` })
    const vehicle = await create('/vehicles', { plateNo: `司机-${suffix}`, fleetId: fleet.id })

    await page.getByTestId('switch-to-admin').click()
    await page.getByTestId('nav-admin-drivers').click()
    const driverNo = `D-${suffix}`
    await page.getByTestId('driver-create-no').fill(driverNo)
    await page.getByTestId('driver-create-name').fill('模拟司机')
    await page.getByTestId('driver-create-phone').fill('13812345678')
    await page.getByTestId('driver-create-fleet').selectOption(String(fleet.id))
    await page.getByTestId('driver-create-submit').click()
    const row = page.getByTestId(`driver-row-${driverNo}`)
    await expect(row).toBeVisible()
    await expect(row).toContainText('13812345678')
    await expect(row).toContainText('未关联')

    const drivers = await (await request.get('/api/v1/drivers', { headers })).json() as { id: number; driverNo: string }[]
    const driver = drivers.find(d => d.driverNo === driverNo)!.id
    await page.getByTestId(`driver-edit-${driver}`).click()
    await page.getByTestId('driver-edit-name').fill('模拟司机甲')
    await page.getByTestId('driver-edit-save').click()
    await expect(row).toContainText('模拟司机甲')
    const correctedNo = `D2-${suffix}`
    await page.getByTestId('driver-edit-no').fill(correctedNo)
    await page.getByTestId('driver-edit-save').click()
    const correctedRow = page.getByTestId(`driver-row-${correctedNo}`)
    await expect(correctedRow).toBeVisible()
    await page.getByTestId(`driver-edit-${driver}`).click()
    await page.getByTestId('driver-associate-vehicle').selectOption(String(vehicle.id))
    await page.getByTestId('driver-associate-submit').click()
    await expect(correctedRow).toContainText(`司机-${suffix}`)
    await page.getByTestId(`driver-history-${driver}`).click()
    await expect(page.getByTestId('driver-history')).toContainText(`司机-${suffix}`)
    await expect(page.getByTestId('driver-history')).toContainText('至今')
    await page.getByTestId('driver-associate-vehicle').selectOption('')
    await page.getByTestId('driver-associate-submit').click()
    await expect(correctedRow).toContainText('未关联')
    await page.getByTestId(`driver-toggle-${driver}`).click()
    await expect(page.getByTestId(`driver-toggle-${driver}`)).toHaveText('启用')
    await page.screenshot({ path: 'test-results/driver-history.png', fullPage: true })
  })

  test('监控员与车队长只读司机档案，车队长仅见授权车队且电话掩码', async ({ page, request }) => {
    await signIn(page, username, password)
    const headers = { satoken: await tokenFor(page) }
    async function create(path: string, data: object) {
      const response = await request.post(`/api/v1${path}`, { headers, data })
      expect(response.status()).toBe(201)
      return await response.json() as { id: number }
    }
    const a = await create('/fleets', { fleetCode: `DRV-A-${suffix}`, fleetName: '模拟授权队' })
    const b = await create('/fleets', { fleetCode: `DRV-B-${suffix}`, fleetName: '模拟其他队' })
    await create('/drivers', { driverNo: `DA-${suffix}`, name: '授权司机', phone: '13812345678', fleetId: a.id })
    await create('/drivers', { driverNo: `DB-${suffix}`, name: '其他司机', phone: '13900001111', fleetId: b.id })
    for (const role of ['MONITOR', 'FLEET_LEADER']) {
      const name = `drv-${role}-${suffix}`
      await create('/users', { username: name, password: 'Driver-Read-2026', roleCode: role,
        fleetIds: role === 'FLEET_LEADER' ? [a.id] : [] })
      await signIn(page, name, 'Driver-Read-2026')
      await page.getByTestId('nav-drivers').click()
      const granted = page.getByTestId(`driver-row-DA-${suffix}`)
      await expect(granted).toBeVisible()
      await expect(page.getByTestId('driver-create-form')).toHaveCount(0)
      if (role === 'MONITOR') {
        await expect(page.getByTestId(`driver-row-DB-${suffix}`)).toBeVisible()
        await expect(granted).toContainText('13812345678')
      } else {
        await expect(page.getByTestId(`driver-row-DB-${suffix}`)).toHaveCount(0)
        await expect(granted).toContainText('138****5678')
        await expect(page.getByTestId('driver-mask-note')).toBeVisible()
      }
      await page.getByTestId('logout').click()
    }
  })
})
