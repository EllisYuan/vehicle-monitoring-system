import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

// 23：真实后端下的浏览器中断验收。
// 中断方式：关闭页面（浏览器侧 WebSocket 随页面销毁）与浏览器层关闭 /ws/monitor 连接；
// 不使用假 WebSocket 冒充真实连接。页面状态与快照补齐由真实后端 + Vite 代理驱动。

const run = promisify(execFile)
const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const tcpPort = process.env.VMS_E2E_TCP_PORT ?? ''
const java = process.env.VMS_E2E_JAVA ?? ''
const simulatorClasspath = process.env.VMS_E2E_SIMULATOR_CP ?? ''

interface AlarmRow {
  id: number
  vehicleId: number
  plateNo: string
  conditionState: string
  handleStatus: string
}

async function signIn(page: Page) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('current-user')).toContainText(username)
}

async function token(page: Page) {
  return page.evaluate(() => sessionStorage.getItem('vms.session.token'))
}

async function createVehicle(request: APIRequestContext, page: Page, suffix: string) {
  const session = await token(page)
  expect(session).toBeTruthy()
  const headers = { satoken: session! }
  const fleet = await request.post('/api/v1/fleets', {
    headers, data: { fleetCode: `OUT-${suffix}`, fleetName: `中断验收车队 ${suffix}` },
  })
  expect(fleet.status()).toBe(201)
  const fleetId = ((await fleet.json()) as { id: number }).id
  const vehicle = await request.post('/api/v1/vehicles', {
    headers, data: { plateNo: `中断验收-${suffix}`, fleetId },
  })
  expect(vehicle.status()).toBe(201)
  return { vehicleId: ((await vehicle.json()) as { id: number }).id, fleetId, headers }
}

async function bindNewTerminal(request: APIRequestContext, vehicleId: number,
                                headers: Record<string, string>, terminalNo: string) {
  const terminal = await request.post('/api/v1/terminals', { headers, data: { terminalNo } })
  expect(terminal.status()).toBe(201)
  const terminalId = ((await terminal.json()) as { id: number }).id
  const bound = await request.put(`/api/v1/vehicles/${vehicleId}/terminal`, {
    headers, data: { terminalId },
  })
  expect(bound.ok()).toBeTruthy()
}

async function simulate(terminalNo: string, extra: string[]) {
  await run(java, ['-cp', simulatorClasspath, 'com.vms.simulator.TerminalSimulator',
    '--host', '127.0.0.1', '--port', tcpPort, '--terminal', terminalNo, ...extra],
  { timeout: 30_000, windowsHide: true })
}

async function vehicleAlarms(request: APIRequestContext, headers: Record<string, string>,
                             vehicleId: number) {
  const response = await request.get('/api/v1/alarms', { headers })
  expect(response.ok()).toBeTruthy()
  return ((await response.json()) as AlarmRow[]).filter(row => row.vehicleId === vehicleId)
}

