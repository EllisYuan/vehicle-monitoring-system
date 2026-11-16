import { spawn } from 'node:child_process'
import { expect, test } from '@playwright/test'

const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const tcpPort = process.env.VMS_E2E_TCP_PORT ?? ''
const java = process.env.VMS_E2E_JAVA ?? ''
const simulatorClasspath = process.env.VMS_E2E_SIMULATOR_CP ?? ''

// 指令只由页面提交，经真实 TCP 下发；终端为独立 simulator CLI，真实回 0x0001 并应用 0x8202。
test.describe('文本与临时跟踪指令 public TCP / REST / browser', () => {
  test.skip(!username || !password || !tcpPort || !java || !simulatorClasspath,
    '需要 BrowserFlowTest 提供真实后端、TCP 与独立 simulator CLI 环境')

  test('页面发起文本与临时跟踪，终端应答后通信结果独立更新', async ({ page, request }) => {
    const suffix = Date.now().toString(36)
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
    const fleet = await create('/fleets', { fleetCode: `CMD-${suffix}`, fleetName: `指令车队 ${suffix}` })
    const vehicle = await create('/vehicles', { plateNo: `指令-${suffix}`, fleetId: fleet.id })
    const terminalNo = `${Date.now()}0000008`
    const terminal = await create('/terminals', { terminalNo })
    expect((await request.put(`/api/v1/vehicles/${vehicle.id}/terminal`, {
      headers, data: { terminalId: terminal.id },
    })).ok()).toBeTruthy()

    let output = ''
    let pending = ''
    const positionEvents: { at: number; line: string }[] = []
    function collect(chunk: Buffer) {
      const text = chunk.toString()
      output += text
      pending += text
      const lines = pending.split(/\r?\n/)
      pending = lines.pop() ?? ''
      for (const line of lines) {
        if (line.includes('position: accepted')) positionEvents.push({ at: Date.now(), line })
      }
    }
    const simulator = spawn(java, ['-cp', simulatorClasspath, 'com.vms.simulator.TerminalSimulator',
      '--host', '127.0.0.1', '--port', tcpPort, '--terminal', terminalNo,
      '--positions', '3', '--interval-ms', '20000', '--linger-ms', '20000'],
    { windowsHide: true })
    simulator.stdout.on('data', collect)
    simulator.stderr.on('data', collect)
    try {
      await expect.poll(async () => {
        const response = await request.get('/api/v1/terminals', { headers })
        const list = await response.json() as Array<{ id: number; communicationStatus: string }>
        return list.find(item => item.id === terminal.id)?.communicationStatus
      }, { timeout: 20_000 }).toBe('ONLINE')

      await page.goto('/monitor/commands')
      await expect(page.getByTestId('commands-text-terminal').locator(`option[value="${terminal.id}"]`))
        .toHaveCount(1)
      await page.getByTestId('commands-text-terminal').selectOption(String(terminal.id))
      await page.getByTestId('commands-text-input').fill('模拟文本指令')
      await page.getByTestId('commands-text-submit').click()
      const latest = page.getByTestId('commands-latest')
      await expect(latest).toContainText('文本通知')
      await expect(page.getByTestId('commands-latest-submission')).toContainText('已提交')
      await expect(page.getByTestId('commands-latest-communication')).toContainText('终端应答成功', { timeout: 15_000 })

      await page.getByTestId('commands-tracking-terminal').selectOption(String(terminal.id))
      await page.getByTestId('commands-tracking-interval').fill('1')
      await page.getByTestId('commands-tracking-valid').fill('60')
      await page.getByTestId('commands-tracking-submit').click()
      await expect(latest).toContainText('临时跟踪')
      await expect(page.getByTestId('commands-table')).toContainText('间隔 1 秒 · 有效期 60 秒')
      await expect(page.getByTestId('commands-latest-communication')).toContainText('终端应答成功', { timeout: 15_000 })

      await expect.poll(() => output).toContain('text: received type=1 flag=1 content=模拟文本指令')
      await expect.poll(() => output).toContain('tracking: intervalSeconds=1 validSeconds=60')
      await expect.poll(() => output).toContain('ack: responseId=0x8300')
      await expect.poll(() => output).toContain('ack: responseId=0x8202')
      // 跟踪生效后，后续位置上报按 1 秒间隔继续；默认间隔为 20 秒，用于证明上报行为确实改变。
      await expect.poll(() => positionEvents.length, { timeout: 30_000 }).toBeGreaterThanOrEqual(3)
      const trackedPositions = positionEvents.filter(event => event.line.includes('trackingIntervalSeconds=1'))
      expect(trackedPositions.length).toBeGreaterThanOrEqual(2)
      const trackingGap = trackedPositions[trackedPositions.length - 1].at
        - trackedPositions[trackedPositions.length - 2].at
      expect(trackingGap).toBeGreaterThanOrEqual(500)
      expect(trackingGap).toBeLessThan(5_000)
      // 保留独立 CLI 的真实接收、应答与跟踪参数输出，供 evidence 核对
      console.log(`simulator output:\n${output}`)
      await page.screenshot({ path: 'test-results/16-commands-ack.png', fullPage: true })
    } finally {
      simulator.kill()
      await new Promise<void>(resolve => {
        if (simulator.exitCode !== null) {
          resolve()
          return
        }
        simulator.once('exit', () => resolve())
        setTimeout(resolve, 5_000).unref()
      })
    }
  })
})
