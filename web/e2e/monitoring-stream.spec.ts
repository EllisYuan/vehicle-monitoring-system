import { expect, test, type Page } from '@playwright/test'

// 17：用假 WebSocket 驱动真实浏览器，验证断线/重连状态、重连后重新取快照补齐窗口、
// 旧版本帧不覆盖新版本，以及 1008 撤权关闭后停止自动重连、清空旧数据且不被迟到快照回填。
interface FakeSocket {
  url: string
  readyState: number
  emit(frame: unknown): void
  serverClose(code: number, reason?: string): void
}

const now = () => new Date().toISOString()

function vehicle(overrides: Record<string, unknown> = {}) {
  const timestamp = now()
  return {
    vehicleId: 7,
    plateNo: '测试车A',
    fleetId: 1,
    fleetName: '测试车队',
    status: 'ENABLED',
    terminalId: 3,
    terminalNo: '01380013900000000007',
    lastSeenAt: timestamp,
    communicationStatus: 'ONLINE',
    position: {
      version: 1,
      terminalId: 3,
      gpsTime: timestamp,
      receivedAt: timestamp,
      lng: 116.4,
      lat: 39.9,
      speedKph: 30,
      directionDeg: 90,
      accOn: true,
      fixValid: true,
    },
    ...overrides,
  }
}

function staleVehicle() {
  const old = new Date(Date.now() - 120_000).toISOString()
  return vehicle({
    vehicleId: 8,
    plateNo: '测试车B',
    terminalNo: '01380013900000000008',
    position: {
      ...vehicle().position,
      terminalId: 4,
      gpsTime: old,
      receivedAt: old,
      speedKph: 0,
    },
  })
}

interface MockOptions {
  /** 每次快照请求（从第 1 次计）的响应延迟；用于制造撤权时仍在途的快照。 */
  snapshotDelays?: number[]
  /** 每个假 WebSocket 实例建立的延迟；第 2 个值足够长，保证“正在重连”状态可被稳定观察到。 */
  openDelays?: number[]
}

async function installMocks(page: Page, options: MockOptions = {}) {
  await page.route(/^https?:\/\/(?!localhost)/, route => route.abort())
  await page.route('**/api/v1/auth/me', route => route.fulfill({
    json: { id: 1, username: 'mock-monitor', roleCode: 'ADMIN' },
  }))
  let snapshotCalls = 0
  // 受控变化只出现在快照中、不经过 WS，用于验证重连后的通用校正机制。
  let corrected = false
  await page.route('**/api/v1/monitor/snapshot', async route => {
    snapshotCalls += 1
    const delay = options.snapshotDelays?.[snapshotCalls - 1] ?? 0
    if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay))
    const changed = corrected
      ? vehicle({ position: { ...vehicle().position, version: 3, speedKph: 66 } })
      : vehicle()
    try {
      await route.fulfill({ json: { serverTime: now(), vehicles: [changed, staleVehicle()] } })
    } catch { /* 请求已被客户端取消 */ }
  })
  let ticketCalls = 0
  await page.route('**/api/v1/auth/ws-ticket', route => {
    ticketCalls += 1
    return route.fulfill({ json: { ticket: `mock-ticket-${ticketCalls}`, expiresAt: now() } })
  })
  await page.addInitScript(openDelays => {
    try { sessionStorage.setItem('vms.session.token', 'mock-session-token') } catch { /* ignore */ }
    const scope = window as unknown as { __wsInstances?: FakeSocket[] }
    class FakeWebSocket {
      static CONNECTING = 0
      static OPEN = 1
      static CLOSING = 2
      static CLOSED = 3
      url: string
      readyState = 0
      onopen: ((event: Event) => void) | null = null
      onmessage: ((event: { data: string }) => void) | null = null
      onerror: ((event: Event) => void) | null = null
      onclose: ((event: { code: number; reason: string; wasClean: boolean }) => void) | null = null
      constructor(url: string | URL) {
        this.url = String(url)
        const list = scope.__wsInstances ?? (scope.__wsInstances = [])
        list.push(this)
        // Vite HMR 也会创建 WebSocket；延迟表只按监控订阅 socket 的顺序取值。
        const monitorSockets = list.filter(socket => socket.url.includes('/ws/monitor'))
        const openDelay = this.url.includes('/ws/monitor')
          ? openDelays[monitorSockets.length - 1] ?? 50
          : 50
        setTimeout(() => {
          if (this.readyState !== 0) return
          this.readyState = 1
          this.onopen?.(new Event('open'))
        }, openDelay)
      }
      send() { /* 订阅不向服务端发送消息 */ }
      close(code = 1000, reason = '') {
        if (this.readyState === 3) return
        this.readyState = 3
        this.onclose?.({ code, reason, wasClean: code === 1000 })
      }
      emit(frame: unknown) { this.onmessage?.({ data: JSON.stringify(frame) }) }
      serverClose(code: number, reason = '') {
        if (this.readyState === 3) return
        this.readyState = 3
        this.onclose?.({ code, reason, wasClean: false })
      }
    }
    window.WebSocket = FakeWebSocket as unknown as typeof WebSocket
  }, options.openDelays ?? [])
  return {
    snapshots: () => snapshotCalls,
    tickets: () => ticketCalls,
    applyControlledChange: () => { corrected = true },
  }
}

