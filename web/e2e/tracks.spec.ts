import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const run = promisify(execFile)
const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const tcpPort = process.env.VMS_E2E_TCP_PORT ?? ''
const java = process.env.VMS_E2E_JAVA ?? ''
const simulatorClasspath = process.env.VMS_E2E_SIMULATOR_CP ?? ''

interface TrackPoint { gpsTime: string; lng: number; lat: number }
interface TrackSegment { startTime: string; endTime: string; points: TrackPoint[] }
interface TrackGap { startTime: string; endTime: string; reason: string }
interface TrackView {
  rawPointCount: number
  invalidPointCount: number
  displayPointCount: number
  segments: TrackSegment[]
  gaps: TrackGap[]
}

async function signIn(page: Page, name: string, secret: string) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(name)
  await page.getByLabel('密码', { exact: true }).fill(secret)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('current-user')).toContainText(name)
}

async function apiLogin(request: APIRequestContext): Promise<string> {
  const response = await request.post('/api/v1/auth/login', { data: { username, password } })
  expect(response.ok()).toBeTruthy()
  return ((await response.json()) as { token: string }).token
}

async function createFleetVehicle(request: APIRequestContext, token: string, suffix: string) {
  const headers = { satoken: token }
  const fleet = await request.post('/api/v1/fleets', {
    headers, data: { fleetCode: `TRK-${suffix}`, fleetName: `轨迹车队 ${suffix}` },
  })
  expect(fleet.status()).toBe(201)
  const fleetId = ((await fleet.json()) as { id: number }).id
  const vehicle = await request.post('/api/v1/vehicles', {
    headers, data: { plateNo: `轨迹-${suffix}`, fleetId },
  })
  expect(vehicle.status()).toBe(201)
  return { fleetId, vehicleId: ((await vehicle.json()) as { id: number }).id, headers }
}

async function bindTerminal(request: APIRequestContext, headers: Record<string, string>,
  vehicleId: number, terminalNo: string) {
  const terminal = await request.post('/api/v1/terminals', { headers, data: { terminalNo } })
  expect(terminal.status()).toBe(201)
  const terminalId = ((await terminal.json()) as { id: number }).id
  const bound = await request.put(`/api/v1/vehicles/${vehicleId}/terminal`, {
    headers, data: { terminalId },
  })
  expect(bound.ok()).toBeTruthy()
}

async function simulate(terminalNo: string, positions: number,
  options: { longitude: number; latitude: number; intervalMs?: number; fixValid?: boolean }) {
  const args = ['-cp', simulatorClasspath, 'com.vms.simulator.TerminalSimulator',
    '--host', '127.0.0.1', '--port', tcpPort, '--terminal', terminalNo,
    '--positions', String(positions), '--interval-ms', String(options.intervalMs ?? 1500),
    '--linger-ms', '0', '--longitude', String(options.longitude), '--latitude', String(options.latitude),
    '--speed', '42.5', '--direction', '90']
  if (options.fixValid === false) args.push('--fix-valid', 'false')
  await run(java, args, { timeout: 30_000, windowsHide: true })
}

/** 上海时区的本地时间串，datetime-local 需要秒级精度才能覆盖秒级 gps_time。 */
function shanghaiTime(date: Date): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date).replace(' ', 'T')
}

async function setDateTime(page: Page, testId: string, value: string) {
  const input = page.getByTestId(testId)
  await input.evaluate((element, text) => {
    element.setAttribute('step', '1')
    ;(element as HTMLInputElement).value = text
    element.dispatchEvent(new Event('input', { bubbles: true }))
  }, value)
}

async function setSeek(page: Page, value: number) {
  await page.getByTestId('track-seek').evaluate((element, seekValue) => {
    ;(element as HTMLInputElement).value = String(seekValue)
    element.dispatchEvent(new Event('input', { bubbles: true }))
  }, value)
}

async function playbackAttribute(page: Page, name: string): Promise<string | null> {
  return page.getByTestId('track-playback').getAttribute(name)
}

async function clockValue(page: Page): Promise<number> {
  return Number(await playbackAttribute(page, 'data-clock-ms'))
}

async function markerLng(page: Page): Promise<number | null> {
  const value = await page.getByTestId('track-map').getAttribute('data-marker-lng')
  return value ? Number(value) : null
}

