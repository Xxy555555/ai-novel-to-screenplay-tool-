# YAML Schema 设计文档 ——「ScriptForge」剧本数据结构

> 版本：v1.0 ｜ 日期：2026-06-05 ｜ 状态：契约先行（实现前定稿）
> 配套：`backend/src/main/resources/screenplay.schema.json`（机器可校验契约）、
> `com.scriptforge.model.*`（Java 记录类型，与本文档逐字段对应）、
> `docs/PRD.md` 6.3 节、`docs/UI-Prototype-Design.md`（前端按本结构渲染）。

---

## 0. 为什么先定 Schema（契约先行）

「小说 → 剧本」的本质是一次**结构化重构**（PRD 1.2）。如果先写管线、后定数据结构，
LLM 的自由文本输出会让每一层各说各话、无法校验、无法可视化编辑。因此本项目把
**「保证 Schema 合法的 YAML」**作为唯一输出契约，先于一切业务逻辑定稿：

- 管线三阶段（理解 / 生成 / 质检）围绕它协作；
- 校验层（networknt json-schema-validator）以它为判据，不合法即回喂 LLM 自动修复；
- 前端三栏工作台（卡片 / YAML / 角色面板 / 质量报告）直接绑定它，卡片 ⇄ YAML 双向同步；
- 导出层（YAML / Fountain）由它渲染。

> **一致性纪律**：`screenplay.schema.json`、`com.scriptforge.model` 记录、本文档、
> 以及 `design/` 前端原型里的 `toYaml()/fromYaml()` 四者必须保持一致。改任意一处都要同步其余三处。

---

## 1. 顶层结构

```yaml
meta:        { ... }   # 作品元信息
characters:  [ ... ]   # 角色注册表（单一事实源，以 id 引用）
scenes:      [ ... ]   # 场景序列（每场持有有序异构元素流）
report:      { ... }   # 改编质量报告
```

对应 `Screenplay(meta, characters, scenes, report)`。

| 字段 | 类型 | 必填 | 设计原因 |
|------|------|:--:|------|
| `meta` | object | ✓ | 标题/原著/语言/生成模型等，供预览页眉与溯源 |
| `characters` | array | ✓ | **跨章一致性的落地**：所有角色集中登记，场景只引用 id |
| `scenes` | array | ✓ | 剧本主体，按播放顺序排列 |
| `report` | object | ✗ | 质量评分与指标；生成中途可缺省，质检阶段补齐 |

**序列化约定**：Jackson 全局 `PropertyNamingStrategy.SNAKE_CASE` + `default-property-inclusion: non_empty`。
因此 Java 的 camelCase 字段（如 `intExt`、`presentCharacters`）序列化为 snake_case（`int_ext`、`present_characters`），
且空集合 / 空串自动省略——领域模型可放心把集合字段默认成空集合以杜绝空指针，输出 YAML 仍保持干净。

---

## 2. `meta` —— 作品元信息

```yaml
meta:
  title: 《活着》改编
  source_title: 改编自 余华《活着》
  author: 余华
  language: zh
  generated_by: stub/scriptforge-stub-1
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `title` | string | ✓ | 改编后剧本标题 |
| `source_title` | string | ✗ | 原著信息 |
| `author` | string | ✗ | 原著作者 |
| `language` | string | ✗ | `zh` / `en`，驱动提示词与渲染细节 |
| `generated_by` | string | ✗ | provider/model 标识，便于复现与溯源；stub 模式为占位值 |

对应 `Meta`。只有 `title` 必填，保证最小可用。

---

## 3. `characters` —— 角色注册表（★跨章一致性核心）

```yaml
characters:
  - id: C01
    name: 福贵
    role: 主角
    aliases: [我, 老爷, 福贵]
    tone: 朴实、自嘲
    relations:
      - { target: 家珍, relation: 妻 }
      - { target: 凤霞, relation: 女 }
    first_appearance: 第1章