test.describe('中断与恢复：浏览器 WebSocket', () => {
  test.skip(!username || !password || !tcpPort || !java || !simulatorClasspath,
    '需要真实后端、TCP 与 simulator CLI 环境（BrowserFlowTest 或 23 号验收脚本提供）')

  test('关闭页面中断实时连接，重开后快照与报警列表补齐且业务事件不重复', async ({ page, request }) => {
    test.setTimeout(150_000)
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page)
    const suffix = Date.now().toString(36)
    const { vehicleId, headers } = await createVehicle(request, page, suffix)
    const terminalNo = `${Date.now()}0000023`
    await bindNewTerminal(request, vehicleId, headers, terminalNo)
    // 定位 gps_time 按秒截断，必须严格晚于绑定 effective_from，否则服务端按 gps_time 解析归属为 UNASSIGNABLE 而拒收。
    await page.waitForTimeout(1100)
    await page.reload()
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    const row = page.getByTestId(`monitor-vehicle-${vehicleId}`)
    await expect(row).toContainText('暂无有效定位')

    await simulate(terminalNo, ['--positions', '2', '--interval-ms', '1100', '--linger-ms', '1000',
      '--longitude', '121.4737', '--latitude', '31.2304', '--speed', '42.5', '--direction', '90'])
    await expect(row).toContainText('42.5 km/h')

    // 3 个连续超速点成立一条 ACTIVE 超速报警（阈值 80 km/h）。
    await simulate(terminalNo, ['--positions', '3', '--interval-ms', '1100', '--linger-ms', '1000',
      '--longitude', '121.4737', '--latitude', '31.2304', '--speed', '100', '--direction', '90'])
    await expect(row).toContainText('100.0 km/h')
    await expect.poll(async () => (await vehicleAlarms(request, headers, vehicleId)).length)
      .toBe(1)
    const alarms = await vehicleAlarms(request, headers, vehicleId)
    expect(alarms[0]!.conditionState).toBe('ACTIVE')
    expect(alarms[0]!.handleStatus).toBe('PENDING')

    // 中断：关闭页面即销毁浏览器侧 WebSocket 连接。
    await page.close()

    // 重开页面并登录（Sa-Token 单会话：新登录替换旧会话，旧连接已随页面销毁）。
    const reopened = await page.context().newPage()
    await reopened.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(reopened)
    await expect(reopened.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    // 快照补齐断线期间已提交的最新位置，不做回退。
    await expect(reopened.getByTestId(`monitor-vehicle-${vehicleId}`)).toContainText('100.0 km/h')
    // 单会话登录会替换旧 token：重开后必须使用新 token 访问 REST。
    const reopenedSession = await token(reopened)
    expect(reopenedSession).toBeTruthy()
    const reopenedHeaders = { satoken: reopenedSession! }

    // 报警列表由 REST 在页面加载时补齐；当前前端没有 alarm_event 订阅，记录实际行为。
    await reopened.goto('/monitor/alarms')
    await expect(reopened.getByTestId(`alarm-row-${alarms[0]!.id}`)).toBeVisible()
    await expect(reopened.getByTestId(`alarm-condition-${alarms[0]!.id}`)).toContainText('活跃')
    await expect(reopened.getByTestId(`alarm-handle-state-${alarms[0]!.id}`)).toContainText('待处理')

    // 重连与重复投递不重复创建业务事件：同一车辆仍只有一条报警。
    const afterReconnect = await vehicleAlarms(request, reopenedHeaders, vehicleId)
    expect(afterReconnect).toHaveLength(1)
    expect(afterReconnect[0]!.id).toBe(alarms[0]!.id)
    await reopened.screenshot({ path: 'test-results/23-browser-reconnect-alarm-list.png', fullPage: true })
  })

  test('浏览器层关闭 WebSocket 后显示断线与数据陈旧，恢复后重新取快照', async ({ page, request }) => {
    test.setTimeout(180_000)
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page)
    const suffix = `st${Date.now().toString(36)}`
    const { vehicleId, headers } = await createVehicle(request, page, suffix)
    const terminalNo = `${Date.now()}0000024`
    await bindNewTerminal(request, vehicleId, headers, terminalNo)
    // 定位 gps_time 按秒截断，必须严格晚于绑定 effective_from，否则服务端按 gps_time 解析归属为 UNASSIGNABLE 而拒收。
    await page.waitForTimeout(1100)
    await simulate(terminalNo, ['--positions', '1', '--linger-ms', '1000',
      '--longitude', '121.48', '--latitude', '31.235', '--speed', '42.5', '--direction', '90'])

    // 浏览器层拦截并关闭 /ws/monitor：真实页面与真实后端之间没有可用订阅，
    // 等价于浏览器侧连接被关闭；页面其余 REST 仍可用。
    // page.unrouteAll 只移除 HTTP 路由，不会移除 routeWebSocket 注册，故用开关控制放行或关闭。
    let wsBlocked = true
    await page.routeWebSocket(url => url.pathname === '/ws/monitor', ws => {
      if (wsBlocked) {
        ws.close({ code: 1011, reason: 'outage-acceptance' })
      } else {
        ws.connectToServer()
      }
    })
    await page.goto('/')
    const row = page.getByTestId(`monitor-vehicle-${vehicleId}`)
    await expect(row).toContainText('42.5 km/h')
    // 断线状态可见：关闭连接后进入断开 / 重连中，最终因重试次数用尽而停止。
    await expect(page.getByTestId('monitor-stream-status')).toContainText(
      /实时连接已断开|正在重连/, { timeout: 15_000 })
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已停止',
      { timeout: 45_000 })

    // 停止后不再自动取快照；超过 60 秒后页面显示数据陈旧，且逐车定位标注陈旧。
    await expect(page.getByTestId('monitor-data-stale')).toContainText('数据陈旧', { timeout: 90_000 })
    await expect(row).toContainText('定位陈旧')

    // 恢复：放行 WS 路由，手动重试后重新握手并取快照，陈旧标记清除。
    wsBlocked = false
    await page.getByRole('button', { name: '重新连接' }).click()
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立',
      { timeout: 20_000 })
    await expect(page.getByTestId('monitor-data-stale')).toHaveCount(0)
    await expect(row).toContainText('42.5 km/h')
    await page.screenshot({ path: 'test-results/23-browser-stale-recovered.png', fullPage: true })

    // 恢复后 REST 仍按当前授权返回数据，业务事件未重复创建。
    const alarms = await vehicleAlarms(request, headers, vehicleId)
    expect(alarms).toHaveLength(0)
  })
})


