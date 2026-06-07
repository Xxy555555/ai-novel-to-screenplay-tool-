# 任务清单 · 工作台增强 + AI 质量评测

> 配套文档：[`docs/workbench-enhancements-plan.md`](docs/workbench-enhancements-plan.md)
> 约定：每个 PR 只做一件事；合并后主分支保持可运行（后端可编译启动、前端 `npm run build` 通过）。
> 后端 Maven：`JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" ...`；集成测试强制 `provider=stub`。

## 进度总览

| # | PR | 范围 | 状态 |
| --- | --- | --- | --- |
| 0 | docs: 计划文档 + task.md | docs/、task.md | ✅ 已完成 |
| 1 | 三栏滚动条 + 返回首页按钮 | 前端 | ✅ 已实现（滚动条根因修复见「修订」） |
| 2 | YAML/卡片范围开关 | 前端 | ✅ 已实现 |
| 3 | 对话回复精简 + 同步可靠 | 前端 + 后端 | ✅ 已实现（元数据禁令见「修订」） |
| 4 | 多线程历史对话 | 前端 | ✅ 已实现 |
| 5 | AI 质量评测 | 前端 + 后端 | ✅ 已实现 |

## 修订（Round 2 · 评审反馈，严格 TDD）

| # | 修订项 | 分支 | 状态 |
| --- | --- | --- | --- |
| R1 | PR3：AI 回复禁止复述元数据（标题/语言/id/JSON 字段名等），只说改动位置 | `feat/chat-03-concise-sync` | ✅ 已完成（TDD 先红后绿） |
| R2 | PR3：复核并断言「对话改动后卡片即时同步」 | `feat/chat-03-concise-sync` | ✅ 已覆盖（既有用例：卡片情绪随对话更新） |
| R3 | PR1：修复高度链断裂（`.wb` 改 `100dvh`），令三栏真正各自出现滚动条 | `feat/ui-01-scroll-home` | ✅ 已完成（Playwright e2e 先红后绿） |
| R4 | PR3：真实模型鲁棒性 —— 回复不漏 Schema（`sanitizeReply`）+ 防截断（max-tokens 8192）+ 解析失败友好兜底，确保改动同步 | `feat/chat-03-concise-sync` | ✅ 已完成（TDD 先红后绿 + 真实模型实测） |
| R5 | PR3：`OpenAiCompatibleClient` 容忍上游 `application/octet-stream` 响应（改按 byte[] 取响应再 UTF-8 解码） | `feat/chat-03-concise-sync` | ✅ 已完成（TDD 先红后绿 + 真实模型实测） |
| R6 | PR3：全局改写类指令仍偶发「未能解析」——解析失败自动重试一次（追加纠正指令）+ `max-tokens` 8192→16384 防截断 | `feat/chat-03-concise-sync` | ✅ 已完成（TDD 先红后绿 + 真实模型实测） |
| R7 | PR3：**大剧本**（长篇小说生成）对话仍失败——LLM 调用瞬时错误自动重试 + 对话超时 180s/300s | `feat/chat-03-concise-sync` | ✅ 已完成（TDD 先红后绿 + 真实模型实测 3/3） |