function socketCount(page: Page) {
  return page.evaluate(() => {
    const scope = window as unknown as { __wsInstances?: FakeSocket[] }
    return scope.__wsInstances?.filter(socket => socket.url.includes('/ws/monitor')).length ?? 0
  })
}

async function emit(page: Page, frame: unknown) {
  await page.evaluate(value => {
    const scope = window as unknown as { __wsInstances?: FakeSocket[] }
    scope.__wsInstances?.filter(socket => socket.url.includes('/ws/monitor')).at(-1)?.emit(value)
  }, frame)
}

async function closeSocket(page: Page, code: number, reason: string) {
  await page.evaluate(({ closeCode, closeReason }) => {
    const scope = window as unknown as { __wsInstances?: FakeSocket[] }
    scope.__wsInstances?.filter(socket => socket.url.includes('/ws/monitor'))
      .at(-1)?.serverClose(closeCode, closeReason)
  }, { closeCode: code, closeReason: reason })
}

test.describe('实时订阅前端状态与版本顺序', () => {
  test('旧版本帧不覆盖新版本，异常断开后显示断线与重连中并重建订阅补齐窗口', async ({ page }) => {
    const calls = await installMocks(page, { openDelays: [50, 3000] })
    await page.goto('/')
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    const row = page.getByTestId('monitor-vehicle-7')
    await expect(row).toContainText('30.0 km/h')
    await expect(page.getByTestId('monitor-vehicle-8')).toContainText('定位陈旧')
    await expect.poll(() => calls.snapshots()).toBeGreaterThanOrEqual(1)

    await emit(page, {
      type: 'vehicle_position',
      event_id: 'position:7:2',
      aggregate_id: 7,
      version: 2,
      server_time: now(),
      payload: vehicle({ position: { ...vehicle().position, version: 2, speedKph: 55 } }),
    })
    await expect(row).toContainText('55.0 km/h')
    await emit(page, {
      type: 'vehicle_position',
      event_id: 'position:7:1',
      aggregate_id: 7,
      version: 1,
      server_time: now(),
      payload: vehicle(),
    })
    await page.waitForTimeout(300)
    await expect(row).toContainText('55.0 km/h')

    const before = await socketCount(page)
    const snapshotsBefore = calls.snapshots()
    await closeSocket(page, 1011, 'slow client')
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已断开')
    // 断线期间发生的受控变化没有经过 WS；重连后必须由重新查询快照补齐。
    calls.applyControlledChange()
    await expect(page.getByTestId('monitor-stream-status')).toContainText('正在重连')
    await expect.poll(() => socketCount(page), { timeout: 10_000 }).toBe(before + 1)
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    await expect(row).toContainText('66.0 km/h')
    await expect.poll(() => calls.snapshots()).toBeGreaterThan(snapshotsBefore)
    await expect.poll(() => calls.tickets()).toBeGreaterThanOrEqual(2)
  })

  test('1008 撤权关闭后停止自动重连并清空旧数据', async ({ page }) => {
    const calls = await installMocks(page)
    await page.goto('/')
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    await expect(page.getByTestId('monitor-vehicle-7')).toBeVisible()
    const before = await socketCount(page)

    await closeSocket(page, 1008, 'SUBSCRIPTION_REVOKED')
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已停止')
    await expect(page.getByRole('alert')).toContainText('实时订阅已失效')
    await expect(page.getByTestId('monitor-empty')).toContainText('暂无可见车辆')
    await page.waitForTimeout(2500)
    expect(await socketCount(page)).toBe(before)
    expect(calls.tickets()).toBe(1)
  })

  test('撤权时仍在途的快照返回后不能恢复旧数据', async ({ page }) => {
    const calls = await installMocks(page, { snapshotDelays: [0, 1500] })
    await page.goto('/')
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已建立')
    // 握手成功后的快照仍在途时收到撤权关闭；该快照随后返回，页面必须保持已停止状态。
    await closeSocket(page, 1008, 'SUBSCRIPTION_REVOKED')
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已停止')
    await expect(page.getByRole('alert')).toContainText('实时订阅已失效')
    await page.waitForTimeout(2200)
    await expect(page.getByTestId('monitor-stream-status')).toContainText('实时连接已停止')
    await expect(page.getByRole('alert')).toContainText('实时订阅已失效')
    await expect(page.getByTestId('monitor-empty')).toContainText('暂无可见车辆')
    expect(await socketCount(page)).toBe(1)
    expect(calls.tickets()).toBe(1)
  })
})