```

| 字段 | 类型 | 必填 | 约束 / 说明 |
|------|------|:--:|------|
| `id` | string | ✓ | 正则 `^C[0-9]+$`（如 `C01`）。**全篇唯一标识** |
| `name` | string | ✓ | 规范化主名 |
| `role` | string | ✗ | 自由文本（中英皆可），如「主角」「反派」 |
| `aliases` | string[] | ✗ | 别名/代称，**实体消解的产物**（「老爷」「我」归并到福贵） |
| `tone` | string | ✗ | 口吻/性格基调，回填给后续章节维持口吻一致 |
| `relations` | Relation[] | ✗ | 每项 `{target, relation}` 均必填 |
| `first_appearance` | string | ✗ | 首次登场章节 |

**设计原因**：题目难点之一是「同一角色在不同章节称谓不同，分块处理易被认成不同人」（PRD 1.2）。
对策是把角色做成**以 id 引用的注册表**：场景的 `present_characters` 与对白元素的 `character`
一律存 id 而非名字。名字怎么变，引用不漂移。运行期由 `StoryState`（`com.scriptforge.model.StoryState`）
做渐进消解：命中已知称谓即复用 id，否则登记新角色并分配 `Cxx`；显式别名归并用 `recordAlias/merge`。

> 「无悬空引用」是质量指标（角色一致性 ≥95%）：`present_characters` 与 `character` 引用的 id
> 必须能在 `characters` 中找到。该约束是跨数组引用，JSON Schema 不便表达，由质检层（`QualityReporter`）校验。

---

## 4. `scenes` —— 场景序列

```yaml
scenes:
  - id: S2
    chapter: 第2章
    heading: { int_ext: INT, location: 走廊, time_of_day: 夜 }
    present_characters: [C01, C02]
    elements:
      - { type: action, text: 福贵踉跄推开门，屋里一片漆黑。 }
      - { type: dialogue, character: C02, parenthetical: 冷冷, line: 你又去赌了。 }
      - { type: dialogue, character: C01, parenthetical: 心虚, line: 就最后一次。 }
      - { type: voiceover, character: C01, line: 那是我最后悔的一夜。 }
    mood: 压抑
    pacing: 缓
    shots: [推镜·门, 中景]
    source: "福贵深一脚浅一脚地回到家，推开门，屋里一片漆黑…（第2章·段14）"
```

对应 `Scene`。

| 字段 | 类型 | 必填 | 约束 / 说明 |
|------|------|:--:|------|
| `id` | string | ✓ | 正则 `^S[0-9]+$`（如 `S2`） |
| `chapter` | string | ✗ | 来源章节，供卡片显示与溯源 |
| `heading` | object | ✓ | 见 4.1 |
| `present_characters` | string[] | ✗ | 元素均为 `^C[0-9]+$` |
| `elements` | Element[] | ✓ | 见 4.2，**有序异构** |
| `mood` | string | ✗ | 标注层：情绪基调 |
| `pacing` | string | ✗ | 标注层：节奏 |
| `shots` | string[] | ✗ | 标注层：分镜/机位建议（自由短语） |
| `source` | string | ✗ | 原文溯源片段，建立可信、支持局部重生成 |

> **设计取舍**：`mood/pacing/shots` 是差异化亮点（P4b 标注层，让初稿「有导演感」），
> 但属可选层，缺失不影响 Schema 合法——避免「范围过大拖垮完成度」（PRD 风险表）。

### 4.1 `heading` —— 场景头三要素

| 字段 | 类型 | 必填 | 约束 |
|------|------|:--:|------|
| `int_ext` | string | ✓ | 枚举 `INT` / `EXT` |
| `location` | string | ✓ | 地点，非空 |
| `time_of_day` | string | ✗ | 时间 |

**关键设计**：只强制 `int_ext` + `location`，**`time_of_day` 故意可选**。
因为「场景头完整率」是质量指标（目标 ≥90%），缺时间应表现为**质量警告**（前端 S4「⚠缺时间」可定位修补），
而**不是 Schema 非法**。若把 time 设为必填，会把一个「待人工补」的软问题升级成硬错误，
与 PRD 的「可量化、可定位」体验冲突。这正是 Schema 校验与质量评分分工的体现。

### 4.2 `elements[]` —— 有序异构元素流

剧本「页面」由一串有序、可混合类型的元素构成，对应 `Element`。用单一对象凭 `type` 区分五类，
`additionalProperties: false` 防止字段拼写错误漏网（利于自动修复定位）：

| `type` | 用到的字段 | 含义 |
|--------|-----------|------|
| `action` | `text` | 动作行（可拍摄的画面） |
| `montage` | `text` | 蒙太奇 |
| `transition` | `text` | 转场（如 `CUT TO:`） |
| `dialogue` | `character` + `line`（可选 `parenthetical`） | 对白 |
| `voiceover` | `character` + `line`（可选 `parenthetical`） | 画外音 V.O.（心理描写转写） |

Schema 用 `if/then` 表达条件必填：`dialogue`/`voiceover` 必须有 `character` 且 `line`；
`action`/`montage`/`transition` 必须有 `text`。

| 字段 | 类型 | 约束 |
|------|------|------|
| `type` | string | 枚举上述五种，必填 |
| `text` | string | 叙述性文本 |
| `character` | string | `^C[0-9]+$`，说话人 id |
| `line` | string | 台词 |
| `parenthetical` | string | 情绪/动作提示，如「冷冷」 |

> **为什么不用多态（每类一个子类型）**：扁平单对象 + `non_empty` 省略空字段，
> 既贴合前端原型的 flow-map 写法、便于 YAML 人手编辑与 diff，又让条件校验集中可读。

---

## 5. `report` —— 改编质量报告

```yaml
report:
  score: 87
  grade: 良好
  schema_valid: true
  schema_error_count: 0
  dialogue_attribution_rate: 0.92
  character_consistency_rate: 0.95
  scene_heading_completeness_rate: 0.90
  show_vs_tell_ratio: 0.8
  scene_count: 12
  character_count: 5
  avg_elements_per_scene: 8.0
  issues:
    - { scene_id: S4, message: 场景缺少时间, severity: warning }
