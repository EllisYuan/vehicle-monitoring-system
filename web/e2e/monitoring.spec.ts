import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const run = promisify(execFile)
const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const tcpPort = process.env.VMS_E2E_TCP_PORT ?? ''
const java = process.env.VMS_E2E_JAVA ?? ''
const simulatorClasspath = process.env.VMS_E2E_SIMULATOR_CP ?? ''
const fixtureKey = process.env.VMS_E2E_WMTS_KEY ?? ''

async function signIn(page: Page) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('current-user')).toContainText(username)
}

async function createVehicle(request: APIRequestContext, page: Page, suffix: string) {
  const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
  expect(token).toBeTruthy()
  const headers = { satoken: token! }
  const fleet = await request.post('/api/v1/fleets', {
    headers, data: { fleetCode: `MAP-${suffix}`, fleetName: `模拟监控车队 ${suffix}` },
  })
  expect(fleet.status()).toBe(201)
  const fleetId = ((await fleet.json()) as { id: number }).id
  const vehicle = await request.post('/api/v1/vehicles', {
    headers, data: { plateNo: `模拟监控-${suffix}`, fleetId },
  })
  expect(vehicle.status()).toBe(201)
  return { vehicleId: ((await vehicle.json()) as { id: number }).id, headers }
}

// 位置只由独立 CLI 经真实 TCP 上报；WMTS 为显式失败 fixture。
// 真实天地图 key、真实底图、真实网络与对齐属 ticket 26 的未执行项，本文件不冒充。
test.describe('实时监控 public TCP / REST / WS / browser', () => {
  test.skip(!username || !password || !tcpPort || !java || !simulatorClasspath,
    '需要 BrowserFlowTest 提供真实后端、TCP 与独立 simulator CLI 环境')

  test('TCP 位置实时进入地图与车辆列表，底图失败仍能查看并过滤', async ({ page, request }) => {
    const suffix = Date.now().toString(36)
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page)
    const { vehicleId, headers } = await createVehicle(request, page, suffix)
    const terminalNo = `${Date.now()}0000007`
    const terminal = await request.post('/api/v1/terminals', { headers, data: { terminalNo } })
    expect(terminal.status()).toBe(201)
    const terminalId = ((await terminal.json()) as { id: number }).id
    expect((await request.put(`/api/v1/vehicles/${vehicleId}/terminal`, {
      headers, data: { terminalId },
    })).ok()).toBeTruthy()

    await page.reload()
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    const row = page.getByTestId(`monitor-vehicle-${vehicleId}`)
    await expect(row).toContainText('暂无有效定位')
    await run(java, ['-cp', simulatorClasspath, 'com.vms.simulator.TerminalSimulator',
      '--host', '127.0.0.1', '--port', tcpPort, '--terminal', terminalNo,
      '--positions', '2', '--interval-ms', '1100', '--linger-ms', '1000',
      '--longitude', '121.4737', '--latitude', '31.2304', '--speed', '42.5', '--direction', '90'],
    { timeout: 20_000, windowsHide: true })

    await expect(row).toContainText('42.5 km/h')
    await row.click()
    const detail = page.getByTestId('monitor-selected-vehicle')
    await expect(detail).toContainText('90°')
    await expect(detail).toContainText('ACC 开')
    await expect(detail).toContainText('定位有效')
    await expect(detail).toContainText('最后通信')
    await expect(detail).toContainText('最后有效定位')
    await expect(page.getByTestId('monitor-map')).toBeVisible()
    await expect(page.getByTestId('monitor-map-error')).toContainText('底图加载失败')
    await expect(page.getByTestId('monitor-map-attribution')).toContainText('WMTS failure fixture')
    await expect(page.getByTestId('monitor-map-attribution')).toContainText('OpenLayers')
    await expect(row).toBeVisible()
    await page.getByLabel('搜索车辆或终端').fill('没有匹配的模拟车辆')
    await expect(page.getByTestId('monitor-empty')).toContainText('没有匹配车辆')
    await page.getByLabel('搜索车辆或终端').fill(`模拟监控-${suffix}`)
    await expect(row).toBeVisible()
    await page.screenshot({ path: 'test-results/07-live-monitor-wmts-fixture-failure.png', fullPage: true })
  })

  test('底图 key 只进入瓦片请求，不进入页面正文、错误提示或控制台', async ({ page }) => {
    test.skip(!fixtureKey, '需要 BrowserFlowTest 提供 fixture key（非真实 key）')
    const tileRequests: string[] = []
    const consoleText: string[] = []
    page.on('request', request => {
      if (request.url().includes('/__wmts-fixture/')) tileRequests.push(request.url())
    })
    page.on('console', message => consoleText.push(message.text()))
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page)
    await expect(page.getByTestId('monitor-map-error')).toContainText('底图加载失败')
    await expect(page.getByTestId('monitor-map-attribution')).toContainText('WMTS failure fixture')
    expect(tileRequests.some(url => url.includes(`tk=${encodeURIComponent(fixtureKey)}`))).toBe(true)
    expect(await page.locator('body').innerText()).not.toContain(fixtureKey)
    expect(consoleText.join('\n')).not.toContain(fixtureKey)
  })

  test('底图返回 403 或非图片内容时显示错误且车辆列表可用', async ({ page, request }) => {
    await page.route('**/__wmts-fixture/**', route => route.fulfill({
      status: 403, contentType: 'application/xml',
      body: '<Error><Message>invalid access key</Message></Error>',
    }))
    await signIn(page)
    const { vehicleId } = await createVehicle(request, page, Date.now().toString(36))
    await page.reload()
    const row = page.getByTestId(`monitor-vehicle-${vehicleId}`)
    await expect(row).toBeVisible()
    await expect(row).toContainText('暂无有效定位')
    await expect(page.getByTestId('monitor-map-error')).toContainText('底图加载失败')
    await expect(row).toBeVisible()

    await page.unroute('**/__wmts-fixture/**')
    await page.route('**/__wmts-fixture/**', route => route.fulfill({
      status: 200, contentType: 'application/xml', body: '<Error>invalid access key</Error>',
    }))
    await page.getByRole('button', { name: '重试底图' }).click()
    await expect(page.getByTestId('monitor-map-error')).toContainText('底图加载失败')
    await expect(row).toBeVisible()
    await page.screenshot({ path: 'test-results/26-invalid-key-fixture.png', fullPage: true })
  })
})
