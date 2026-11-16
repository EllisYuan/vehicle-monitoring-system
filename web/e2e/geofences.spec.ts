import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''

interface FenceView {
  id: number
  fenceName: string
  fenceType: 'CIRCLE' | 'POLYGON'
  centerLng: number | null
  centerLat: number | null
  radiusM: number | null
  vertices: { lng: number; lat: number }[]
  enterEnabled: boolean
  leaveEnabled: boolean
  status: 'ENABLED' | 'DISABLED'
  version: number
  vehicles: { id: number; plateNo: string }[]
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
    headers, data: { fleetCode: `GEO-${suffix}`, fleetName: `围栏车队 ${suffix}` },
  })
  expect(fleet.status()).toBe(201)
  const fleetId = ((await fleet.json()) as { id: number }).id
  const vehicle = await request.post('/api/v1/vehicles', {
    headers, data: { plateNo: `围栏-${suffix}`, fleetId },
  })
  expect(vehicle.status()).toBe(201)
  return { fleetId, vehicleId: ((await vehicle.json()) as { id: number }).id, headers }
}

async function createCircleFence(request: APIRequestContext, headers: Record<string, string>,
  name: string, vehicleIds: number[]) {
  const created = await request.post('/api/v1/geofences', {
    headers,
    data: {
      fenceName: name, fenceType: 'CIRCLE', centerLng: 116.4, centerLat: 39.9, radiusM: 500,
      enterEnabled: true, leaveEnabled: true, vehicleIds,
    },
  })
  expect(created.status()).toBe(201)
  return ((await created.json()) as { id: number }).id
}

/** 真实鼠标绘制：返回地图画布可点击区域，坐标按比例换算。 */
async function mapBox(page: Page) {
  const map = page.getByTestId('geofence-map').locator('.map-target')
  await expect(map).toBeVisible()
  await map.scrollIntoViewIfNeeded()
  const box = await map.boundingBox()
  expect(box).toBeTruthy()
  return box!
}

async function clickAt(page: Page, box: { x: number; y: number; width: number; height: number },
  xRatio: number, yRatio: number) {
  await page.mouse.click(box.x + box.width * xRatio, box.y + box.height * yRatio)
  await page.waitForTimeout(120)
}

