import { test, expect } from '@playwright/test'

// 浏览器端到端：工作台三栏各自纵向滚动 + 返回首页按钮（PR1 / 修订 R3）。
// 纯 CSS 高度链/滚动无法在 happy-dom 单测断言，故由真实浏览器验证。
// RED（修复前 .wb{height:100%} 被 n-config-provider 断链）：整页滚动、各栏未被视口高度约束。
// GREEN（.wb{height:100dvh}）：整页不滚动、三栏各自被视口高度约束并独立出现滚动条。
test('工作台三栏各自滚动且整页不滚动 + 返回首页', async ({ page }) => {
  // 进入工作台（选《活着》样本 → 生成 → 自动跳转）
  await page.goto('/')
  await page.locator('.chip').first().click()
  await page.locator('button.start').click()
  await page.waitForURL('**/workbench', { timeout: 30_000 })
  await expect(page.locator('.outline .oi').first()).toBeVisible()

  const innerH = await page.evaluate(() => window.innerHeight)

  // [1] 整页本身不应滚动（内容被收进各栏，而非整页滚动）。
  const docScroll = await page.evaluate(() => document.documentElement.scrollHeight)
  expect(docScroll, '整页不应出现纵向滚动').toBeLessThanOrEqual(innerH + 2)

  // [2] 工作台根高度 ≈ 视口高度。
  const wbH = await page.locator('.wb').evaluate((el) => el.clientHeight)
  expect(Math.abs(wbH - innerH)).toBeLessThanOrEqual(2)

  // [3] 三栏都被视口高度约束（=> 内容超长时各自出现滚动条），且 overflow-y 可滚动。
  for (const sel of ['.pane.left', '.pane.center', '.pane.right']) {
    const box = page.locator(sel)
    const ch = await box.evaluate((el) => el.clientHeight)
    expect(ch, `${sel} 高度应被视口约束`).toBeLessThanOrEqual(innerH + 2)
  }
  for (const sel of ['.pane.left', '.pane.center']) {
    const oy = await page.locator(sel).evaluate((el) => getComputedStyle(el).overflowY)
    expect(['auto', 'scroll'], `${sel} 应为可滚动容器`).toContain(oy)
  }

  // [4] 返回首页按钮回到 /
  await page.locator('.home-btn').click()
  await page.waitForURL((u) => new URL(u).pathname === '/', { timeout: 10_000 })
})
