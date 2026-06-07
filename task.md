# 任务清单 · 工作台增强 + AI 质量评测

> 配套文档：[`docs/workbench-enhancements-plan.md`](docs/workbench-enhancements-plan.md)
> 约定：每个 PR 只做一件事；合并后主分支保持可运行（后端可编译启动、前端 `npm run build` 通过）。
> 后端 Maven：`JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" ...`；集成测试强制 `provider=stub`。

## 进度总览

| # | PR | 范围 | 状态 |
| --- | --- | --- | --- |
| 0 | docs: 计划文档 + task.md | docs/、task.md | ✅ 已完成 |
| 1 | 三栏滚动条 + 返回首页按钮 | 前端 | ⬜ 待开始 |
| 2 | YAML/卡片范围开关 | 前端 | ⬜ 待开始 |
| 3 | 对话回复精简 + 同步可靠 | 前端 + 后端 | ⬜ 待开始 |
| 4 | 多线程历史对话 | 前端 | ⬜ 待开始 |
| 5 | AI 质量评测 | 前端 + 后端 | ⬜ 待开始 |

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