test.describe('电子围栏绘制与进出事件', () => {
  test.skip(!username || !password, '需要 BrowserFlowTest 提供真实测试后端管理员')

  test('绘制圆形并按米制半径校验，创建、编辑与停用留痕', async ({ page, request }) => {
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page, username, password)
    const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(token).toBeTruthy()
    const suffix = Date.now().toString(36)
    const { vehicleId } = await createFleetVehicle(request, token!, suffix)

    await page.getByTestId('nav-geofences').click()
    await expect(page.getByTestId('geofence-page')).toBeVisible()
    await expect(page.getByTestId('geofence-list-empty')).toBeVisible()

    // 两次单击画出圆形：先定圆心，再定半径；半径按大圆距离换算为米。
    const box = await mapBox(page)
    await page.getByTestId('draw-circle').click()
    await clickAt(page, box, 0.4, 0.6)
    await clickAt(page, box, 0.5, 0.6)
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-geometry-type', 'CIRCLE')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-valid', 'true')
    const drawnRadius = Number(await page.getByTestId('geofence-map').getAttribute('data-radius-m'))
    expect(drawnRadius).toBeGreaterThan(10)

    // 页面按服务端同一规则即时拒绝超出 10–100000 米的半径。
    await page.getByTestId('geofence-radius-input').fill('5')
    await page.getByTestId('geofence-radius-input').press('Tab')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-valid', 'false')
    await expect(page.getByTestId('geofence-draw-error')).toContainText('10 到 100000 米')
    await page.getByTestId('geofence-radius-input').fill('2000')
    await page.getByTestId('geofence-radius-input').press('Tab')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-valid', 'true')
    await expect(page.getByTestId('geofence-draw-error')).toHaveCount(0)

    await page.getByTestId('fence-name').fill('一号围栏-' + suffix)
    await page.getByTestId(`vehicle-option-${vehicleId}`).check()
    await page.getByTestId('save-geofence').click()
    await expect(page.getByTestId('geofence-notice')).toContainText('围栏已创建')
    await expect(page.getByTestId('geofence-notice')).toContainText('v1')

    const row = page.getByTestId('geofence-list').locator('li').filter({ hasText: '一号围栏-' + suffix })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('围栏-' + suffix)
    const fenceId = await row.getAttribute('data-testid')
    expect(fenceId).toBeTruthy()
    const id = Number(fenceId!.replace('geofence-row-', ''))

    let fence = await readFence(request, token!, id)
    expect(fence.fenceType).toBe('CIRCLE')
    expect(fence.radiusM).toBeCloseTo(2000, 3)
    // 圆心取真实点击位置换算的 WGS84 坐标，应落在视野中心附近且为经纬度（非像素或投影坐标）。
    expect(Math.abs(fence.centerLng! - 116.4)).toBeLessThan(0.2)
    expect(Math.abs(fence.centerLat! - 39.9)).toBeLessThan(0.2)
    expect(fence.enterEnabled).toBe(true)
    expect(fence.leaveEnabled).toBe(true)
    expect(fence.version).toBe(1)
    expect(fence.vehicles.map(vehicle => vehicle.id)).toEqual([vehicleId])

    // 编辑：加载已保存图形，改半径并关闭离开事件，版本递增且绑定保留。
    await page.getByTestId(`edit-geofence-${id}`).click()
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-geometry-type', 'CIRCLE')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-radius-m', '2000')
    await page.getByTestId('geofence-radius-input').fill('3000')
    await page.getByTestId('geofence-radius-input').press('Tab')
    await page.getByTestId('leave-enabled').uncheck()
    await page.getByTestId('save-geofence').click()
    await expect(page.getByTestId('geofence-notice')).toContainText('围栏已更新')
    await expect(page.getByTestId(`geofence-version-${id}`)).toHaveText('v2')

    fence = await readFence(request, token!, id)
    expect(fence.fenceName).toBe('一号围栏-' + suffix)
    expect(fence.radiusM).toBeCloseTo(3000, 3)
    expect(fence.enterEnabled).toBe(true)
    expect(fence.leaveEnabled).toBe(false)
    expect(fence.version).toBe(2)
    expect(fence.vehicles.map(vehicle => vehicle.id)).toEqual([vehicleId])

    // 停用：状态与版本落库，其他字段不变。
    await page.getByTestId(`toggle-geofence-${id}`).click()
    await expect(page.getByTestId('geofence-notice')).toContainText('围栏已停用')
    await expect(page.getByTestId(`geofence-status-${id}`)).toHaveText('已停用')
    await expect(page.getByTestId(`geofence-version-${id}`)).toHaveText('v3')
    fence = await readFence(request, token!, id)
    expect(fence.status).toBe('DISABLED')
    expect(fence.version).toBe(3)
    expect(fence.radiusM).toBeCloseTo(3000, 3)
    expect(fence.leaveEnabled).toBe(false)

    await page.screenshot({ path: 'test-results/15-geofence-create-edit.png', fullPage: true })
  })

  test('绘制多边形并按同一规则拒绝自交，闭合后顶点数正确', async ({ page, request }) => {
    await page.route('**/__wmts-fixture/**', route => route.abort('failed'))
    await signIn(page, username, password)
    const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(token).toBeTruthy()
    const suffix = Date.now().toString(36)

    await page.getByTestId('nav-geofences').click()
    const box = await mapBox(page)

    // 蝴蝶结四点在服务端与页面都应判定自交，保存前就被拦截。
    await page.getByTestId('draw-polygon').click()
    await clickAt(page, box, 0.3, 0.45)
    await clickAt(page, box, 0.6, 0.75)
    await clickAt(page, box, 0.6, 0.45)
    await clickAt(page, box, 0.3, 0.75)
    await page.getByTestId('finish-draw').click()
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-geometry-type', 'POLYGON')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-valid', 'false')
    await expect(page.getByTestId('geofence-draw-error')).toContainText('自交')

    await page.getByTestId('clear-geometry').click()
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-geometry-type', '')

    // 三角形闭合：最后单击第一个顶点结束绘制。
    await page.getByTestId('draw-polygon').click()
    await clickAt(page, box, 0.35, 0.45)
    await clickAt(page, box, 0.6, 0.45)
    await clickAt(page, box, 0.475, 0.72)
    await page.getByTestId('finish-draw').click()
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-geometry-type', 'POLYGON')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-vertex-count', '3')
    await expect(page.getByTestId('geofence-map')).toHaveAttribute('data-valid', 'true')

    await page.getByTestId('fence-name').fill('三角围栏-' + suffix)
    await page.getByTestId('save-geofence').click()
    await expect(page.getByTestId('geofence-notice')).toContainText('围栏已创建')

    const list = await request.get('/api/v1/geofences', { headers: { satoken: token! } })
    expect(list.ok()).toBeTruthy()
    const fences = (await list.json()) as FenceView[]
    const polygon = fences.find(item => item.fenceName === '三角围栏-' + suffix)
    expect(polygon).toBeTruthy()
    expect(polygon!.fenceType).toBe('POLYGON')
    expect(polygon!.vertices.length).toBe(3)
    expect(polygon!.radiusM).toBeNull()
    expect(polygon!.centerLng).toBeNull()

    await page.screenshot({ path: 'test-results/15-geofence-polygon.png', fullPage: true })
  })

  test('车队长看不到围栏入口，跨车队围栏请求被拒绝', async ({ page, request }) => {
    const suffix = `scope${Date.now().toString(36)}`
    const adminToken = await apiLogin(request)
    const granted = await createFleetVehicle(request, adminToken, `A${suffix}`)
    const foreign = await createFleetVehicle(request, adminToken, `B${suffix}`)
    await createCircleFence(request, { satoken: adminToken }, `甲队围栏-${suffix}`, [granted.vehicleId])
    const foreignFenceId = await createCircleFence(request,
      { satoken: adminToken }, `乙队围栏-${suffix}`, [foreign.vehicleId])

    const leaderName = `geo-leader-${suffix}`
    const leaderPassword = 'Leader-Pass-2026'
    const created = await request.post('/api/v1/users', {
      headers: { satoken: adminToken },
      data: { username: leaderName, password: leaderPassword, roleCode: 'FLEET_LEADER',
        fleetIds: [granted.fleetId] },
    })
    expect(created.status()).toBe(201)

    await signIn(page, leaderName, leaderPassword)
    expect(await page.getByTestId('nav-geofences').count()).toBe(0)
    await page.goto('/monitor/geofences')
    await expect(page).toHaveURL(/\/$/)
    expect(await page.getByTestId('geofence-page').count()).toBe(0)

    const leaderToken = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(leaderToken).toBeTruthy()
    const denied = await request.get('/api/v1/geofences', {
      headers: { satoken: leaderToken! }, params: { fleetId: foreign.fleetId },
    })
    expect(denied.status()).toBe(403)
    const forbiddenRead = await request.get(`/api/v1/geofences/${foreignFenceId}`, {
      headers: { satoken: leaderToken! },
    })
    expect(forbiddenRead.status()).toBe(403)

    await page.screenshot({ path: 'test-results/15-geofence-permission.png', fullPage: true })
  })
})

async function readFence(request: APIRequestContext, token: string, id: number): Promise<FenceView> {
  const response = await request.get(`/api/v1/geofences/${id}`, { headers: { satoken: token } })
  expect(response.ok()).toBeTruthy()
  return (await response.json()) as FenceView
}