```

对应 `QualityReport` + `Issue`。比率均为 0~1 小数（前端按百分比展示），`score` 为 0~100 整数。

| 字段 | 类型 | 对应 PRD 指标 |
|------|------|------|
| `score` / `grade` | integer / string | 综合评分与评级（≥80 绿 / 60–79 琥珀 / <60 红） |
| `schema_valid` / `schema_error_count` | boolean / integer | Schema 合法率（修复后应 100% 合法、0 错误） |
| `dialogue_attribution_rate` | number | 对白说话人归属覆盖率（≥0.90） |
| `character_consistency_rate` | number | 角色一致性 / 无悬空引用（≥0.95） |
| `scene_heading_completeness_rate` | number | 场景头三要素完整率（≥0.90） |
| `show_vs_tell_ratio` | number | 「演 / 说」比，体现影像化程度 |
| `scene_count` / `character_count` / `avg_elements_per_scene` | — | 结构统计 |
| `issues[]` | Issue[] | 待改进项；`scene_id` 供前端「定位」跳转 |

`Issue`：`{ scene_id?, message(必填), severity ∈ {error, warning, info} }`。

---

## 6. 管线内部数据契约（不进入输出 YAML）

为支撑「理解 → 生成」两阶段分离，模型层还定义了若干**中间类型**（不序列化进最终 YAML）：

| 类型 | 产出阶段 | 作用 |
|------|---------|------|
| `Chapter(index, title, content)` | 章节切分 | 一章原文 |
| `Beat(kind, speaker, text, emotion)` | 理解 | 最小故事节拍（action/dialogue/narration） |
| `SceneFacts(int_ext, location, time_of_day, present_characters, beats, source)` | 理解 | 一场戏的客观事实（未转写为剧本） |
| `ChapterFacts(chapter_index, scenes)` | 理解 | 单章抽取结果 |
| `StoryState` | 理解（贯穿） | 角色圣经可变状态：消解、登记、合并 |

**两阶段为何分离**：理解层只抽「事实」（谁、在哪、做了什么、说了什么），不做格式化；
生成层（Compose）才把事实转写为剧本——叙述/心理 → 画外音、补场景编号与转场、加标注层。
分离让每步可解释、可展示中间产物、出错可定位，也便于自动修复（PRD 9 风险对策）。

---

## 7. 校验与自动修复（契约如何被守护）

1. **校验**：用 `screenplay.schema.json` 对 LLM 产出的 YAML（按 JSON 校验）做结构校验。
2. **自动修复**：不合法时，把校验错误连同原输出回喂 LLM 限次修复（`scriptforge.llm.max-repair-retries`）；
   仍失败则走规则兜底（补必填、丢弃非法元素、给悬空引用兜底等），确保**最终 100% Schema 合法**。
3. **跨引用校验**：Schema 管不到的「id 必须存在」「对白必须有归属」由质检层度量并计入质量分。

> 最终对外承诺：**保证 Schema 合法的 YAML**——可机器校验、可人手编辑、可 git diff。
