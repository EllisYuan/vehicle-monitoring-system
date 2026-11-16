import { expect, test, type Page } from '@playwright/test'

const username = process.env.VMS_E2E_USERNAME ?? ''
const password = process.env.VMS_E2E_PASSWORD ?? ''

async function signIn(page: Page, name: string, secret: string) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(name)
  await page.getByLabel('密码', { exact: true }).fill(secret)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('current-user')).toContainText(name)
}

test.describe('轨迹保留预览与确认', () => {
  test.skip(!username || !password, '需要真实测试后端管理员')

  test('管理员预览缩短范围后确认生效并留下维护记录', async ({ page }) => {
    await signIn(page, username, password)
    await page.getByTestId('switch-to-admin').click()
    await page.getByTestId('nav-admin-track-retention').click()

    await expect(page.getByTestId('retention-days')).toHaveText('30')
    await page.getByTestId('proposed-retention-days').fill('7')
    await page.getByTestId('preview-retention').click()

    const preview = page.getByTestId('retention-preview')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('从 30 天缩短为 7 天')
    await expect(preview).toContainText('待删除分区：p')
    await expect(page.getByTestId('retention-days')).toHaveText('30')

    await page.getByTestId('confirm-retention').click()
    await expect(page.getByTestId('retention-confirmed')).toContainText('已从 30 天缩短为 7 天')
    await expect(page.getByTestId('retention-days')).toHaveText('7')
    await expect(page.getByTestId('retention-runs')).toContainText('CONFIRM')
  })
})
