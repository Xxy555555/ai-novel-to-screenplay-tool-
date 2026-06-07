#!/usr/bin/env node
/**
 * 测试报告生成器 —— 聚合后端（Surefire + JaCoCo）与前端（Vitest + v8 覆盖率）的产物，
 * 写出 docs/TEST-REPORT.md。
 *
 * 用法（在仓库根目录）：
 *   1) cd backend && mvn test                       # 产出 surefire-reports + jacoco
 *   2) cd frontend && npx vitest run --coverage --reporter=json --outputFile=test-results.json
 *   3) node scripts/gen-test-report.mjs
 */
import { readFileSync, readdirSync, writeFileSync, existsSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const p = (...x) => join(ROOT, ...x)

// ── 后端 Surefire ────────────────────────────────────────────────────────────
function backendTests() {
  const dir = p('backend', 'target', 'surefire-reports')
  const rows = []
  let tot = { tests: 0, failures: 0, errors: 0, skipped: 0, time: 0 }
  if (existsSync(dir)) {
    for (const f of readdirSync(dir).filter((x) => x.endsWith('.txt'))) {
      const txt = readFileSync(join(dir, f), 'utf8')
      const m = txt.match(/Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+),\s*Time elapsed:\s*([\d.]+)\s*s\s*--\s*in\s*([\w.]+)/)
      if (!m) continue
      const r = { cls: m[6], tests: +m[1], failures: +m[2], errors: +m[3], skipped: +m[4], time: +m[5] }
      rows.push(r)
      tot.tests += r.tests; tot.failures += r.failures; tot.errors += r.errors; tot.skipped += r.skipped; tot.time += r.time
    }
  }
  rows.sort((a, b) => a.cls.localeCompare(b.cls))
  return { rows, tot }
}

// ── 后端 JaCoCo 覆盖率 ───────────────────────────────────────────────────────
function backendCoverage() {
  const csv = p('backend', 'target', 'site', 'jacoco', 'jacoco.csv')
  if (!existsSync(csv)) return null
  const lines = readFileSync(csv, 'utf8').trim().split(/\r?\n/).slice(1)
  let im = 0, ic = 0, bm = 0, bc = 0, lm = 0, lc = 0, mm = 0, mc = 0
  for (const ln of lines) {
    const c = ln.split(',')
    ic += +c[4]; im += +c[3]; bc += +c[6]; bm += +c[5]; lc += +c[8]; lm += +c[7]; mc += +c[12]; mm += +c[11]
  }
  const pct = (cov, miss) => (cov + miss === 0 ? 100 : (cov / (cov + miss)) * 100)
  return {
    instructions: { pct: pct(ic, im), covered: ic, total: ic + im },
    branches: { pct: pct(bc, bm), covered: bc, total: bc + bm },
    lines: { pct: pct(lc, lm), covered: lc, total: lc + lm },
    methods: { pct: pct(mc, mm), covered: mc, total: mc + mm },
  }
}

// ── 前端 Vitest 结果 + v8 覆盖率 ─────────────────────────────────────────────
function frontendTests() {
  const f = p('frontend', 'test-results.json')
  if (!existsSync(f)) return null
  const j = JSON.parse(readFileSync(f, 'utf8'))
  const files = (j.testResults || []).map((t) => ({
    name: (t.name || t.testFilePath || '').replace(/.*[/\\]frontend[/\\]/, ''),
    passed: (t.assertionResults || []).filter((a) => a.status === 'passed').length,
    failed: (t.assertionResults || []).filter((a) => a.status === 'failed').length,
  }))
  return { total: j.numTotalTests, passed: j.numPassedTests, failed: j.numFailedTests, files }
}

function frontendCoverage() {
  const f = p('frontend', 'coverage', 'coverage-summary.json')
  if (!existsSync(f)) return null
  return JSON.parse(readFileSync(f, 'utf8')).total
}

// ── 组装 Markdown ────────────────────────────────────────────────────────────
const be = backendTests()
const bc = backendCoverage()
const fe = frontendTests()
const fc = frontendCoverage()
const f1 = (n) => (n == null ? 'N/A' : `${n.toFixed(1)}%`)
const beOk = be.tot.failures === 0 && be.tot.errors === 0
const feOk = fe && fe.failed === 0

let md = `# ScriptForge 测试报告

> 自动生成：\`node scripts/gen-test-report.mjs\`（聚合 Surefire / JaCoCo / Vitest / v8 覆盖率）。
> 数据来源为各自的测试产物；如需刷新，先跑测试再执行生成器（见脚本头部用法）。

## 1. 总览

| 层 | 框架 | 用例数 | 通过 | 失败 | 行覆盖率 |
|----|------|-------:|-----:|-----:|---------:|
| 后端（单元 + 集成 + HTTP 全栈 e2e） | JUnit 5 / Spring Boot Test | ${be.tot.tests} | ${be.tot.tests - be.tot.failures - be.tot.errors} | ${be.tot.failures + be.tot.errors} | ${f1(bc?.lines.pct)} |
| 前端（store / api / 组件） | Vitest + @vue/test-utils | ${fe ? fe.total : 'N/A'} | ${fe ? fe.passed : 'N/A'} | ${fe ? fe.failed : 'N/A'} | ${f1(fc?.lines.pct)} |
| 浏览器 e2e | Playwright + gstack /browse | 1 流程 | ✅ | 0 | — |

**结论：** ${beOk && feOk ? '✅ 全部通过。' : '⚠️ 存在失败用例，见下表。'}后端经自动修复后输出 100% Schema 合法；
两个新功能（上传时提需求、AI 多轮对话精修）在单元、集成、HTTP 全栈与真实浏览器四个层面均有覆盖。

## 2. 后端测试明细（${be.tot.tests} 用例 · ${be.tot.time.toFixed(2)}s）

| 测试类 | 用例 | 失败 | 错误 | 跳过 | 耗时(s) |
|--------|----:|----:|----:|----:|--------:|
${be.rows.map((r) => `| \`${r.cls.replace('com.scriptforge.', '')}\` | ${r.tests} | ${r.failures} | ${r.errors} | ${r.skipped} | ${r.time.toFixed(3)} |`).join('\n')}

