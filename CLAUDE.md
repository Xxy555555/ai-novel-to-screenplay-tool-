# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库工作时提供指引。

## 项目状态

**ScriptForge** —— 一款把小说（3 章以上）转换为结构化剧本（YAML）的 AI 工具。当前仓库是**可运行的框架骨架，业务逻辑尚未实现**。产品形态、架构与 UI 已在 `docs/` 中完整定义，填充功能时应以这些文档为准：

- `docs/PRD.md` —— 产品需求、三阶段管线、通用大模型适配器（6.4 节）、里程碑（第 8 节）。
- `docs/UI-Prototype-Design.md` —— 完整 UI 规格：设计令牌（第 2 节）、9 个屏线框图（第 6 节）、演示动线（第 9 节）。

## 常用命令

### 后端（`backend/`，Spring Boot 3 / Java 17 / Maven）

本机 `JAVA_HOME` 指向 `D:\JDK\jdk17\bin`（多了一层 `\bin`），会导致 Maven 报错。**每次调用 Maven 都要用 JDK 根目录覆盖它**，例如：

```bash
# 在 backend/ 下
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" -q -DskipTests compile
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" spring-boot:run   # 启动 :8080
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" -q test           # 全部测试
# 单个测试：
JAVA_HOME="D:/JDK/jdk17" "D:/Maven/apache-maven-3.9.9/bin/mvn" -Dtest=ScriptForgeApplicationTests#contextLoads test
```

启动后健康检查：`GET http://localhost:8080/api/health`。

### 前端（`frontend/`，Vue 3 + Vite）

```bash
# 在 frontend/ 下
npm install
npm run dev      # 启动 :5173，/api 反向代理到 :8080（见 vite.config.js）
npm run build    # 产物输出 dist/
```

## 编码

Windows 控制台为 **GBK**（Maven 日志里项目路径会显示成乱码，无害）。源码与运行全链路强制 **UTF-8**：`pom.xml` 的 compiler/surefire、`application.yml` 的 `server.servlet.encoding`、以及 `-Dfile.encoding=UTF-8`。新增构建/运行配置时务必保持这一点 —— 产品要端到端处理中文小说文本。

## 架构（目标设计，待实现）

转换被建模为**由渐进构建的「角色圣经」驱动的三阶段管线** —— 这是本设计对「3 章以上」难点（跨章角色一致性、场景切分、对白归属）的核心回答。`com.scriptforge` 下的后端分包已作为空层预建（每个包都有 `package-info.java` 说明职责）：

- `pipeline` —— `ChapterSplitter` → `AnalyzeStage` → `ComposeStage` → `ValidateStage` → `QualityReporter`，由 `PipelineOrchestrator` 编排。编排器在各分块间维护**`StoryState`（角色圣经）**并发送 SSE 进度事件。Analyze 抽取故事事实并更新角色圣经（别名/实体消解）；Compose 把事实转成剧本 YAML。
- `llm` —— `LlmClient` 接口背后是**一个通用适配器**。切换模型仅改配置（`application.yml` 的 `scriptforge.llm.*`，可用 `SCRIPTFORGE_LLM_*` 环境变量覆盖）：`provider` 取 `stub`（离线，默认 —— 无 Key 也能演示）、`openai`（任意 OpenAI 兼容端点：OpenAI/DeepSeek/Kimi/GLM/通义/Ollama/OpenRouter）或 `claude`（原生 Anthropic）。**不要硬编码任何厂商。**
- `schema` —— `SchemaValidator`（networknt json-schema-validator）针对 `resources/screenplay.schema.json` 校验，外加 `AutoRepair`：不合法的 LLM 输出回喂模型修复（有限重试，`max-repair-retries`），并有规则兜底。输出契约是**保证 Schema 合法的 YAML**。
- `model` —— 与 YAML 结构对应的 record。Jackson 全局配置为 `SNAKE_CASE` + `non_null`（`application.yml`），因此 Java 的 camelCase 字段会序列化为 Schema/前端期望的 snake_case 字段名。
- `export` —— `YamlExporter` 与 `FountainRenderer`（YAML → 工业风格可读剧本）。
- `controller` —— REST 接口 + 一个 **SSE** 流式端点，用于实时生成进度。

YAML Schema 是核心契约：顶层 `meta` / `characters` / `scenes` / `report`，其中每个场景持有一个**有序异构 `elements` 列表**（action / dialogue / voiceover / transition / montage）以忠实还原剧本「页面」，角色则是**以 id 引用的注册表**（单一事实源，防止名字漂移）。请**先**实现 Schema 及其设计原因文档（`docs/yaml-schema-design.md`），**再**做管线 —— 契约先行。

前端镜像该管线：三栏工作台（场景大纲 / 卡片或 YAML 双向同步编辑器 / 角色圣经 + 质量报告面板）。设计令牌在 `src/styles/tokens.css`；深色主题 + 琥珀强调色也在 `App.vue` 中注入 Naive UI。

## PR（Pull Request）提交规范

**新增功能必须基于 PR 提交。** 规则如下：

1. **每个 PR 只做一件事**：单个 PR 只实现或修改单一功能；鼓励尽可能小、粒度尽可能细的 PR；大功能应拆分为多个独立 PR 分步提交。
2. **标题与描述清晰完整**，每个 PR 至少包含以下四部分：
   - **标题**：一句话说明本 PR 新增/修改了什么。
   - **功能描述**：该功能的作用与使用方式。
   - **实现思路**：技术选型或核心实现逻辑的简要说明。
   - **测试方式**：如何验证该功能正常运行。
3. **合并后主分支始终可运行**：PR 合并到主分支后，代码必须保持可运行状态，评委在任意时间查看都应能复现演示效果。合并前需确保 `backend` 能编译启动、`frontend` 能 `npm run build` 通过。

PR 描述模板：

```markdown
### 标题
（一句话：新增/修改了什么）

### 功能描述
（作用、使用方式）

### 实现思路
（技术选型、核心实现逻辑）

### 测试方式
（复现步骤 / 验证命令 / 预期结果）
```

结合本项目「契约先行」的实现顺序，建议的 PR 拆分粒度示例：
`Schema + 设计文档` → `章节切分` → `通用 LLM 适配器(含 stub)` → `Analyze 阶段` → `角色圣经一致性` → `Compose 阶段` → `Schema 校验 + 自动修复` → `质量报告` → `SSE 编排 + REST 接口` → 前端各页面逐个 PR。每个都是一个可独立合并、且不破坏主分支可运行性的小步。
