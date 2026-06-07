# 工作台增强 + AI 质量评测 · 实施计划

> 状态：已与产品方逐项确认，按下述 5 个独立 PR 分步落地。
> 适用范围：`frontend/src/views/WorkbenchView.vue` 为主，新增前端 `stores/chat.js`、后端评测端点。

## 背景与目标

ScriptForge 的核心（生成管线 + 多轮对话精修 + 测试报告）已端到端可用并合并主分支。本轮围绕**工作台使用体验**与**改编质量闭环**做四项增强，均来自实际使用反馈：

1. 三栏（场景大纲 / 编辑器 / 角色·质量·对话）滚动不顺、缺少返回首页入口；YAML 视图总是整部剧本，与「卡片只看当前场景」不一致。
2. 与 AI 对话改完剧本后，前端展示需可靠同步；AI 回复过于啰嗦，应只说「改了哪里」。
3. 对话不持久化，每次进入工作台都被清空；需要 ChatGPT 式多线程历史。
4. 缺少「改编质量」评判：将**生成剧本 + 原著小说**单独交给 AI（隔离其它上下文以免干扰判断），给出评分、总体评价与修改建议。

## 已确认的设计决策

| 议题 | 决策 |
| --- | --- |
| YAML 范围 | 编辑器顶部加「当前场景 / 完整剧本」开关，**默认当前场景** |
| 历史对话 | ChatGPT 式**多线程**，可新建/切换/删除；存 **localStorage**，按 `sessionId` 分桶；**所有线程共用工作台当前剧本**（剧本仍是单一事实源，可被卡片/YAML 手改） |
| 评测输出 | 综合评分(0–100) + 总体评价 + 修改建议列表 |
| 评测触发 | 手动点按钮触发，结果**仅展示**（不一键应用） |

## 关键事实（代码勘探结论）

- 后端**无数据库**，状态都是内存 `ConcurrentHashMap`（重启即丢）→ 历史对话存前端 localStorage 最合适。
- **原著小说文本在后端持久可取**：`GenerationService.pending`（`InputSpec.text()`）按 `sessionId` 存放且从不删除 → 评测可在后端按 `sessionId` 取原文。
- `POST /api/chat` 本就**无状态**（前端每次发 `screenplay + history`）→ 多线程历史天然契合。
- 三栏 `.pane` 已是 `overflow-y:auto`，但右栏 `.right` 为 `overflow:hidden`、由子元素滚动；暗色主题下滚动条几乎不可见，需补可见样式并统一。
- 对话改剧本的同步靠 `applyRefined()` 重新赋值 `data.value`（卡片由 `curScene` computed 派生，自动刷新）；需核实 YAML 视图刷新路径。

## 按 PR 拆分

> 规范：每个 PR 只做一件事、四段式描述；后端 `mvn` 须用 `JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn"`，集成测试强制 `provider=stub`；前端 `npm run build` 须通过。合并后主分支保持可运行。

### PR1 —— 三栏滚动条 + 返回首页按钮（item 1a/1b）
纯前端，`WorkbenchView.vue`。
- 为三栏滚动容器补**可见的暗色滚动条样式**（`::-webkit-scrollbar` + Firefox `scrollbar-width/color`）：`.pane`、`.char-list`、`.chat-msgs`、`.tabpane.qual`、`.script-page`；统一右栏滚动行为，保证角色/质量/对话三个面板都能独立滚动到底。
- 顶部工具栏新增「返回首页」按钮 → `router.push('/')`。

### PR2 —— YAML/卡片范围开关：默认只看当前场景（item 1c）
纯前端，`WorkbenchView.vue`。
- 新增 `yamlScope = ref('scene')`（scene | full），在编辑器工具栏加切换。
- `syncYamlFromModel()`：scene 时仅 dump 当前场景对象；full 时维持整部。
- `onYamlInput()`：scene 时把解析出的单场景按 `id`/索引回写进 `data.value.scenes`（处理 id 变更并同步 `selScene`）；full 时维持现状。
- 切换 `yamlScope` / `selScene` / `viewMode` 时重新同步。

### PR3 —— 对话精修体验：回复精简 + 同步可靠（item 2）
前端 `WorkbenchView.vue` + 后端 `llm/PromptTemplates.java`。
- `refineSystem()` 明确要求 `reply` 仅 1–2 句说明「改了哪几处」，禁止复述剧本/寒暄（中英双语分支都改）。
- 核实并修复 `applyRefined()` 后展示同步：卡片随 `data.value` 重建；YAML 视图按 `yamlScope` 重新同步；`changed===false` 时给出「未发生改动」提示而非静默。

### PR4 —— 多线程历史对话（item 3）
前端为主，新增 Pinia store + 改 `WorkbenchView.vue`。
- 新增 `frontend/src/stores/chat.js`（仿 `stores/app.js`，便于单测）：按 `sessionId` 分桶，持久化 key `sf:chat:<sessionId>` = `{ activeThreadId, threads:[{id,title,messages,createdAt,updatedAt}] }`；actions：`loadThreads / newThread / switchThread / deleteThread / appendMessage / renameThread`（标题默认取首条用户消息截断；id 用 `crypto.randomUUID()`）。
- `WorkbenchView.vue`：对话区加历史线程列表（新建/选中/删除），切换线程即载入该线程消息；`seedChat()` 改为线程为空才注入开场白、不再每次 mount 清空；`sendChat()` 读写当前线程并持久化；**切线程只换消息，不动 `data.value`**。

