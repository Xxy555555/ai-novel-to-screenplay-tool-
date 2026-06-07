import { defineConfig, devices } from '@playwright/test'

// 浏览器端到端配置。前置：后端（stub）跑在 :8080，前端 dev 跑在 :5173（/api 代理到 :8080）。
// 见 e2e/README.md 的启动步骤。
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
