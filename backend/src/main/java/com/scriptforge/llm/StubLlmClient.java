package com.scriptforge.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 离线规则桩 LLM（默认 provider）—— 无需任何 API Key 即可跑通完整管线，现场零依赖演示。
 *
 * <p>当前管线只用 LLM 做「理解层 Analyze」：输入一章正文，输出
 * {@link PromptTemplates} 顶部定义的「Analyze 事实 JSON」。本桩用确定性规则
 * （引号识别对白、关键词判内外景/时间、内置角色圣经提示）从原文抽取事实，
 * 不含任何随机性，保证每次运行结果一致、可复现。
 *
 * <p>对内置样本（《活着》同人 / The Gift）附带「角色圣经提示」，补全别名/定位/关系，
 * 从而让「跨章一致性」（如把「我/少爷/老爷」归并到福贵）在演示中清晰可见；
 * 对任意上传文本则退化为纯规则抽取。
 */
public class StubLlmClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public StubLlmClient(LlmProperties props) {
        this.props = props;
    }

    @Override
    public String describe() {
        String m = props == null || props.getModel() == null || props.getModel().isBlank()
                ? "scriptforge-stub-1" : props.getModel();
        return "stub/" + m;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String user = userPrompt == null ? "" : userPrompt;
        // 修复提示：桩不做智能修复，原样回吐【原始输出】部分。
        int origIdx = user.indexOf("【原始输出】");
        if (origIdx >= 0) {
            return user.substring(origIdx + "【原始输出】".length()).trim();
        }
        String chapter = extractChapter(user);
        try {
            return mapper.writeValueAsString(analyze(chapter));
        } catch (Exception e) {
            // 极端兜底：返回一个最小合法事实 JSON。
            return "{\"characters\":[],\"scenes\":[{\"int_ext\":\"INT\",\"location\":\"室内\","
                    + "\"time_of_day\":\"\",\"present\":[],\"source\":\"\",\"beats\":[]}]}";
        }
    }

    /** 从用户提示中截取三引号 {@code """} 之间的章节正文；截不到则退回全文。 */
    private static String extractChapter(String user) {
        int a = user.indexOf("\"\"\"");
        if (a >= 0) {
            int b = user.indexOf("\"\"\"", a + 3);
            if (b > a) {
                return user.substring(a + 3, b).trim();
            }
        }
        return user.trim();
    }

    // ───────────────────────── 角色圣经提示（仅内置样本） ─────────────────────────

    private record Hint(List<String> aliases, String role, String tone, String[][] relations) {}

    private static final Map<String, Hint> HINTS = new LinkedHashMap<>();
    static {
        HINTS.put("福贵", new Hint(List.of("我", "少爷", "老爷"), "主角", "朴实、自嘲",
                new String[][]{{"家珍", "妻"}, {"凤霞", "女"}, {"有庆", "子"}}));
        HINTS.put("家珍", new Hint(List.of("她", "女人"), "女主", "坚韧、隐忍",
                new String[][]{{"福贵", "夫"}}));
        HINTS.put("龙二", new Hint(List.of(), "反派", "精明、市侩", new String[][]{{"福贵", "赌友 / 对手"}}));
        HINTS.put("凤霞", new Hint(List.of(), "配角", "乖巧、沉默", new String[][]{{"福贵", "父"}}));
        HINTS.put("有庆", new Hint(List.of(), "配角", "天真、活泼", new String[][]{{"福贵", "父"}}));
        HINTS.put("Daniel", new Hint(List.of(), "protagonist", "earnest", new String[][]{}));
        HINTS.put("Clara", new Hint(List.of(), "supporting", "gentle", new String[][]{}));
        HINTS.put("Mr. Hale", new Hint(List.of(), "supporting", "stern", new String[][]{}));
    }

    // ───────────────────────── 规则抽取 ─────────────────────────

    private static final Pattern ZH_DIALOGUE = Pattern.compile("「([^」]*)」");
    private static final Pattern EN_DIALOGUE = Pattern.compile("\"([^\"]{2,})\"");
    // 中文说话人：名字(2-4汉字) + 可选修饰 + 说/道/问 等
    private static final Pattern ZH_SPEAKER = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})(?:冷冷|轻轻|缓缓|急急|低声|高声|笑着|叹|颤声)?(?:地|着)?(?:说|道|问|喊|叫|答|应|嚷)");
    private static final Pattern EN_SPEAKER = Pattern.compile(
            "([A-Z][a-zA-Z.]+(?:\\s[A-Z][a-z]+)?)\\s+(?:said|asked|replied|whispered|cried|shouted|murmured|answered)"
                    + "|(?:said|asked|replied|whispered|cried|shouted|murmured|answered)\\s+([A-Z][a-zA-Z.]+)");

    private boolean isChinese(String s) {
        return Pattern.compile("[\\u4e00-\\u9fa5]").matcher(s).find();
    }

    /** 抽取一章 → Analyze 事实 Map（characters + scenes，每章产出 1 个场景，含有序节拍）。 */
    private Map<String, Object> analyze(String chapter) {
        boolean zh = isChinese(chapter);
        List<String> lines = new ArrayList<>();
        for (String ln : chapter.split("\\r?\\n")) {
            String t = ln.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }

        // 1) 收集候选角色名：内置提示中出现在本章的 + 对白归属正则识别的。
        Set<String> names = new LinkedHashSet<>();
        for (String name : HINTS.keySet()) {
            if (chapter.contains(name)) {
                names.add(name);
            }
        }
        collectSpeakerNames(chapter, zh, names);

        // 2) 逐行产出节拍。
        List<Map<String, Object>> beats = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        String lastSpeaker = null;
        String protagonist = pickProtagonist(names);
        for (String line : lines) {
            Matcher dm = (zh ? ZH_DIALOGUE : EN_DIALOGUE).matcher(line);
            boolean hadDialogue = false;
            int searchFrom = 0;
            while (dm.find()) {
                hadDialogue = true;
                String text = dm.group(1).trim();
                if (text.isEmpty()) {
                    continue;
                }
                String speaker = speakerBefore(line, dm.start(), names);
                if (speaker == null) {
                    speaker = lastSpeaker;
                }
                if (speaker != null) {
                    lastSpeaker = speaker;
                    present.add(speaker);
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("kind", "dialogue");
                if (speaker != null) {
                    b.put("speaker", speaker);
                }
                b.put("text", text);
                beats.add(b);
                searchFrom = dm.end();
            }
            if (!hadDialogue) {
                String kind = isNarration(line, zh) && protagonist != null ? "narration" : "action";
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("kind", kind);
                if ("narration".equals(kind)) {
                    b.put("speaker", protagonist);
                    present.add(protagonist);
                }
                b.put("text", line);
                beats.add(b);
            }
        }
        // 把本章出现的提示角色也补进 present（即便没说话）。
        for (String n : names) {
            if (chapter.contains(n)) {
                present.add(n);
            }
        }

        // 3) 场景头：关键词判内外景/时间/地点。
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("int_ext", guessIntExt(chapter, zh));
        scene.put("location", guessLocation(chapter, zh));
        scene.put("time_of_day", guessTime(chapter, zh));
        scene.put("present", new ArrayList<>(present));
        scene.put("source", chapter.length() > 42 ? chapter.substring(0, 42).replaceAll("\\s+", "") + "…" : chapter);
        scene.put("beats", beats);

        // 4) 角色清单（仅本章出现者）。
        List<Map<String, Object>> characters = new ArrayList<>();
        for (String name : present) {
            characters.add(buildCharacter(name));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("characters", characters);
        root.put("scenes", List.of(scene));
        return root;
    }

    private Map<String, Object> buildCharacter(String name) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        Hint h = HINTS.get(name);
        if (h != null) {
            c.put("aliases", h.aliases());
            c.put("role", h.role());
            c.put("tone", h.tone());
            List<Map<String, String>> rels = new ArrayList<>();
            for (String[] r : h.relations()) {
                rels.add(Map.of("target", r[0], "relation", r[1]));
            }
            c.put("relations", rels);
        } else {
            c.put("role", "配角");
        }
        return c;
    }

    private void collectSpeakerNames(String chapter, boolean zh, Set<String> names) {
        Matcher m = (zh ? ZH_SPEAKER : EN_SPEAKER).matcher(chapter);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                String name = m.group(g);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
    }

    /** 在 quote 之前的文本里找最靠近 quote 的已知角色名作为说话人。 */
    private static String speakerBefore(String line, int quoteStart, Set<String> names) {
        String pre = line.substring(0, quoteStart);
        String best = null;
        int bestIdx = -1;
        for (String n : names) {
            int idx = pre.lastIndexOf(n);
            if (idx > bestIdx) {
                bestIdx = idx;
                best = n;
            }
        }
        return best;
    }

    private static String pickProtagonist(Set<String> names) {
        for (String n : names) {
            Hint h = HINTS.get(n);
            if (h != null && ("主角".equals(h.role()) || "protagonist".equals(h.role()))) {
                return n;
            }
        }
        return names.isEmpty() ? null : names.iterator().next();
    }

    private static boolean isNarration(String line, boolean zh) {
        if (zh) {
            return line.matches(".*(我|心里|心想|想着|记得|那是|后悔|觉得|仿佛|从此).*");
        }
        return line.matches(".*\\b(I|I'd|remembered|thought|felt|knew|wondered)\\b.*");
    }

    private static String guessIntExt(String text, boolean zh) {
        if (zh) {
            if (text.matches(".*(田|田埂|田间|地头|村口|街|路上|野|院子|门外|河边).*")
                    && !text.matches(".*(屋里|房里|堂屋|室内).*")) {
                return "EXT";
            }
            return "INT";
        }
        if (text.matches("(?s).*\\b(field|street|road|outside|garden|yard|river|hill)\\b.*")) {
            return "EXT";
        }
        return "INT";
    }

    private static String guessTime(String text, boolean zh) {
        if (zh) {
            if (text.matches(".*(夜|晚|夤夜|深夜|入夜|月).*")) {
                return "夜";
            }
            if (text.matches(".*(清晨|早晨|晨|白日|晌午|午后|日头|阳光).*")) {
                return "日";
            }
            return "";
        }
        if (text.matches("(?s).*\\b(night|midnight|evening|dusk)\\b.*")) {
            return "NIGHT";
        }
        if (text.matches("(?s).*\\b(morning|noon|afternoon|dawn|daylight)\\b.*")) {
            return "DAY";
        }
        return "";
    }

    private static final String[] ZH_LOCATIONS = {
            "赌坊", "赌场", "私塾", "堂屋", "走廊", "田埂", "田间", "客栈", "院子", "灶房", "门口", "屋里", "房里"};
    private static final String[] EN_LOCATIONS = {
            "kitchen", "hall", "field", "street", "room", "garden", "shop", "house", "yard", "doorway"};

    private static String guessLocation(String text, boolean zh) {
        String[] locs = zh ? ZH_LOCATIONS : EN_LOCATIONS;
        for (String l : locs) {
            if (text.contains(l)) {
                return zh ? (l.endsWith("里") ? l.substring(0, l.length() - 1) : l) : l;
            }
        }
        // schema 要求 location 非空：给一个确定性兜底。
        return zh ? "室内" : "interior";
    }
}
