import { expect, test } from '@playwright/test'

const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''

// 统计读取复用真实后端；本用例只构造档案，不触发报警，零分母展示由新队一行验证。
test.describe('有权限的监控统计页面', () => {
  test.skip(!username || !password, '需要 BrowserFlowTest 提供真实管理员')

  test('今日指标分列、车队聚合可复算且零分母显示破折号', async ({ page, request }) => {
    test.setTimeout(120_000)
    const suffix = Date.now().toString(36)
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByTestId('login-submit').click()
    await expect(page.getByTestId('current-user')).toContainText(username, { timeout: 20_000 })

    const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(token).toBeTruthy()
    const headers = { satoken: token! }
    const fleetResponse = await request.post('/api/v1/fleets', {
      headers,
      data: { fleetCode: `STA-${suffix}`, fleetName: `统计车队 ${suffix}` },
    })
    expect(fleetResponse.status()).toBe(201)
    const fleet = (await fleetResponse.json()) as { id: number }
    const vehicleResponse = await request.post('/api/v1/vehicles', {
      headers,
      data: { plateNo: `统计-${suffix}`, fleetId: fleet.id },
    })
    expect(vehicleResponse.status()).toBe(201)

    await page.getByTestId('nav-stats').click()
    await expect(page).toHaveURL(/\/monitor\/stats$/)
    await expect(page.getByTestId('stats-overview')).toBeVisible()
    await expect(page.getByTestId('stats-vehicle-total')).toContainText(/\d+/)
    await expect(page.getByTestId('stats-vehicle-offline')).toBeVisible()
    await expect(page.getByTestId('stats-vehicle-notConnected')).toBeVisible()
    await expect(page.getByTestId('stats-vehicle-disabled')).toBeVisible()
    await expect(page.getByTestId('stats-vehicle-activeDisconnected')).toBeVisible()
    await expect(page.getByTestId('stats-vehicle-startupPending')).toBeVisible()
    await expect(page.getByTestId('stats-alarm-pending')).toBeVisible()
    await expect(page.getByTestId('stats-alarm-processing')).toBeVisible()

    const row = page.getByTestId(`stats-fleet-${fleet.id}`)
    await expect(row).toContainText(`统计车队 ${suffix}`)
    const cells = row.locator('td')
    await expect(cells.nth(1)).toHaveText('1')
    await expect(cells.nth(9)).toHaveText('0')
    await expect(cells.nth(12)).toHaveText('—')
    await page.screenshot({ path: 'test-results/statistics-page.png', fullPage: true })
  })
})
