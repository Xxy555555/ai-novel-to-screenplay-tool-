# 浏览器端到端测试（Playwright）

覆盖真实用户路径：**上传(带改编需求) → 生成(SSE) → 工作台 → AI 对话精修 → YAML 双向同步**。
后端使用 `stub` 离线适配器，断言确定、可复现、无需联网或 API Key。

## 前置：启动前后端（两个终端）

后端（强制 stub，端口 8080）。本项目路径含非 ASCII 目录，`spring-boot:run` 的 fork 类路径会乱码，
故用打包后的 jar 以相对路径启动：

```bash
cd backend
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" -q -DskipTests package
# Windows PowerShell：$env:SCRIPTFORGE_LLM_PROVIDER='stub'; & "D:/JDK/jdk17/bin/java" -jar target/novel-to-screenplay.jar
SCRIPTFORGE_LLM_PROVIDER=stub "D:/JDK/jdk17/bin/java" -jar target/novel-to-screenplay.jar
```

前端（dev 服务器，端口 5173，`/api` 代理到 8080）：

```bash
cd frontend && npm install && npm run dev
```

## 运行 e2e

```bash
cd e2e
npm install
npx playwright install chromium   # 首次需下载浏览器
npm test                          # = playwright test
npm run report                    # 查看 HTML 报告
```

可用 `E2E_BASE_URL` 覆盖前端地址（默认 `http://localhost:5173`）。

### 浏览器下载被墙时

若 `npx playwright install chromium` 拉取 cdn.playwright.dev 失败，可指向本机已有的 chromium，
新建临时 `pw-local.config.mjs` 在 `use.launchOptions.executablePath` 填入已装的
`chrome-headless-shell.exe` 路径，再 `npx playwright test -c pw-local.config.mjs`。
（本仓库即以此方式跑通过一次，断言全绿。）

## 说明

- 同一条用户路径也已用 gstack `/browse` 做过人工驱动验证（截图见测试报告 `docs/TEST-REPORT.md`）。
- 后端的「HTTP 全栈端到端」（不依赖浏览器、随 `mvn test` 一起跑）见
  `backend/.../controller/GenerationFlowIntegrationTest.java`。
