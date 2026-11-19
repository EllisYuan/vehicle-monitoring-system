#!/usr/bin/env node
/**
 * 24：100 终端性能验收的浏览器侧测量脚本。
 *
 * 不属于默认 Playwright 套件（testDir 为 ./e2e），由 server 侧
 * HundredTerminalPerfHarnessTest 在 vms.perf=true 时以子进程方式调用。
 *
 * 测量方法：
 * - 真实登录（真实 /api/v1/auth/*）后进入实时监控首页，使用页面自身的 WebSocket 订阅；
 * - 在应用脚本执行前包装 window.WebSocket，只记录 /ws/monitor 帧；
 * - 每帧记录 event_id、payload.receivedAt、server_time、客户端到达时刻与紧随其后的
 *   requestAnimationFrame 回调时刻（该回调结束后浏览器绘制本帧）；
 * - 不注入假帧、不轮询接口取位置，位置全部来自真实 WS 推送。
 *
 * 输出：把原始帧与 socket 生命周期写入 VMS_PERF_FRAMES 指定的 JSON 文件；
 * stdout 打印 PERF_ENV / PERF_READY / PERF_DONE 供 Java harness 读取。
 */
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const webPort = process.env.VMS_PERF_WEB_PORT
const username = process.env.VMS_PERF_USERNAME
const password = process.env.VMS_PERF_PASSWORD
const durationMs = Number(process.env.VMS_PERF_DURATION_MS ?? '60000')
const framesPath = process.env.VMS_PERF_FRAMES
const screenshotsDir = process.env.VMS_PERF_SCREENSHOTS_DIR

if (!webPort || !username || !password || !framesPath) {
  console.error('缺少 VMS_PERF_WEB_PORT / VMS_PERF_USERNAME / VMS_PERF_PASSWORD / VMS_PERF_FRAMES')
  process.exit(2)
}

const baseUrl = `http://127.0.0.1:${webPort}`

function installWebSocketProbe() {
  const native = window.WebSocket
  const state = (window.__vmsPerf = {
    sockets: 0,
    events: [],
    pageErrors: [],
    frames: [],
  })
  class PerfWebSocket extends native {
    constructor(url, protocols) {
      super(url, protocols)
      const target = String(url)
      if (!target.includes('/ws/monitor')) return
      state.sockets += 1
      this.addEventListener('open', () => {
        state.events.push({ kind: 'open', at: Date.now(), url: target })
      })
      this.addEventListener('error', () => {
        state.events.push({ kind: 'error', at: Date.now(), url: target })
      })
      this.addEventListener('close', event => {
        state.events.push({
          kind: 'close', at: Date.now(), url: target,
          code: event.code, reason: event.reason, wasClean: event.wasClean,
        })
      })
      this.addEventListener('message', event => {
        const clientArrival = Date.now()
        const arrivalPerf = performance.now()
        let frame
        try {
          frame = JSON.parse(String(event.data))
        } catch {
          return
        }
        if (frame?.type !== 'vehicle_position' || !frame.payload?.position) return
        const position = frame.payload.position
        const record = {
          eventId: frame.event_id ?? null,
          vehicleId: frame.aggregate_id ?? null,
          version: frame.version ?? null,
          serverTime: Date.parse(frame.server_time),
          receivedAt: Date.parse(position.receivedAt),
          gpsTime: Date.parse(position.gpsTime),
          clientArrival,
          arrivalPerf,
          render: 0,
          renderPerf: 0,
        }
        state.frames.push(record)
        // 该 rAF 回调排在 Vue 微任务刷新之后、本帧绘制之前；回填帧时间即可。
        requestAnimationFrame(() => {
          record.render = Date.now()
          record.renderPerf = performance.now()
        })
      })
    }
  }
  Object.defineProperty(window, 'WebSocket', {
    value: PerfWebSocket,
    configurable: true,
    writable: true,
  })
  window.addEventListener('error', event => {
    state.pageErrors.push(String(event.message ?? event.error ?? 'unknown'))
  })
}