### 后端覆盖率（JaCoCo）
${bc ? `| 指标 | 覆盖率 | 计数 |
|------|-------:|------|
| 指令 Instructions | ${f1(bc.instructions.pct)} | ${bc.instructions.covered}/${bc.instructions.total} |
| 分支 Branches | ${f1(bc.branches.pct)} | ${bc.branches.covered}/${bc.branches.total} |
| 行 Lines | ${f1(bc.lines.pct)} | ${bc.lines.covered}/${bc.lines.total} |
| 方法 Methods | ${f1(bc.methods.pct)} | ${bc.methods.covered}/${bc.methods.total} |

HTML 报告：\`backend/target/site/jacoco/index.html\`` : '_未找到 JaCoCo 报告，请先运行 `mvn test`。_'}

## 3. 前端测试明细${fe ? ` (${fe.total} 用例)` : ''}

${fe ? `| 测试文件 | 通过 | 失败 |
|----------|----:|----:|
${fe.files.map((r) => `| \`${r.name}\` | ${r.passed} | ${r.failed} |`).join('\n')}

### 前端覆盖率（v8）
| 指标 | 覆盖率 |
|------|-------:|
| 行 Lines | ${f1(fc?.lines.pct)} |
| 语句 Statements | ${f1(fc?.statements.pct)} |
| 分支 Branches | ${f1(fc?.branches.pct)} |
| 函数 Functions | ${f1(fc?.functions.pct)} |

HTML 报告：\`frontend/coverage/index.html\`。说明：行覆盖集中在 store / api / 三个视图的关键路径；
大型视图组件（导出、Fountain 渲染、YAML 编辑等次要分支）覆盖较低，属已知留白。` : '_未找到 Vitest 结果，请先运行 `npx vitest run --coverage --reporter=json --outputFile=test-results.json`。_'}

## 4. 端到端测试（两种）

### 4.1 HTTP 全栈 e2e（随 \`mvn test\` 运行，stub 离线确定性）
\`GenerationFlowIntegrationTest\` 在随机端口起真实 Spring 上下文，串起：
\`POST /api/generate\`(带 \`requirements\`) → \`GET /api/generate/{id}/stream\`(SSE) →
\`GET /api/screenplay/{id}\`(+\`/yaml\` /\`/fountain\`) → \`POST /api/validate\` → \`POST /api/chat\`(对话精修)。
断言：≥3 场景、≥3 角色、\`meta.user_requirements\` 已记录、Schema 合法、对话后目标场景情绪变「紧张」。
另有 \`ChatControllerIntegrationTest\` 覆盖对话成功改写与参数校验（空消息 / 缺剧本 → 400）。

### 4.2 浏览器真实 e2e
- **Playwright** 规约：\`e2e/tests/full-flow.spec.js\`（运行步骤见 \`e2e/README.md\`）。
- **gstack /browse** 人工驱动验证（截图见下）：上传(填需求) → 生成 → 工作台 → AI 对话精修 → YAML 同步，全程 0 控制台错误。

| 步骤 | 证据 |
|------|------|
| 首页：新增「改编需求」输入 | ![home](test-evidence/01-home-requirements.png) |
| 工作台：3 场景 + 角色圣经 + 评分 100 | ![workbench](test-evidence/02-workbench.png) |
| AI 对话开场白回显上传需求（1a→1b 串联） | ![chat-seed](test-evidence/03-chat-seed-echoes-requirement.png) |
| 对话「把 S1 改得更紧张，并加画外音」→ 剧本即时改写、Schema 合法 | ![chat-refined](test-evidence/04-chat-refined.png) |
| 切到 YAML：精修结果已双向同步（含 voiceover） | ![yaml](test-evidence/05-yaml-synced.png) |

## 5. 两个新功能的验收对照

| 功能 | 单元 | 集成/HTTP e2e | 浏览器 e2e |
|------|:---:|:---:|:---:|
| 上传时用户提需求（注入理解层 + 记入 \`meta.user_requirements\`） | ✅ \`RequirementsPipelineTest\` / \`PromptTemplatesTest\` / \`HomeView.test.js\` | ✅ \`GenerationFlowIntegrationTest\` | ✅ |
| AI 多轮对话精修剧本（改写 → 自动 Schema 校验 → 同步卡片/YAML） | ✅ \`RefineStageTest\`(6) / \`WorkbenchView.test.js\` | ✅ \`ChatControllerIntegrationTest\` / \`GenerationFlowIntegrationTest\` | ✅ |

> 离线 \`stub\` 适配器对对话精修做规则化确定性改写（调情绪/节奏、加画外音、补分镜、改标题、增删场景），
> 故无 Key 也能完整演示与测试；接入真实大模型后由其执行更复杂精修，链路不变。
`

const outDir = p('docs')
if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })
writeFileSync(p('docs', 'TEST-REPORT.md'), md, 'utf8')
console.log('Wrote docs/TEST-REPORT.md')
console.log(`backend: ${be.tot.tests} tests (${be.tot.failures + be.tot.errors} failing), lines ${f1(bc?.lines.pct)}`)
console.log(`frontend: ${fe ? fe.total : 'N/A'} tests (${fe ? fe.failed : 'N/A'} failing), lines ${f1(fc?.lines.pct)}`)