> R1 走标准 TDD（PromptTemplatesTest 先加「元数据/metadata」禁令断言→红→补提示→绿）；R2 由既有组件用例覆盖（对话后 `.atag.mood` 更新）；R3 为纯 CSS，单测无法可靠断言（已实测 happy-dom 不计算 scoped 样式），以 `e2e/tests/scroll-and-home.spec.js` 真实浏览器先红（整页 scrollHeight 1051 > 视口 722）后绿验证。
>
> R4 复盘：评审在**真实 LLM**（agnes-2.0-flash）下仍见「回复含 Schema、改动不同步」。根因——模型未严格回吐 `{reply,screenplay}` 信封 / 输出被 4096 token 截断 → `RefineStage` 解析失败后把原始输出（含 Schema 转储）当回复返回、且剧本未变。修复：`RefineStage.sanitizeReply`（去围栏/截断到首个 `{` 前/限长）用于成功与失败两条路径，解析失败回友好提示而非原始输出；`max-tokens` 4096→8192（可经 `SCRIPTFORGE_LLM_MAX_TOKENS` 覆盖）防截断。TDD：RefineStageTest 先加「截断/含 Schema 输出不得进回复」「reply 内嵌 JSON 须剔除」两条失败用例→红→补 `sanitizeReply`→绿；并加 markdown 围栏信封回归用例。最后用真实模型实测：`把标题改为《群山回唱》` → 回复「已将剧本标题修改为《群山回唱》。」（16 字、无 Schema/围栏）、`changed=true`、标题已更新、可同步。
>
> R5 复盘：评审又见 `对话精修调用失败：…Error while extracting response for type [java.lang.String] and content type [application/octet-stream]`。根因——agnes 网关偶发把 JSON 响应头误标为 `application/octet-stream`，`OpenAiCompatibleClient` 用 `.body(String.class)` 找不到转换器即抛错、整个对话失败。修复：改 `.body(byte[].class)` 取原始字节再 UTF-8 解码（绕开 content-type 匹配）+ 显式 `Accept: application/json`；对正常 JSON 响应行为不变。TDD：`OpenAiCompatibleClientTest` 用 `MockRestServiceServer` 返回 octet-stream 的 JSON 体 → 先红（复现线上报错）→ 改 byte[] 后绿。真实模型实测：连发对话不再报该错，`给 S1 加一句画外音` → HTTP 200、回复 18 字无 Schema、`changed/valid=true`、新元素已进入同步后的剧本。
>
> R6 复盘：评审把「质量评测的修改建议」整段作为指令做**全局改写**，仍偶发 `未能解析本次返回的剧本改动`。真实模型诊断：8 场景改写 `finish_reason=stop`、约 7.6k 字符可正常解析（即当前构建已能处理），失败主因是 (1) 模型偶发不按 `{reply,screenplay}` 信封返回（散文/markdown），(2) 更大剧本输出被 token 上限截断。修复：`RefineStage` 抽出 `parseEnvelope`，首次解析不到剧本时**追加「只输出严格 JSON 信封」纠正指令自动重试一次**；`max-tokens` 8192→16384（已实测 agnes 接受、`finish_reason=stop`）。TDD：RefineStageTest 加「首次回散文、纠正后回合法信封→应重试并成功且恰好两次调用」用例→红→实现重试后绿。真实模型实测：8 场景全局改写 → HTTP 200、`changed/valid=true`、回复 59 字无 Schema、8 场景齐全。
>
> R7 复盘（上传长篇小说《重生都市至尊》后对话失败）：先确诊——真实跑通「生成→对话」，量得剧本 **10 场景 / 16k 字符**；整本改写 `in=16476 / out=22295` 字符、`finish_reason=stop`（**未截断**，16384 足够）、单次耗时 **~79–110s**。失败根因是 agnes 网关在这种「大且慢」响应上**偶发瞬时错误**（5xx / 提取失败 octet-stream），而客户端**之前不重试**（仅 RefineStage 在解析失败时重试，HTTP 异常直接放弃）。修复：`OpenAiCompatibleClient` 加瞬时错误重试（2 次尝试，覆盖生成与对话）；超时 `timeout-seconds` 120→180、前端 chat 超时 180→300s。TDD：`OpenAiCompatibleClientTest` 用 `MockRestServiceServer`「首次 502、二次成功」→ 先红→加重试后绿。真实模型实测：同一大剧本连发 **3 次全局改写均成功**（HTTP 200、`changed/valid=true`、回复 55–63 字无 Schema、耗时 93–110s）。
>
> 备注（长期方向）：~100s 的对话等待偏久，根因是「每轮回吐整本剧本」对大剧本天然昂贵。后续可考虑**按场景增量编辑**（仅回吐改动场景、服务端合并）从架构上消除大输出，留作独立优化。