test.describe('单车轨迹查询与回放', () => {
  test.skip(!username || !password, '需要 BrowserFlowTest 提供真实测试后端管理员')

  test('浏览器回放不跨断点，支持播放暂停拖动与 1/2/4/8 倍速且无导出入口', async ({ page, request }) => {
    test.skip(!tcpPort || !java || !simulatorClasspath, '需要 BrowserFlowTest 提供真实 TCP 与 simulator CLI 环境')
    test.setTimeout(90_000)
    const suffix = Date.now().toString(36)
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page, username, password)
    const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(token).toBeTruthy()
    const { vehicleId, headers } = await createFleetVehicle(request, token!, suffix)
    const terminalNo = `${Date.now()}0000011`
    await bindTerminal(request, headers, vehicleId, terminalNo)

    await page.goto('/monitor/tracks')
    await expect(page.getByTestId('track-vehicle').locator(`option[value="${vehicleId}"]`)).toHaveCount(1)

    // 三段真实 TCP 数据：有效段 A → 定位无效段 B → 有效段 C（经另一经度起点，便于区分）。
    const startedAt = Date.now()
    await simulate(terminalNo, 3, { longitude: 121.4737, latitude: 31.2304 })
    await page.waitForTimeout(1100)
    await simulate(terminalNo, 2, { longitude: 121.4737, latitude: 31.2304, fixValid: false })
    await page.waitForTimeout(1100)
    await simulate(terminalNo, 3, { longitude: 121.474, latitude: 31.2304 })
    const finishedAt = Date.now()

    await page.getByTestId('track-vehicle').selectOption(String(vehicleId))
    await setDateTime(page, 'track-from', shanghaiTime(new Date(startedAt - 60_000)))
    await setDateTime(page, 'track-to', shanghaiTime(new Date(finishedAt + 60_000)))
    await page.getByTestId('track-query').click()

    await expect(page.getByTestId('track-summary')).toBeVisible()
    await expect(page.getByTestId('track-raw-count')).toHaveText('8')
    await expect(page.getByTestId('track-display-count')).toHaveText('4')
    await expect(page.getByTestId('track-segment-count')).toHaveText('2')
    await expect(page.getByTestId('track-gap-count')).toHaveText('1')
    await expect(page.getByTestId('track-gap-0')).toContainText('定位无效')
    await expect(page.getByTestId('track-map')).toHaveAttribute('data-segment-count', '2')
    await expect(page.getByTestId('track-map')).toHaveAttribute('data-gap-count', '1')
    await expect(page.getByTestId('track-map-error')).toContainText('底图加载失败')

    const from = await page.getByTestId('track-from').inputValue()
    const to = await page.getByTestId('track-to').inputValue()
    const truthResponse = await request.get('/api/v1/tracks', {
      headers: { satoken: token! }, params: { vehicleId, from, to },
    })
    expect(truthResponse.ok()).toBeTruthy()
    const view = (await truthResponse.json()) as TrackView
    expect(view.rawPointCount).toBe(8)
    expect(view.invalidPointCount).toBe(2)
    expect(view.displayPointCount).toBe(4)
    expect(view.segments.length).toBe(2)
    expect(view.gaps.length).toBe(1)
    expect(view.gaps[0].reason).toBe('INVALID_FIX')
    expect(view.segments[0].points.length).toBe(2)
    expect(view.segments[1].points.length).toBe(2)
    const firstLng = view.segments[0].points[0].lng
    const lastLng = view.segments[1].points[1].lng
    const startMs = Date.parse(view.segments[0].startTime)
    const endMs = Date.parse(view.segments[1].endTime)
    expect(endMs - startMs).toBeGreaterThan(2_000)

    await expect(page.getByTestId('track-playback')).toHaveAttribute('data-state', 'ready')
    await expect.poll(() => clockValue(page)).toBe(startMs)
    await expect.poll(() => markerLng(page)).toBeCloseTo(firstLng, 6)

    // 真实拖动时间轴：鼠标按住滑块从 40% 拖到 20%，应落在第一个有效分段内。
    const seek = page.getByTestId('track-seek')
    await seek.scrollIntoViewIfNeeded()
    const box = await seek.boundingBox()
    expect(box).toBeTruthy()
    await page.mouse.move(box!.x + box!.width * 0.4, box!.y + box!.height / 2)
    await page.mouse.down()
    await page.mouse.move(box!.x + box!.width * 0.2, box!.y + box!.height / 2, { steps: 5 })
    await page.mouse.up()
    await expect.poll(() => clockValue(page)).toBeGreaterThan(startMs)
    await expect.poll(() => markerLng(page)).not.toBeNull()
    const draggedLng = await markerLng(page)
    expect(draggedLng!).toBeGreaterThanOrEqual(firstLng)

    // 时间轴端点与车辆位置一致。
    await setSeek(page, 0)
    await expect.poll(() => clockValue(page)).toBe(startMs)
    await expect.poll(() => markerLng(page)).toBeCloseTo(firstLng, 6)
    await setSeek(page, endMs - startMs)
    await expect.poll(() => clockValue(page)).toBe(endMs)
    await expect.poll(() => markerLng(page)).toBeCloseTo(lastLng, 6)
    await setSeek(page, 0)

    // 播放穿越断点：断点内没有标记位置，进入第二个分段后恢复。
    await page.getByTestId('track-play').click()
    await expect(page.getByTestId('track-playback')).toHaveAttribute('data-state', 'playing')
    await expect.poll(() => clockValue(page)).toBeGreaterThan(startMs)
    await expect(page.getByTestId('track-in-gap')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('track-playback')).toHaveAttribute('data-in-gap', 'true')
    await expect.poll(() => markerLng(page)).toBeNull()
    await expect(page.getByTestId('track-in-gap')).toBeHidden({ timeout: 20_000 })
    await page.getByTestId('track-pause').click()
    await expect(page.getByTestId('track-playback')).toHaveAttribute('data-state', 'paused')
    const pausedClock = await clockValue(page)
    await page.waitForTimeout(600)
    expect(await clockValue(page)).toBe(pausedClock)

    // 1/2/4/8 倍速均可切换；同样墙钟时间内 8× 走的轨迹时间明显多于 1×。
    for (const value of [1, 2, 4, 8]) {
      await page.getByTestId(`track-speed-${value}`).click()
      await expect(page.getByTestId('track-playback')).toHaveAttribute('data-speed', String(value))
      await expect(page.getByTestId(`track-speed-${value}`)).toHaveAttribute('aria-pressed', 'true')
    }
    await setSeek(page, 0)
    await page.getByTestId('track-speed-1').click()
    await page.getByTestId('track-play').click()
    await page.waitForTimeout(1_200)
    await page.getByTestId('track-pause').click()
    const slowAdvance = (await clockValue(page)) - startMs
    await setSeek(page, 0)
    await page.getByTestId('track-speed-8').click()
    await page.getByTestId('track-play').click()
    await page.waitForTimeout(1_200)
    await page.getByTestId('track-pause').click()
    const fastAdvance = (await clockValue(page)) - startMs
    expect(slowAdvance).toBeGreaterThan(0)
    expect(fastAdvance).toBeGreaterThan(slowAdvance * 3)

    // 不提供原始轨迹导出入口。
    expect(await page.getByText(/导出|下载/).count()).toBe(0)
    expect(await page.locator('a[download]').count()).toBe(0)
    await page.screenshot({ path: 'test-results/10-track-playback.png', fullPage: true })
  })

  test('车队长只能查询授权车队轨迹，跨队请求被服务端拒绝', async ({ page, request }) => {
    const suffix = `scope${Date.now().toString(36)}`
    const adminToken = await apiLogin(request)
    const granted = await createFleetVehicle(request, adminToken, `A${suffix}`)
    const foreign = await createFleetVehicle(request, adminToken, `B${suffix}`)
    const leaderName = `track-leader-${suffix}`
    const leaderPassword = 'Leader-Pass-2026'
    const created = await request.post('/api/v1/users', {
      headers: { satoken: adminToken },
      data: { username: leaderName, password: leaderPassword, roleCode: 'FLEET_LEADER',
        fleetIds: [granted.fleetId] },
    })
    expect(created.status()).toBe(201)

    await signIn(page, leaderName, leaderPassword)
    await page.getByTestId('nav-tracks').click()
    await expect(page.getByTestId('track-vehicle').locator(`option[value="${granted.vehicleId}"]`))
      .toHaveCount(1)
    await expect(page.getByTestId('track-vehicle').locator(`option[value="${foreign.vehicleId}"]`))
      .toHaveCount(0)
    await page.getByTestId('track-vehicle').selectOption(String(granted.vehicleId))
    await page.getByTestId('track-query').click()
    await expect(page.getByTestId('track-summary')).toBeVisible()
    await expect(page.getByTestId('track-segment-count')).toHaveText('0')
    await expect(page.getByTestId('track-empty')).toContainText('没有有效轨迹点')

    const leaderToken = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(leaderToken).toBeTruthy()
    const denied = await request.get('/api/v1/tracks', {
      headers: { satoken: leaderToken! },
      params: {
        vehicleId: foreign.vehicleId,
        from: shanghaiTime(new Date(Date.now() - 3_600_000)),
        to: shanghaiTime(new Date()),
      },
    })
    expect(denied.status()).toBe(403)

    expect(await page.getByText(/导出|下载/).count()).toBe(0)
    await page.screenshot({ path: 'test-results/10-leader-track-scope.png', fullPage: true })
  })
})
