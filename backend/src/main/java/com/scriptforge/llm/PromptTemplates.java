package com.scriptforge.llm;

import java.util.List;

import com.scriptforge.model.Character;

/**
 * 提示词模板 —— 管线各阶段与各 LLM 实现之间的<strong>提示/输出契约</strong>。
 *
 * <p>目前只有「理解层 Analyze」需要调用 LLM（生成层 Compose 为确定性装配，见
 * {@code ComposeStage}）。Analyze 要求模型把一章原文抽取成下述<strong>固定形状的 JSON</strong>，
 * 由 {@code AnalyzeStage} 解析、{@link StubLlmClient} 离线产出，二者必须与本类描述一致。
 *
 * <h2>Analyze 输出 JSON 契约</h2>
 * <pre>{@code
 * {
 *   "characters": [
 *     { "name": "福贵", "aliases": ["我","老爷"], "role": "主角", "tone": "朴实、自嘲",
 *       "relations": [ { "target": "家珍", "relation": "妻" } ] }
 *   ],
 *   "scenes": [
 *     { "int_ext": "INT", "location": "走廊", "time_of_day": "夜",
 *       "present": ["福贵","家珍"], "source": "原文片段…",
 *       "beats": [
 *         { "kind": "action",    "text": "福贵踉跄推开门，屋里一片漆黑。" },
 *         { "kind": "dialogue",  "speaker": "家珍", "text": "你又去赌了。", "emotion": "冷冷" },
 *         { "kind": "narration", "speaker": "福贵", "text": "那是我最后悔的一夜。" }
 *       ] }
 *   ]
 * }
 * }</pre>
 *
 * 约定：{@code present} 与 {@code speaker} 用<strong>角色名</strong>（非 id）；{@code AnalyzeStage}
 * 通过 {@code StoryState} 把名字消解为统一 id。{@code beats[].kind} ∈ {@code action / dialogue / narration}。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /** 系统提示：把模型定位为「只输出 JSON 事实」的剧本分析师。 */
    public static String analyzeSystem(String language) {
        boolean en = "en".equalsIgnoreCase(language);
        if (en) {
            return "You are a professional screenplay-adaptation analyst. "
                    + "Extract STRUCTURED FACTS from one novel chapter and output STRICT JSON ONLY "
                    + "(no markdown, no prose, no code fences). Do not invent facts not in the text.";
        }
        return "你是专业的剧本改编分析师。请从给定的一章小说中抽取「结构化故事事实」，"
                + "并且只输出严格合法的 JSON（不要 markdown、不要解释、不要代码围栏）。不要编造原文没有的事实。";
    }

    /** 兼容重载：不带用户需求的理解层提示。 */
    public static String analyzeUser(int chapterIndex, String chapterTitle, String chapterContent,
                                     List<Character> bible, String language) {
        return analyzeUser(chapterIndex, chapterTitle, chapterContent, bible, language, null);
    }

    /**
     * 用户提示：拼接章节正文、已知角色圣经快照、（可选）用户改编需求与输出格式要求。
     *
     * @param chapterIndex     章节序号
     * @param chapterTitle     章节标题
     * @param chapterContent   章节正文
     * @param bible            当前角色圣经快照（供跨章一致性：沿用既有角色 id / 名）
     * @param language         语言（影响提示语言）
     * @param userRequirements 用户上传时提出的改编需求（自由文本，可为空/{@code null}）
     */
    public static String analyzeUser(int chapterIndex, String chapterTitle, String chapterContent,
                                     List<Character> bible, String language, String userRequirements) {
        boolean en = "en".equalsIgnoreCase(language);
        StringBuilder known = new StringBuilder();
        if (bible != null && !bible.isEmpty()) {
            for (Character c : bible) {
                known.append("- ").append(c.name());
                if (!c.aliases().isEmpty()) {
                    known.append("（").append(String.join(" / ", c.aliases())).append("）");
                }
                known.append('\n');
            }
        }
        boolean hasReq = userRequirements != null && !userRequirements.isBlank();
        String reqBlock = !hasReq ? ""
                : (en ? "\nUser adaptation requirements (honor them while staying faithful to the text):\n"
                        + userRequirements.trim() + "\n"
                      : "\n用户改编需求（在忠于原文的前提下尽量满足）：\n" + userRequirements.trim() + "\n");
        String schema = """
                {
                  "characters": [ { "name": "", "aliases": [], "role": "", "tone": "",
                                    "relations": [ { "target": "", "relation": "" } ] } ],
                  "scenes": [ { "int_ext": "INT|EXT", "location": "", "time_of_day": "",
                                "present": [], "source": "",
                                "beats": [ { "kind": "action|dialogue|narration",
                                             "speaker": "", "text": "", "emotion": "" } ] } ]
                }""";
        if (en) {
            return "Known characters (reuse the same names for the same person across chapters):\n"
                    + (known.length() == 0 ? "(none yet)\n" : known)
                    + reqBlock
                    + "\nChapter " + chapterIndex + " — " + chapterTitle + ":\n\"\"\"\n"
                    + chapterContent + "\n\"\"\"\n\n"
                    + "Split this chapter into scenes (by time + place + present characters), and for each scene "
                    + "list ordered beats. Use 'narration' for inner monologue / description, 'dialogue' for spoken "
                    + "lines (with speaker), 'action' for visible events. Output JSON in EXACTLY this shape:\n" + schema;
        }
        return "已知角色（同一人在不同章节请沿用同一名字）：\n"
                + (known.length() == 0 ? "（暂无）\n" : known)
                + reqBlock
                + "\n第 " + chapterIndex + " 章 —— " + chapterTitle + "：\n\"\"\"\n"
                + chapterContent + "\n\"\"\"\n\n"
                + "请把本章按「时间+地点+在场人物」切分为若干场景，每个场景给出有序节拍："
                + "心理/叙述用 narration，台词用 dialogue（含 speaker），可见事件用 action。"
                + "严格按以下形状输出 JSON：\n" + schema;
    }

    // ───────────────────────── 对话精修（多轮） ─────────────────────────

    /** 标记：用户消息中「当前剧本 JSON」分隔符。stub 据此识别为精修请求并解析剧本。 */
    public static final String REFINE_SCREENPLAY_MARKER = "【当前剧本 JSON】";
    /** 标记：用户消息中「用户指令」分隔符。 */
    public static final String REFINE_INSTRUCTION_MARKER = "【用户指令】";

    /**
     * 对话精修系统提示：把模型定位为「按指令改写整本剧本并只回 JSON 信封」的编辑助手。
     * 输出契约：{@code {"reply": "对所做修改的简要说明", "screenplay": <完整剧本对象>}}。
     */
    public static String refineSystem(String language) {
        boolean en = "en".equalsIgnoreCase(language);
        if (en) {
            return "You are a screenplay editing assistant. Given the CURRENT screenplay and the user's "
                    + "instruction, return the FULL modified screenplay. Preserve scenes/characters the user "
                    + "did not mention. Keep the same JSON structure (meta / characters / scenes / report) and the "
                    + "same id scheme (characters C1.., scenes S1.., character references by id). Output STRICT JSON "
                    + "ONLY in this exact shape, no markdown, no prose:\n"
                    + "{ \"reply\": \"<what changed>\", \"screenplay\": { ...full screenplay... } }\n"
                    + "The \"reply\" MUST be as short as possible (1-2 sentences) and user-facing: describe ONLY what the user can "
                    + "see change on screen — the scene outline, the scene cards, or the character bible "
                    + "(e.g. \"Rewrote the dialogue in scene 2 to be punchier; added a voice-over for the lead.\"). "
                    + "NEVER mention internal field names, metadata or data structure (no meta, title, source_title, "
                    + "source, language, generated_by, JSON, schema, ids-as-fields) — the user does not understand those terms. "
                    + "If nothing user-visible changed, say so plainly. No greetings, apologies, or restating the screenplay.";
        }
        return "你是剧本精修助手。给定「当前剧本」与「用户指令」，请返回修改后的<strong>完整剧本</strong>，"
                + "保留用户未提及的场景/角色，沿用相同结构（meta / characters / scenes / report）与 id 体系"
                + "（角色 C1.. 场景 S1.. 对白以角色 id 引用）。只输出严格合法的 JSON（不要 markdown、不要解释），"
                + "且必须是以下信封形状：\n"
                + "{ \"reply\": \"<改了哪里>\", \"screenplay\": { …完整剧本… } }\n"
                + "其中 reply 必须简短（1～2 句）且<strong>面向用户</strong>：只描述用户在<strong>页面</strong>上能看到的变化"
                + "——即「场景大纲」「场景卡片」或「角色圣经」里的改动"
                + "（例如「把第 2 场的对白改得更利落了；给主角加了一句画外音」「把场景大纲里的地点和时间改成了中文」）。"
                + "<strong>绝不要提到任何元数据、内部字段名或数据结构</strong>"
                + "（不要出现 meta、title、source_title、source、language、generated_by、JSON、Schema、字段 等用户看不懂的术语）。"
                + "若本次没有改变用户在页面上能看到的内容，就直说「未改动可见内容」。不要寒暄、不要道歉、不要复述剧本。";
    }

    /**
     * 对话精修用户提示：内嵌当前剧本 JSON 与用户指令（用固定标记包裹，便于 stub 离线解析）。
     *
     * @param screenplayJson 当前剧本的 JSON 字符串（snake_case）
     * @param instruction    本轮用户指令
     */
    public static String refineUser(String screenplayJson, String instruction) {
        return REFINE_SCREENPLAY_MARKER + "\n" + screenplayJson + "\n\n"
                + REFINE_INSTRUCTION_MARKER + "\n" + (instruction == null ? "" : instruction.trim()) + "\n\n"
                + "请按系统要求只返回 JSON 信封：{ \"reply\": \"…\", \"screenplay\": { … } }";
    }

    // ───────────────────────── 改编质量评测（隔离上下文） ─────────────────────────

    /** 标记：评测用户消息中「原著小说」分隔符。 */
    public static final String EVAL_NOVEL_MARKER = "【原著小说原文】";
    /** 标记：评测用户消息中「改编后剧本」分隔符。 */
    public static final String EVAL_SCREENPLAY_MARKER = "【改编后剧本 JSON】";

    /**
     * 质量评测系统提示：把模型定位为「只依据所给原著与剧本、不引入外部上下文」的改编质量评审，
     * 只输出 {@code {"score","assessment","suggestions"}} JSON 信封。隔离设计 —— 不传入角色圣经、
     * 对话历史、用户需求等任何其它上下文，避免干扰判断。
     */
    public static String evaluateSystem(String language) {
        boolean en = "en".equalsIgnoreCase(language);
        if (en) {
            return "You are a screenplay-adaptation quality reviewer. You are given ONLY the original novel text "
                    + "and the adapted screenplay. Judge how well the screenplay adapts the novel: fidelity to plot "
                    + "and characters, dramatic effectiveness, dialogue quality, scene structure, and show-don't-tell. "
                    + "Base your judgement SOLELY on the two texts provided — do NOT use outside knowledge or any other "
                    + "context. Output STRICT JSON ONLY, no markdown, no prose, in this exact shape:\n"
                    + "{ \"score\": <0-100 integer>, \"assessment\": \"<2-4 sentence overall judgement>\", "
                    + "\"suggestions\": [\"<concrete, actionable suggestion>\", ...] }\n"
                    + "Give 3-6 specific suggestions tied to the screenplay.";
        }
        return "你是剧本改编质量评审。你只会拿到「原著小说原文」与「改编后剧本」两份材料。请评估剧本对原著的"
                + "改编质量：对情节与人物的忠实度、戏剧性、对白质量、场景结构、以及「展示而非陈述」。"
                + "判断必须<strong>只依据所给的这两份文本</strong>，不要使用外部知识或任何其它上下文。"
                + "只输出严格合法的 JSON（不要 markdown、不要解释），且必须是以下信封形状：\n"
                + "{ \"score\": <0-100 的整数>, \"assessment\": \"<2~4 句总体评价>\", "
                + "\"suggestions\": [\"<具体、可操作的修改建议>\", ...] }\n"
                + "请给出 3~6 条针对该剧本的具体建议。";
    }

    /**
     * 质量评测用户提示：仅内嵌原著小说与改编剧本（用固定标记包裹，便于离线/真实模型一致解析）。
     * 刻意不附带其它上下文，以隔离判断。
     *
     * @param screenplayJson 改编后剧本的 JSON 字符串（snake_case）
     * @param novelText      原著小说原文
     */
    public static String evaluateUser(String screenplayJson, String novelText) {
        return EVAL_NOVEL_MARKER + "\n" + (novelText == null ? "" : novelText.trim()) + "\n\n"
                + EVAL_SCREENPLAY_MARKER + "\n" + (screenplayJson == null ? "" : screenplayJson) + "\n\n"
                + "请按系统要求只返回 JSON 信封：{ \"score\": …, \"assessment\": \"…\", \"suggestions\": [ … ] }";
    }

    /**
     * 修复提示：把 Schema 校验错误连同不合法输出回喂模型，要求只返回修正后的 YAML/JSON。
     * 供 {@code AutoRepair} 使用。
     *
     * @param invalidOutput 不合法的原始输出
     * @param errors        Schema 校验错误信息（逐条）
     */
    public static String repairUser(String invalidOutput, String errors) {
        return "下面的剧本数据未通过 Schema 校验。请修正所有问题后，只输出修正后的合法 JSON（不要解释）。\n\n"
                + "【校验错误】\n" + errors + "\n\n【原始输出】\n" + invalidOutput;
    }
}
