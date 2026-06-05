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

    /**
     * 用户提示：拼接章节正文、已知角色圣经快照与输出格式要求。
     *
     * @param chapterIndex   章节序号
     * @param chapterTitle   章节标题
     * @param chapterContent 章节正文
     * @param bible          当前角色圣经快照（供跨章一致性：沿用既有角色 id / 名）
     * @param language       语言（影响提示语言）
     */
    public static String analyzeUser(int chapterIndex, String chapterTitle, String chapterContent,
                                     List<Character> bible, String language) {
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
                    + "\nChapter " + chapterIndex + " — " + chapterTitle + ":\n\"\"\"\n"
                    + chapterContent + "\n\"\"\"\n\n"
                    + "Split this chapter into scenes (by time + place + present characters), and for each scene "
                    + "list ordered beats. Use 'narration' for inner monologue / description, 'dialogue' for spoken "
                    + "lines (with speaker), 'action' for visible events. Output JSON in EXACTLY this shape:\n" + schema;
        }
        return "已知角色（同一人在不同章节请沿用同一名字）：\n"
                + (known.length() == 0 ? "（暂无）\n" : known)
                + "\n第 " + chapterIndex + " 章 —— " + chapterTitle + "：\n\"\"\"\n"
                + chapterContent + "\n\"\"\"\n\n"
                + "请把本章按「时间+地点+在场人物」切分为若干场景，每个场景给出有序节拍："
                + "心理/叙述用 narration，台词用 dialogue（含 speaker），可见事件用 action。"
                + "严格按以下形状输出 JSON：\n" + schema;
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
