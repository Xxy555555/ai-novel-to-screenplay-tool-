# ScriptForge · AI 小说转剧本工具

把 3 章以上的小说，一键转换为**结构化、可校验、可视化编辑**的剧本初稿（YAML）。

> 状态：**端到端可运行**。后端三阶段管线 + 前端三屏全部实现并联通；默认离线 `stub` 模式无需任何 API Key 即可现场演示完整流程。
## 定义剧本的 YAML Schema ：
```
docs/yaml-schema-design.md
```
## 演示视频的链接：
```
https://www.bilibili.com/video/BV1UdEt6VEK3/?vd_source=ab27eb7c329d783d3a686c78e088a1b8
```
## 亮点

- **角色圣经 / 跨章一致性**：渐进实体消解，把「我 / 少爷 / 老爷」归并到同一角色 id，全篇引用不漂移。
- **三阶段管线（理解 → 生成 → 质检）**：可解释、可展示中间产物、出错可定位。
- **Schema 驱动 + 自动修复**：JSON Schema 校验 → LLM 限次修复 → 规则兜底，保证输出 100% 合法 YAML。
- **改编质量报告**：对白归属率、角色一致性、场景头完整率、演/说比、综合评分。
- **可视化工作台**：三栏（大纲 / 卡片⇄YAML 双向同步 / 角色圣经 + 质量报告）+ Fountain 预览 + 导出。
- **上传时提需求**：上传/选样本时可填「改编需求」（如「突出悬疑、多用画外音」），注入理解层并记入 `meta.user_requirements` 溯源。
- **AI 多轮对话精修**：工作台内与 AI 对话直接改剧本（「把 S2 改得更紧张」「给主角加画外音」「改标题」「删/增场景」），自动 Schema 校验并双向同步到卡片/YAML；离线 stub 也能确定性演示。
- **实时进度（SSE）**：章节切分 → 角色识别 → 别名归并 → 场景生成逐步可见。
- **通用大模型适配器**：换 `(base-url + model + api-key)` 配置即切换任意模型，不绑定厂商。

## 目录结构

```
.
├─ docs/
│  ├─ PRD.md                    产品需求（含通用大模型适配器设计）
│  ├─ UI-Prototype-Design.md    前端原型设计
│  └─ yaml-schema-design.md     YAML Schema 逐字段设计文档（契约）
├─ design/                      可交互 HTML 原型（首页/进度/工作台）
├─ backend/                     Spring Boot 3 后端（Java 17）
│  └─ src/main/java/com/scriptforge/
│     ├─ model/                 领域模型（record）
│     ├─ llm/                   通用大模型适配器（stub/openai/claude）
│     ├─ pipeline/              切分→理解→生成→质检 + 编排器
│     ├─ schema/                Schema 校验 + 自动修复
│     ├─ export/                YAML / Fountain 导出
│     └─ controller/            REST + SSE 接口
│  └─ src/main/resources/
│     ├─ screenplay.schema.json 输出契约
│     └─ samples/               内置示例小说（中/英）
└─ frontend/                    Vue3 + Vite 前端
   └─ src/views/                HomeView / ProgressView / WorkbenchView
```

## 技术栈

- 前端：Vue3 + Vite + Pinia + Vue Router + Naive UI + js-yaml（深色影视工作台主题）
- 后端：Java 17 + Spring Boot 3 + Jackson YAML + networknt json-schema-validator
- AI：通用大模型适配器（OpenAI 兼容 / 原生 Claude / 离线 stub），详见 `docs/PRD.md` 6.4

## 本地运行

### 后端（端口 8080）

```bash
cd backend
# Windows 若 JAVA_HOME 多了一层 \bin，用 JDK 根目录覆盖（见 CLAUDE.md）：
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" spring-boot:run
# 或打包后运行（中文路径下更稳）：
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" -DskipTests package
java -Dfile.encoding=UTF-8 -jar target/novel-to-screenplay.jar
# 健康检查：GET http://localhost:8080/api/health
```

### 前端（端口 5173，已配 /api 代理到 8080）

```bash
cd frontend
npm install
npm run dev
# 打开 http://localhost:5173 ：选「《活着》节选」示例 → 开始生成 → 看进度 → 进入工作台
```

## REST / SSE 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/generate` | 建会话（`{sample_id 或 text, language, title, requirements}`，snake_case），返回 `session_id` |
| GET | `/api/generate/{id}/stream` | SSE 流式生成（事件：stage/progress/log/character/alias/scene/complete/error） |
| GET | `/api/screenplay/{id}` `{,/yaml,/fountain}` | 取最终剧本（JSON / YAML / Fountain 文本） |
| GET | `/api/samples` | 内置示例列表 |
| POST | `/api/validate` | 重校验当前 YAML，返回 `{valid, error_count, errors, report}` |
| POST | `/api/chat` | AI 多轮对话精修：`{screenplay, message, history, language}` → `{reply, screenplay, changed, valid, error_count, errors}` |

## 切换大模型（不改代码）

改 `backend/src/main/resources/application.yml` 的 `scriptforge.llm.*`，或用 `SCRIPTFORGE_LLM_*` 环境变量：

```bash
# 例：接 DeepSeek（OpenAI 兼容）
export SCRIPTFORGE_LLM_PROVIDER=openai
export SCRIPTFORGE_LLM_BASE_URL=https://api.deepseek.com/v1
export SCRIPTFORGE_LLM_MODEL=deepseek-chat
export SCRIPTFORGE_LLM_API_KEY=sk-xxx
# 例：原生 Claude
export SCRIPTFORGE_LLM_PROVIDER=claude
export SCRIPTFORGE_LLM_BASE_URL=https://api.anthropic.com
export SCRIPTFORGE_LLM_MODEL=claude-opus-4-8
export SCRIPTFORGE_LLM_API_KEY=sk-ant-xxx
```

默认 `provider=stub`：离线规则桩，无 Key 也能跑通全流程。



## 测试

端到端测试报告：[`docs/TEST-REPORT.md`](docs/TEST-REPORT.md)（由 `node scripts/gen-test-report.mjs` 聚合生成）。

```bash
# 后端：单元 + 集成 + HTTP 全栈 e2e + JaCoCo 覆盖率
cd backend && JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" test
#   覆盖率 HTML：backend/target/site/jacoco/index.html

# 前端：Vitest 组件/接口/store 测试 + v8 覆盖率
cd frontend && npm run test          # 或 npm run test:coverage
#   覆盖率 HTML：frontend/coverage/index.html

# 浏览器端到端（Playwright，需前后端在跑）：见 e2e/README.md
cd e2e && npm install && npx playwright install chromium && npm test

# 生成聚合测试报告 → docs/TEST-REPORT.md
node scripts/gen-test-report.mjs
```

三层覆盖：**后端** 20 用例（JUnit5/Spring Boot Test，含 `GenerationFlowIntegrationTest` HTTP 全栈 e2e）；
**前端** 21 用例（Vitest + @vue/test-utils）；**浏览器** 1 条全流程（Playwright + gstack `/browse`）。
测试全程用 `stub` 离线适配器，确定性、可进 CI、无需 API Key。

## 协作规范

新增功能基于 PR 提交（详见 `CLAUDE.md`「PR 提交规范」）：每个 PR 只做一件事、四段式描述（标题/功能描述/实现思路/测试方式）、合并后主分支保持可运行。