async function main() {
  const browser = await chromium.launch({
    headless: true,
    args: [
      '--disable-background-timer-throttling',
      '--disable-backgrounding-occluded-windows',
      '--disable-renderer-backgrounding',
    ],
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await context.addInitScript(installWebSocketProbe)
  // 底图 fixture 不参与本次延迟口径（AC30 由 26 另验）；快速失败避免外部网络等待。
  await context.route('**/__wmts-fixture/**', route => route.abort('failed'))
  const page = await context.newPage()
  const consoleErrors = []
  page.on('console', message => {
    if (message.type() === 'error') consoleErrors.push(message.text().slice(0, 500))
  })

  const playwrightVersion = JSON.parse(
    fs.readFileSync(new URL('../node_modules/@playwright/test/package.json', import.meta.url), 'utf8'),
  ).version
  console.log(`PERF_ENV node=${process.version} playwright=${playwrightVersion} chromium=${browser.version()}`)

  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByTestId('login-submit').click()
  await page.waitForURL(url => url.pathname === '/', { timeout: 60_000 })
  await page.getByTestId('current-user').filter({ hasText: username }).waitFor({ timeout: 60_000 })
  await page.waitForFunction(
    () => document.querySelector('[data-testid="monitor-stream-status"]')?.textContent?.includes('实时连接已建立'),
    undefined,
    { timeout: 60_000 },
  )

  const readyAt = Date.now()
  const readyInfo = await page.evaluate(() => ({
    url: location.href,
    visibility: document.visibilityState,
    timeOrigin: performance.timeOrigin,
    timezoneOffsetMinutes: new Date().getTimezoneOffset(),
    vehicleRows: document.querySelectorAll('[data-testid^="monitor-vehicle-"]').length,
    streamStatus: document.querySelector('[data-testid="monitor-stream-status"]')?.textContent?.trim() ?? '',
  }))
  console.log(`PERF_READY ${readyAt}`)
  console.log(`PERF_PAGE url=${readyInfo.url} visibility=${readyInfo.visibility} timeOrigin=${readyInfo.timeOrigin}`)

  if (screenshotsDir) {
    fs.mkdirSync(screenshotsDir, { recursive: true })
    await page.screenshot({ path: path.join(screenshotsDir, 'ready.png') }).catch(() => {})
  }

  await new Promise(resolve => setTimeout(resolve, durationMs))

  // 等最后一批 rAF 回调回填，再取回数据。
  console.log('PERF_PHASE evaluate_start')
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  const result = await page.evaluate(() => {
    const state = window.__vmsPerf
    return {
      finishedAt: Date.now(),
      visibility: document.visibilityState,
      timeOrigin: performance.timeOrigin,
      timezoneOffsetMinutes: new Date().getTimezoneOffset(),
      vehicleRows: document.querySelectorAll('[data-testid^="monitor-vehicle-"]').length,
      streamStatus: document.querySelector('[data-testid="monitor-stream-status"]')?.textContent?.trim() ?? '',
      sockets: state.sockets,
      events: state.events,
      pageErrors: state.pageErrors,
      frames: state.frames,
    }
  })
  if (screenshotsDir) {
    await page.screenshot({ path: path.join(screenshotsDir, 'finished.png') }).catch(() => {})
  }
  console.log('PERF_PHASE evaluate_done')

  const payload = {
    readyAt,
    consoleErrors,
    ...result,
  }
  fs.mkdirSync(path.dirname(framesPath), { recursive: true })
  fs.writeFileSync(framesPath, JSON.stringify(payload))
  console.log('PERF_PHASE frames_written')
  await browser.close()
  console.log('PERF_PHASE browser_closed')
  const closed = result.events.filter(event => event.kind === 'close').length
  console.log(`PERF_DONE frames=${result.frames.length} sockets=${result.sockets} closes=${closed} `
    + `vehicleRows=${result.vehicleRows} pageErrors=${result.pageErrors.length} consoleErrors=${consoleErrors.length}`)
  if (!result.frames.length) {
    console.error('未捕获到任何 /ws/monitor 位置帧')
    process.exit(3)
  }
}

main().catch(error => {
  console.error('PERF_FAILED', error)
  process.exit(1)
})
