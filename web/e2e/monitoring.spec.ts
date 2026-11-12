import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { expect, test } from '@playwright/test'

const run = promisify(execFile)
const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const tcpPort = process.env.VMS_E2E_TCP_PORT ?? ''
const java = process.env.VMS_E2E_JAVA ?? ''
const simulatorClasspath = process.env.VMS_E2E_SIMULATOR_CP ?? ''

// 位置只由独立 CLI 经真实 TCP 上报；REST 仅准备档案。WMTS 为显式失败 fixture。
test.describe('实时监控 public TCP / REST / WS / browser', () => {
  test.skip(!username || !password || !tcpPort || !java || !simulatorClasspath,
    '需要 BrowserFlowTest 提供真实后端、TCP 与独立 simulator CLI 环境')

  test('TCP 位置实时进入地图与车辆列表，底图失败仍能查看并过滤', async ({ page, request }) => {
    const suffix = Date.now().toString(36)
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByTestId('login-submit').click()
    await expect(page.getByTestId('current-user')).toContainText(username)
    const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(token).toBeTruthy()
    const headers = { satoken: token! }
    async function create(path: string, data: object) {
      const response = await request.post(`/api/v1${path}`, { headers, data })
      expect(response.status()).toBe(201)
      return await response.json() as { id: number }
    }
    const fleet = await create('/fleets', { fleetCode: `MAP-${suffix}`, fleetName: `模拟监控车队 ${suffix}` })
    const vehicle = await create('/vehicles', { plateNo: `模拟监控-${suffix}`, fleetId: fleet.id })
    const terminalNo = `${Date.now()}0000007`
    const terminal = await create('/terminals', { terminalNo })
    expect((await request.put(`/api/v1/vehicles/${vehicle.id}/terminal`, {
      headers, data: { terminalId: terminal.id },
    })).ok()).toBeTruthy()

    await page.reload()
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    const row = page.getByTestId(`monitor-vehicle-${vehicle.id}`)
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
    await expect(row).toBeVisible()
    await page.getByLabel('搜索车辆或终端').fill('没有匹配的模拟车辆')
    await expect(page.getByTestId('monitor-empty')).toContainText('没有匹配车辆')
    await page.getByLabel('搜索车辆或终端').fill(`模拟监控-${suffix}`)
    await expect(row).toBeVisible()
    await page.screenshot({ path: 'test-results/07-live-monitor-wmts-fixture-failure.png', fullPage: true })
  })
})
