import { test, expect } from '@playwright/test'

// 浏览器端到端：上传(带改编需求) → 生成 → 工作台 → AI 对话精修 → YAML 双向同步。
// 后端用 stub 离线确定性，故断言可稳定复现。
test('上传带需求 → 生成 → 工作台 → 对话精修 → YAML 同步', async ({ page }) => {
  const errors = []
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))

  // [1] 首页：填改编需求（Feature 1a）+ 选《活着》样本
  await page.goto('/')
  await page.fill('#req', '突出悬疑紧张氛围，多用画外音表现心理')
  await page.locator('.chip').first().click()

  // [2] 开始生成 → 进度(SSE) → 自动进入工作台
  const start = page.locator('button.start')
  await expect(start).toBeEnabled()
  await start.click()
  await page.waitForURL('**/workbench', { timeout: 30_000 })

  // [3] 工作台：场景大纲已渲染（≥3 场景）
  await expect(page.locator('.outline .oi').first()).toBeVisible()
  expect(await page.locator('.outline .oi').count()).toBeGreaterThanOrEqual(3)

  // [4] 打开「AI 对话」标签 —— 开场白应回显上传时填写的改编需求（1a→1b 串联）
  await page.locator('.tabs button', { hasText: 'AI 对话' }).click()
  await expect(page.locator('.tabpane.chat')).toContainText('突出悬疑')

  // [5] 发送精修指令（Feature 1b）→ 剧本被改写并同步
  const moodBefore = (await page.locator('.atag.mood').first().textContent())?.trim()
  expect(moodBefore).not.toBe('紧张') // 基线情绪非「紧张」，才能证明是对话改出来的
  await page.locator('.chat-input textarea').fill('把 S1 改得更紧张，并给 S1 加一句画外音')
  await page.locator('.chat-input .send').click()
  await expect(page.locator('.tabpane.chat')).toContainText('画外音', { timeout: 20_000 })
  // 卡片情绪标注更新为「紧张」
  await expect(page.locator('.atag.mood')).toContainText('紧张')

  // [6] 切到 YAML 视图：精修结果应已同步（含 voiceover 元素）
  await page.locator('.seg button', { hasText: 'YAML' }).click()
  await expect(page.locator('textarea.yta')).toHaveValue(/voiceover/)

  // 全程无前端控制台错误
  expect(errors, '前端控制台不应有错误：' + errors.join(' | ')).toHaveLength(0)
})
