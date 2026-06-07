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
                    + "The \"reply\" MUST be as short as possible: 1-2 sentences stating ONLY which places were "
                    + "changed (e.g. \"Made S2 more tense; added a V.O. line for C1.\"). No greetings, no apologies, "
                    + "no restating the screenplay, no extra explanation.";
        }
        return "你是剧本精修助手。给定「当前剧本」与「用户指令」，请返回修改后的<strong>完整剧本</strong>，"
                + "保留用户未提及的场景/角色，沿用相同结构（meta / characters / scenes / report）与 id 体系"
                + "（角色 C1.. 场景 S1.. 对白以角色 id 引用）。只输出严格合法的 JSON（不要 markdown、不要解释），"
                + "且必须是以下信封形状：\n"
                + "{ \"reply\": \"<改了哪里>\", \"screenplay\": { …完整剧本… } }\n"
                + "其中 reply 必须尽量简短：用 1～2 句话只说明改动了哪些地方"
                + "（例如「已把 S2 改得更紧张；给 C1 加了一句画外音。」），不要寒暄、不要道歉、"
                + "不要复述剧本、不要多余解释。";
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