### PR5 —— AI 质量评测（item 4）
后端新增端点/服务/Prompt + 前端面板。
- 后端：
  - `GenerationService` 加 `getOriginalText(sessionId)`（返回 `pending.get(id).text()`，缺失返回 null）。
  - `PromptTemplates` 新增 `evaluateSystem(language)` / `evaluateUser(screenplayJson, novelText)` + 标记；系统提示**强约束隔离**（「仅依据所给原著与剧本判断，不引入外部知识/其它上下文」），输出严格 JSON `{ "score":0-100, "assessment":"...", "suggestions":[...] }`。
  - 新增 `pipeline/QualityEvalStage.java`（仿 `RefineStage`）：用 `llm.complete(sys,user)` **单轮**调用（保证隔离）；解析 JSON 信封；**容错兜底**：解析失败退回基于规则版 `QualityReporter` 的分数 + 通用建议（保证 stub 环境测试稳定）。返回 `QualityEvalResult(score, assessment, suggestions)`。
  - 新增 `controller/EvaluationController.java`：`POST /api/evaluate/{sessionId}`，body `{ screenplay }`（沿用 ChatController 宽松 SNAKE_CASE mapper），按 `sessionId` 取原文；原文缺失返回明确错误。
- 前端：
  - `api/http.js` 加 `evaluateQuality({ sessionId, screenplay })`。
  - `WorkbenchView.vue`：质量面板加「AI 评测」区域，按钮触发、loading 态，展示评分 + 评价 + 建议列表（只读）。

## 关键文件

- `frontend/src/views/WorkbenchView.vue` —— PR1–PR5 前端核心
- `frontend/src/stores/chat.js`（新） —— PR4 历史线程
- `frontend/src/api/http.js` —— PR5 新增 `evaluateQuality`
- `backend/.../llm/PromptTemplates.java` —— PR3 精简、PR5 评测 prompt
- `backend/.../pipeline/QualityEvalStage.java`（新）、`controller/EvaluationController.java`（新）、`controller/GenerationService.java`（取原文）—— PR5
- 复用：`RefineStage`（服务模式参照）、`QualityReporter`（兜底分数）、`AutoRepair`、`ChatController` 宽松 mapper、`curScene/plainScreenplay/normalize`（前端）

## 修订（Round 2 · 评审反馈）

评审后对 PR1 / PR3 的收紧，均按 **TDD（先写失败测试，再实现）** 推进：

1. **PR3 — 对话回复不得包含元数据**：除「精简、只说改了哪里」外，AI 回复**禁止复述/罗列任何元数据**（标题、语言、generated_by、id、JSON 字段名、Schema 结构等），只用自然语言说明改动位置。
   - 在 `refineSystem`（中英双语）追加明确禁令；`PromptTemplatesTest` 断言提示含「不要…元数据/字段名/JSON」类约束（先红后绿）。
2. **PR3 — 改后必须同步到前端展示**：复核 `applyRefined` 重建 `data.value` → 卡片（`curScene` 计算属性）即时刷新、YAML 按 `yamlScope` 重新序列化；以组件测试断言「对话改动后卡片内容随之更新」。
3. **PR1 — 三栏滚动条未生效的根因修复**：根因是<strong>高度链断裂</strong> —— `App.vue` 的 `n-config-provider` 包裹层使 `.wb{height:100%}` 退化为内容高度，导致整页滚动、三栏不各自滚动。修复：`.wb` 改用视口高度（`height:100dvh`，回退 `100vh`），令三栏在内容超长时各自出现纵向滚动条。
   - 说明：纯 CSS 渲染/滚动无法在 happy-dom 单测可靠断言；该项以 **Playwright e2e**（真实浏览器，断言每栏 `scrollHeight>clientHeight` 且互不联动）+ `npm run build` + 人工目检验证，属 TDD 对「展示/配置」的合理例外。

## 验证

- **后端**：`JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" -q test` 全绿。新增 `QualityEvalStage` 单测（stub 走兜底）+ `EvaluationController` 集成测试（`@SpringBootTest(properties="scriptforge.llm.provider=stub")`，断言返回 score/assessment/suggestions，以及无原文的报错路径）。
- **前端**：`npm run test`（新 `stores/chat.js` 单测：建/切/删/持久化；WorkbenchView 的 YAML 范围开关、返回首页按钮、evaluateQuality mock）；`npm run build` 通过。
- **手动**：`java -jar target/novel-to-screenplay.jar` + `npm run dev`，走「上传/示例→生成→工作台」：①三栏可各自滚动、点返回首页回 `/`；②YAML 默认只显示当前场景、可切完整；③对话改剧本后卡片/YAML 同步、回复仅一两句；④多条历史对话可来回切、刷新不丢；⑤质量面板点「开始评测」得到评分+评价+建议。
- **安全**：真实 LLM 的 API Key 绝不写入仓库/报告；评测端点不回显任何密钥。