---

## PR1 —— 三栏滚动条 + 返回首页按钮
分支：`feat/ui-01-scroll-home`
- [ ] 为滚动容器补可见暗色滚动条样式（`.pane` / `.char-list` / `.chat-msgs` / `.tabpane.qual` / `.script-page`）
- [ ] 统一右栏滚动：角色 / 质量 / 对话三个面板均可独立滚动到底
- [ ] 顶部工具栏新增「返回首页」按钮 → `router.push('/')`
- [ ] 验收：内容超长时三栏各自出现滚动条；点按钮回到 `/`；`npm run build` 通过

## PR2 —— YAML/卡片范围开关（默认当前场景）
分支：`feat/ui-02-yaml-scope`
- [ ] 新增 `yamlScope = ref('scene')` 与工具栏「当前场景 / 完整剧本」开关
- [ ] `syncYamlFromModel()`：scene 仅 dump 当前场景；full 维持整部
- [ ] `onYamlInput()`：scene 时按 id/索引回写单场景（处理 id 变更、同步 `selScene`）；full 维持现状
- [ ] 切换 `yamlScope` / `selScene` / `viewMode` 时重新同步
- [ ] 验收：默认 YAML 只显示当前场景，编辑后回写正确；切完整剧本可整体编辑

## PR3 —— 对话回复精简 + 同步可靠
分支：`feat/chat-03-concise-sync`
- [ ] 后端 `PromptTemplates.refineSystem()`：`reply` 限 1–2 句、只说改动、禁复述（中英双语）
- [ ] 前端核实/修复 `applyRefined()` 后卡片与 YAML 同步（YAML 按 `yamlScope` 重新同步）
- [ ] `changed===false` 时提示「未发生改动」而非静默
- [ ] 验收：对话改剧本后视图即时更新；回复简短；后端 `mvn -q test` 全绿

## PR4 —— 多线程历史对话
分支：`feat/chat-04-history`
- [ ] 新增 `frontend/src/stores/chat.js`：按 `sessionId` 分桶持久化（key `sf:chat:<sessionId>`），actions `loadThreads/newThread/switchThread/deleteThread/appendMessage/renameThread`
- [ ] `WorkbenchView.vue`：历史线程列表（新建/选中/删除）+ 切换载入消息
- [ ] `seedChat()` 改为线程为空才注入开场白；`sendChat()` 读写当前线程并持久化
- [ ] 所有线程共用当前剧本（切线程不动 `data.value`）
- [ ] 单测：`stores/chat.js` 建/切/删/持久化到 localStorage
- [ ] 验收：多条历史可来回切、刷新/重进不丢；`npm run test` + `build` 通过

## PR5 —— AI 质量评测
分支：`feat/eval-05-quality`
- [ ] 后端 `GenerationService.getOriginalText(sessionId)`
- [ ] 后端 `PromptTemplates.evaluateSystem/evaluateUser` + 隔离约束 + JSON 信封 `{score,assessment,suggestions}`
- [ ] 后端 `pipeline/QualityEvalStage`（单轮 `complete()` 隔离调用 + 解析失败兜底到规则版分数）
- [ ] 后端 `controller/EvaluationController`：`POST /api/evaluate/{sessionId}`（取原文，缺失报错）
- [ ] 前端 `api/http.js` 加 `evaluateQuality`；质量面板加「开始评测」区域（评分+评价+建议，只读）
- [ ] 测试：`QualityEvalStage` 单测（stub 走兜底）+ `EvaluationController` 集成测试（`provider=stub`，含无原文报错路径）
- [ ] 验收：点「开始评测」得到评分+评价+建议；密钥不入库/不回显；前后端测试全绿

---

## 收尾
- [ ] 各 PR 推送远端并合并（用户在 GitHub 操作），合并后同步本地 main
- [ ] 主分支端到端复跑：后端 `java -jar target/novel-to-screenplay.jar` + 前端 `npm run dev` 走完整动线
