import { spawn } from 'node:child_process'
import { expect, test } from '@playwright/test'

const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''
const tcpPort = process.env.VMS_E2E_TCP_PORT ?? ''
const java = process.env.VMS_E2E_JAVA ?? ''
const simulatorClasspath = process.env.VMS_E2E_SIMULATOR_CP ?? ''

// 报警由独立 simulator 经真实 TCP 触发；处置与只读范围均经真实浏览器与后端。
test.describe('报警处置与车队长只读范围 browser / REST', () => {
  test.skip(!username || !password || !tcpPort || !java || !simulatorClasspath,
    '需要 BrowserFlowTest 提供真实后端、TCP 与独立 simulator CLI 环境')

  test('管理员按状态机处置报警，车队长只读且电话掩码', async ({ page, request, browser }) => {
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
    async function create(path: string, data: object) {
      const response = await request.post(`/api/v1${path}`, { headers, data })
      expect(response.status()).toBe(201)
      return await response.json() as { id: number }
    }
    const fleet = await create('/fleets', { fleetCode: `ALM-${suffix}`, fleetName: `报警车队 ${suffix}` })
    const plateNo = `报警-${suffix}`
    const vehicle = await create('/vehicles', { plateNo, fleetId: fleet.id })
    const driver = await create('/drivers', {
      driverNo: `AD${suffix}`, name: `报警司机${suffix}`, phone: '13812345678', fleetId: fleet.id,
    })
    expect((await request.put(`/api/v1/vehicles/${vehicle.id}/driver`, {
      headers, data: { driverId: driver.id },
    })).ok()).toBeTruthy()
    const terminalNo = `${Date.now()}0000013`
    const terminal = await create('/terminals', { terminalNo })
    expect((await request.put(`/api/v1/vehicles/${vehicle.id}/terminal`, {
      headers, data: { terminalId: terminal.id },
    })).ok()).toBeTruthy()

    let output = ''
    const simulator = spawn(java, ['-cp', simulatorClasspath, 'com.vms.simulator.TerminalSimulator',
      '--host', '127.0.0.1', '--port', tcpPort, '--terminal', terminalNo,
      '--positions', '4', '--interval-ms', '1100', '--linger-ms', '15000', '--speed', '100',
    ], { windowsHide: true })
    function collect(chunk: Buffer) {
      const text = chunk.toString()
      output += text
      if (text.trim()) console.log(`[simulator] ${text.trim()}`)
    }
    simulator.stdout.on('data', collect)
    simulator.stderr.on('data', collect)
    try {
      await expect.poll(async () => {
        const response = await request.get('/api/v1/terminals', { headers })
        const list = await response.json() as Array<{ id: number; communicationStatus: string }>
        return list.find(item => item.id === terminal.id)?.communicationStatus
      }, { timeout: 20_000 }).toBe('ONLINE')
      await expect.poll(async () => {
        const response = await request.get('/api/v1/monitor/snapshot', { headers })
        const snapshot = await response.json() as {
          vehicles: Array<{ plateNo: string; position: { speedKph: number } | null }>
        }
        return snapshot.vehicles.find(item => item.plateNo === plateNo)?.position?.speedKph ?? 0
      }, { timeout: 20_000, message: `等待 ${plateNo} 超速位置进入实时快照` }).toBeGreaterThan(80)
      // 连续超速实时点按 ticket 12 判定成立 ACTIVE；真实 TCP 链路产生真实报警。
      await expect.poll(async () => {
        const response = await request.get('/api/v1/alarms', { headers })
        if (!response.ok()) {
          console.log(`[diag] alarms HTTP ${response.status()}: ${(await response.text()).slice(0, 300)}`)
          return false
        }
        const rows = await response.json() as Array<{ plateNo: string; conditionState: string }>
        const mine = rows.filter(row => row.plateNo === plateNo)
        if (mine.length) console.log(`[diag] alarm rows: ${JSON.stringify(mine)}`)
        return mine.some(row => row.conditionState === 'ACTIVE')
      }, { timeout: 30_000, message: `等待 ${plateNo} 超速报警成立` }).toBe(true)
      const alarmRows = await (await request.get('/api/v1/alarms', { headers }))
        .json() as Array<{ id: number; plateNo: string; handleStatus: string }>
      const alarm = alarmRows.find(row => row.plateNo === plateNo)!
      expect(alarm.handleStatus).toBe('PENDING')

      await page.goto('/monitor/alarms')
      await expect(page.getByTestId('alarm-table')).toContainText(plateNo)
      await page.getByTestId(`alarm-open-${alarm.id}`).click()
      await expect(page.getByTestId('alarm-detail')).toBeVisible()
      await expect(page.getByTestId('alarm-detail-condition')).toHaveText('活跃')
      await expect(page.getByTestId('alarm-detail-handle')).toHaveText('待处理')
      await expect(page.getByTestId('alarm-detail-driver')).toContainText(`报警司机${suffix}`)
      await expect(page.getByTestId('alarm-detail-phone')).toHaveText('13812345678')
      await expect(page.getByTestId('alarm-detail-rule')).toContainText('阈值 80 km/h')
      await expect(page.getByTestId('alarm-handle-history-empty')).toBeVisible()
      await page.getByTestId('alarm-handle-remark').fill('已联系司机核实')
      await page.getByTestId('alarm-handle-start').click()
      await expect(page.getByTestId('alarm-notice')).toContainText('已开始处理')
      await expect(page.getByTestId('alarm-detail-handle')).toHaveText('处理中')
      await expect(page.getByTestId('alarm-handle-history')).toContainText('已联系司机核实')
      await page.getByTestId('alarm-handle-finish').click()
      await expect(page.getByTestId('alarm-notice')).toContainText('报警已处理')
      await expect(page.getByTestId('alarm-detail-handle')).toHaveText('已处理')
      await expect(page.getByTestId('alarm-handle-history').locator('li')).toHaveCount(2)
      await expect(page.getByTestId('alarm-terminal-note')).toBeVisible()
      await page.screenshot({
        path: '../.scratch/vehicle-monitoring/evidence/13-alarm-handling.png', fullPage: true,
      })

      const leaderName = `leader-${suffix}`
      await create('/users', {
        username: leaderName, password: 'Leader-Pass-2026', roleCode: 'FLEET_LEADER', fleetIds: [fleet.id],
      })
      const leaderContext = await browser.newContext()
      const leaderPage = await leaderContext.newPage()
      try {
        await leaderPage.goto('/login')
        await leaderPage.getByLabel('用户名').fill(leaderName)
        await leaderPage.getByLabel('密码', { exact: true }).fill('Leader-Pass-2026')
        await leaderPage.getByTestId('login-submit').click()
        await expect(leaderPage.getByTestId('current-user')).toContainText(leaderName, { timeout: 20_000 })
        await leaderPage.goto('/monitor/alarms')
        await expect(leaderPage.getByTestId('alarm-readonly-note')).toBeVisible()
        await leaderPage.getByTestId(`alarm-open-${alarm.id}`).click()
        await expect(leaderPage.getByTestId('alarm-detail-phone')).toHaveText('138****5678')
        await expect(leaderPage.getByTestId('alarm-readonly-detail-note')).toBeVisible()
        await expect(leaderPage.getByTestId('alarm-handle-form')).toHaveCount(0)
        await leaderPage.screenshot({
          path: '../.scratch/vehicle-monitoring/evidence/13-alarm-leader-readonly.png', fullPage: true,
        })
      } finally {
        await leaderContext.close()
      }
      expect(output).toContain('position: accepted')
      console.log(`simulator output:\n${output}`)
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
