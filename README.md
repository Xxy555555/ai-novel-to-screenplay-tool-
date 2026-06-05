# ScriptForge · AI 小说转剧本工具

把 3 章以上的小说，一键转换为**结构化、可校验、可视化编辑**的剧本初稿（YAML）。

> 当前阶段：**前后端框架骨架已就位，业务代码尚未实现。**

## 目录结构

```
.
├─ docs/
│  ├─ PRD.md                    产品需求文档（含通用大模型适配器设计）
│  └─ UI-Prototype-Design.md    前端原型设计文档（用于 Open Design）
├─ backend/                     Spring Boot 3 后端骨架（Java 17）
│  └─ src/main/java/com/scriptforge/
│     ├─ config/ controller/    框架配置 + 健康检查
│     └─ model/ llm/ pipeline/ schema/ export/   分层占位（package-info）
└─ frontend/                    Vue3 + Vite 前端骨架
   └─ src/  (api / router / stores / styles / views)
```

## 技术栈

- 前端：Vue3 + Vite + Pinia + Vue Router + Naive UI（深色影视工作台主题）
- 后端：Java 17 + Spring Boot 3 + Jackson YAML + networknt json-schema-validator
- AI：通用大模型适配器（OpenAI 兼容 / 原生 Claude / 离线 stub），详见 `docs/PRD.md` 6.4

## 本地运行

### 后端（端口 8080）
```bash
cd backend
# Windows 若 JAVA_HOME 未指向 JDK17 根目录，可临时指定：
#   set JAVA_HOME=D:\JDK\jdk17
mvn spring-boot:run
# 验证：GET http://localhost:8080/api/health
```

### 前端（端口 5173，已配 /api 代理到 8080）
```bash
cd frontend
npm install
npm run dev
# 打开 http://localhost:5173 ，首页会显示后端连通状态
```

## 协作规范

新增功能请基于 PR 提交，遵循以下要求（详见 `CLAUDE.md`「PR 提交规范」）：

- **每个 PR 只做一件事**：单一功能；鼓励小而细粒度；大功能拆成多个独立 PR 分步提交。
- **标题与描述清晰完整**：包含「标题 / 功能描述 / 实现思路 / 测试方式」四部分。
- **合并后主分支保持可运行**：评委在任意时间查看都应能复现演示效果。

## 下一步（业务实现路线见 docs/PRD.md 第 8 节里程碑）

1. 领域模型 + `screenplay.schema.json` + Schema 设计文档
2. 章节切分 + 通用 LLM 适配器 + 三阶段管线（先用 stub 跑通）
3. 角色圣经一致性 + 自动修复 + 质量报告
4. SSE 编排 + REST 接口
5. 前端各业务页面（上传 → 进度 → 工作台 → 预览 → 导出）
