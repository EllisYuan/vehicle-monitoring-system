import { expect, test } from '@playwright/test'

const USERNAME = process.env.VMS_E2E_USERNAME ?? ''
const PASSWORD = process.env.VMS_E2E_PASSWORD ?? ''

test.describe('登录与退出', () => {
  test.skip(!USERNAME || !PASSWORD, '需要 VMS_E2E_USERNAME / VMS_E2E_PASSWORD 指向真实部署的管理员')

  test('未登录访问首页被重定向到登录页', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByTestId('login-form')).toBeVisible()
  })

  test('错误凭证停留在登录页且不泄露账号是否存在', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(USERNAME)
    await page.getByLabel('密码').fill('definitely-wrong-password')
    await page.getByTestId('login-submit').click()

    await expect(page.getByTestId('login-error')).toHaveText('用户名或密码错误')
    await expect(page).toHaveURL(/\/login/)
  })

  test('正确凭证进入会话，退出后原会话不能继续访问', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(USERNAME)
    await page.getByLabel('密码').fill(PASSWORD)
    await page.getByTestId('login-submit').click()

    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByTestId('current-user')).toContainText(USERNAME)

    const token = await page.evaluate(() => sessionStorage.getItem('vms.session.token'))
    expect(token).toBeTruthy()
    await expect(page).not.toHaveURL(new RegExp(token as string))

    await page.getByTestId('logout').click()
    await expect(page).toHaveURL(/\/login/)

    const afterLogout = await page.evaluate(async (oldToken) => {
      const response = await fetch('/api/v1/auth/me', { headers: { satoken: oldToken } })
      return response.status
    }, token)
    expect(afterLogout).toBe(401)

    await page.goto('/')
    await expect(page).toHaveURL(/\/login/)
  })
})
