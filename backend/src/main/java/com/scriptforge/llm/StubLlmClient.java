package com.scriptforge.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /**
     * 多轮对话精修（离线确定性）。识别 {@link PromptTemplates#REFINE_SCREENPLAY_MARKER} 后，
     * 解析内嵌剧本 JSON 并按用户指令做<strong>规则化改写</strong>，返回
     * {@code {"reply": …, "screenplay": …}} 信封；非精修对话退化为单轮 {@link #complete}。
     *
     * <p>支持的离线指令（中英关键词）：调情绪/节奏（紧张/轻松/压抑…）、加画外音(V.O.)、
     * 加分镜、改标题、删除场景、新增场景。无法识别时只回文字、不改剧本（changed=false）。
     */
    @Override
    public String chat(String systemPrompt, List<LlmClient.ChatMessage> messages) {
        String last = "";
        if (messages != null) {
            for (LlmClient.ChatMessage m : messages) {
                if (m != null && m.content() != null) {
                    last = m.content();
                }
            }
        }
        if (!last.contains(PromptTemplates.REFINE_SCREENPLAY_MARKER)) {
            return LlmClient.super.chat(systemPrompt, messages); // 非精修：走默认扁平化 → complete
        }
        try {
            return refine(last);
        } catch (Exception e) {
            // 兜底：解析/改写失败时只回文字，不动剧本。
            return envelope("离线助手暂时无法解析该指令，剧本未改动。", null);
        }
    }

    /** 解析精修用户消息，按指令改写剧本并产出信封 JSON。 */
    private String refine(String user) throws Exception {
        String json = between(user, PromptTemplates.REFINE_SCREENPLAY_MARKER, PromptTemplates.REFINE_INSTRUCTION_MARKER);
        String instruction = afterMarker(user, PromptTemplates.REFINE_INSTRUCTION_MARKER);
        int stop = instruction.indexOf("请按系统要求");
        if (stop >= 0) {
            instruction = instruction.substring(0, stop);
        }
        instruction = instruction.trim();

        JsonNode rootNode = mapper.readTree(json.trim());
        if (!rootNode.isObject()) {
            return envelope("未能解析当前剧本，剧本未改动。", null);
        }
        ObjectNode root = (ObjectNode) rootNode;
        ArrayNode scenes = root.has("scenes") && root.get("scenes").isArray()
                ? (ArrayNode) root.get("scenes") : root.putArray("scenes");

        String lower = instruction.toLowerCase();
        String targetId = targetSceneId(instruction);
        List<String> notes = new ArrayList<>();

        // 1) 情绪 / 节奏
        String[] moodPacing = moodPacingFor(instruction, lower);
        if (moodPacing != null) {
            List<ObjectNode> targets = scenesToEdit(scenes, targetId);
            for (ObjectNode sc : targets) {
                sc.put("mood", moodPacing[0]);
                sc.put("pacing", moodPacing[1]);
            }
            if (!targets.isEmpty()) {
                notes.add("已把" + scope(targetId, targets.size()) + "的情绪调为「" + moodPacing[0]
                        + "」、节奏调为「" + moodPacing[1] + "」");
            }
        }

        // 2) 画外音 V.O.
        if (containsAny(instruction, lower, "画外音", "旁白", "内心", "独白", "voiceover", "voice-over", "v.o", "monologue")) {
            ObjectNode sc = targetId != null ? findScene(scenes, targetId) : firstScene(scenes);
            if (sc != null) {
                ArrayNode els = sc.has("elements") && sc.get("elements").isArray()
                        ? (ArrayNode) sc.get("elements") : sc.putArray("elements");
                ObjectNode vo = mapper.createObjectNode();
                vo.put("type", "voiceover");
                String cid = pickCharacterId(sc, root);
                if (cid != null) {
                    vo.put("character", cid);
                }
                vo.put("line", "（内心独白）此刻，他的心绪久久不能平静。");
                els.add(vo);
                notes.add("已为 " + sc.path("id").asText("该场景") + " 添加一条画外音（V.O.）");
            }
        }

        // 3) 分镜 / 镜头
        if (containsAny(instruction, lower, "分镜", "镜头", "机位", "特写", "shot", "close-up", "closeup")) {
            ObjectNode sc = targetId != null ? findScene(scenes, targetId) : firstScene(scenes);
            if (sc != null) {
                ArrayNode shots = sc.has("shots") && sc.get("shots").isArray()
                        ? (ArrayNode) sc.get("shots") : sc.putArray("shots");
                String shot = containsAny(instruction, lower, "特写", "close") ? "特写"
                        : containsAny(instruction, lower, "远景", "wide") ? "大远景" : "中景";
                boolean exists = false;
                for (JsonNode s : shots) {
                    if (shot.equals(s.asText())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    shots.add(shot);
                    notes.add("已为 " + sc.path("id").asText("该场景") + " 补充分镜「" + shot + "」");
                }
            }
        }

        // 4) 改标题
        if (containsAny(instruction, lower, "标题", "改名", "命名", "title", "rename")) {
            String newTitle = quoted(instruction);
            if (newTitle != null && !newTitle.isBlank()) {
                ObjectNode meta = root.has("meta") && root.get("meta").isObject()
                        ? (ObjectNode) root.get("meta") : root.putObject("meta");
                meta.put("title", newTitle);
                notes.add("已将标题改为「" + newTitle + "」");
            }
        }

        // 5) 删除场景（需指定场景 id，且至少保留 1 场）
        if (containsAny(instruction, lower, "删除", "去掉", "删掉", "remove", "delete") && targetId != null) {
            int idx = indexOfScene(scenes, targetId);
            if (idx >= 0 && scenes.size() > 1) {
                scenes.remove(idx);
                notes.add("已删除场景 " + targetId);
            }
        }

        // 6) 新增场景
        if (containsAny(instruction, lower, "新增场景", "加一场", "添加场景", "add scene", "new scene")) {
            ObjectNode sc = mapper.createObjectNode();
            sc.put("id", nextSceneId(scenes));
            ObjectNode heading = sc.putObject("heading");
            heading.put("int_ext", "INT");
            heading.put("location", "新场景");
            heading.put("time_of_day", "日");
            ArrayNode els = sc.putArray("elements");
            ObjectNode action = mapper.createObjectNode();
            action.put("type", "action");
            action.put("text", "（新场景内容待补）");
            els.add(action);
            scenes.add(sc);
            notes.add("已新增场景 " + sc.get("id").asText());
        }

        String reply = notes.isEmpty()
                ? "我理解你的需求是：「" + (instruction.isBlank() ? "（空）" : instruction) + "」。当前为离线 stub 模式，"
                  + "可执行的指令包括：调整情绪/节奏、添加画外音(V.O.)、补充分镜、修改标题、删除/新增场景。"
                  + "接入真实大模型后可执行更复杂的精修。"
                : String.join("；", notes) + "。";
        return envelope(reply, root);
    }

    // ───────────────────────── 精修：解析与改写小工具 ─────────────────────────

    private String envelope(String reply, JsonNode screenplay) {
        ObjectNode env = mapper.createObjectNode();
        env.put("reply", reply);
        if (screenplay != null) {
            env.set("screenplay", screenplay);
        }
        try {
            return mapper.writeValueAsString(env);
        } catch (Exception e) {
            return "{\"reply\":\"离线助手内部错误。\"}";
        }
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) {
            return "";
        }
        i += a.length();
        int j = s.indexOf(b, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }

    private static String afterMarker(String s, String marker) {
        int i = s.indexOf(marker);
        return i < 0 ? "" : s.substring(i + marker.length());
    }

    private static boolean containsAny(String original, String lower, String... keys) {
        for (String k : keys) {
            if (k == null || k.isEmpty()) {
                continue;
            }
            if (original.contains(k) || lower.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 情绪+节奏二元组；未命中返回 null。 */
    private static String[] moodPacingFor(String original, String lower) {
        if (containsAny(original, lower, "紧张", "悬疑", "紧迫", "危机", "tense", "tension", "suspense", "thrill")) {
            return new String[]{"紧张", "快"};
        }
        if (containsAny(original, lower, "压抑", "沉重", "悲伤", "绝望", "somber", "heavy", "sad")) {
            return new String[]{"压抑", "缓"};
        }
        if (containsAny(original, lower, "轻松", "轻快", "舒缓", "温馨", "欢快", "calm", "relax", "warm", "gentle")) {
            return new String[]{"轻快", "缓"};
        }
        return null;
    }

    private static final Pattern SCENE_REF = Pattern.compile("[sS](\\d+)|第\\s*(\\d+)\\s*场");

    /** 从指令里解析目标场景 id（如 S2 / 第2场）；无则返回 null。 */
    private static String targetSceneId(String instruction) {
        Matcher m = SCENE_REF.matcher(instruction);
        if (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            return "S" + n;
        }
        return null;
    }

    private static List<ObjectNode> scenesToEdit(ArrayNode scenes, String targetId) {
        List<ObjectNode> out = new ArrayList<>();
        if (targetId != null) {
            ObjectNode sc = findScene(scenes, targetId);
            if (sc != null) {
                out.add(sc);
            }
            return out;
        }
        for (JsonNode s : scenes) {
            if (s.isObject()) {
                out.add((ObjectNode) s);
            }
        }
        return out;
    }

    private static ObjectNode findScene(ArrayNode scenes, String id) {
        for (JsonNode s : scenes) {
            if (s.isObject() && id.equals(s.path("id").asText())) {
                return (ObjectNode) s;
            }
        }
        return null;
    }

    private static int indexOfScene(ArrayNode scenes, String id) {
        for (int i = 0; i < scenes.size(); i++) {
            if (id.equals(scenes.get(i).path("id").asText())) {
                return i;
            }
        }
        return -1;
    }

    private static ObjectNode firstScene(ArrayNode scenes) {
        return scenes.size() > 0 && scenes.get(0).isObject() ? (ObjectNode) scenes.get(0) : null;
    }

    private static String nextSceneId(ArrayNode scenes) {
        int max = 0;
        for (JsonNode s : scenes) {
            String id = s.path("id").asText("");
            if (id.matches("S\\d+")) {
                max = Math.max(max, Integer.parseInt(id.substring(1)));
            }
        }
        return "S" + (max + 1);
    }

    /** 优先取场景在场角色的首个合法 id，否则取角色表首个合法 id，否则 null。 */
    private static String pickCharacterId(ObjectNode scene, ObjectNode root) {
        for (JsonNode p : scene.path("present_characters")) {
            if (p.asText("").matches("C\\d+")) {
                return p.asText();
            }
        }
        for (JsonNode c : root.path("characters")) {
            if (c.path("id").asText("").matches("C\\d+")) {
                return c.path("id").asText();
            }
        }
        return null;
    }

    private static final Pattern QUOTED = Pattern.compile("[「『\"“]([^」』\"”]{1,60})[」』\"”]");

    /** 从指令里抽取引号内的标题文本（支持「」『』 "" “”）。 */
    private static String quoted(String instruction) {
        Matcher m = QUOTED.matcher(instruction);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String scope(String targetId, int count) {
        return targetId != null ? targetId : "全部 " + count + " 个场景";
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

    // 同时支持直角引号「」与全角双引号“ ”（中文网文绝大多数用后者）。
    private static final Pattern ZH_DIALOGUE = Pattern.compile("[「“]([^」”]*)[」”]");
    private static final Pattern EN_DIALOGUE = Pattern.compile("\"([^\"]{2,})\"");
    // 中文说话人：名字(2-4汉字) + 可选修饰 + 说/道/问 等
    // 非贪婪捕名 + 多字动词（说道/笑道…）优先，避免把「说」并进名字（如「林萧说道」误成「林萧说」）。
    private static final Pattern ZH_SPEAKER = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4}?)(?:冷冷|轻轻|缓缓|急急|低声|高声|笑着|淡淡|叹|颤声)?(?:地|着)?"
                    + "(?:说道|笑道|问道|喝道|答道|喊道|说|道|问|喊|叫|答|应|嚷)");
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

        // 1) 收集候选角色名：内置样本用精选 cast（不跑通用正则，避免误捕）；
        //    无内置提示的任意文本才退化到带过滤的对白归属正则识别。
        Set<String> names = new LinkedHashSet<>();
        for (String name : HINTS.keySet()) {
            if (chapter.contains(name)) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            collectSpeakerNames(chapter, zh, names);
        }

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

    // 中文常见非姓名用字/虚词，用于过滤通用正则的误捕。
    private static final Pattern ZH_NON_NAME = Pattern.compile(
            "[的了着是只却又也就还在和与跟把被让对从向到这那们呢吗啊吧很都更最我你他她它母父亲儿女]");

    private void collectSpeakerNames(String chapter, boolean zh, Set<String> names) {
        Matcher m = (zh ? ZH_SPEAKER : EN_SPEAKER).matcher(chapter);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                String name = m.group(g);
                if (name != null && plausibleName(name.trim(), zh)) {
                    names.add(name.trim());
                }
            }
        }
    }

    // 常见会出现在「…说道」前、但并非姓名的副词/连词，剔除以减少误捕。
    private static final java.util.Set<String> COMMON_NONAME = java.util.Set.of(
            "如果", "自然", "然后", "可是", "但是", "于是", "突然", "果然", "竟然", "居然", "当然", "依然",
            "显然", "因为", "所以", "不过", "接着", "随后", "随即", "旋即", "这时", "那时", "此时", "顿时",
            "立刻", "立即", "闻言", "说完", "见状", "心中", "片刻", "半晌", "许久", "忽然", "猛然", "似乎",
            "仿佛", "好像", "也许", "或许", "到底", "究竟", "难道", "其实", "原来", "本来", "已经", "正在");

    /** 通用识别的姓名可信度过滤：中文 2-3 字、不含虚词/代词、且非常见副词/连词；英文首字母大写。 */
    private static boolean plausibleName(String name, boolean zh) {
        if (name.isBlank()) {
            return false;
        }
        if (zh) {
            int len = name.length();
            return len >= 2 && len <= 3 && !ZH_NON_NAME.matcher(name).find() && !COMMON_NONAME.contains(name);
        }
        return Character.isUpperCase(name.charAt(0));
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

    // 仅当出现明确的「内心独白」标记时才判为 narration（→ 画外音），避免把场景描写误转 V.O.。
    private static boolean isNarration(String line, boolean zh) {
        if (zh) {
            return line.matches(".*(心里|心想|想着|记得|后悔|觉得|我知道|我明白|那是我|这辈子|暗想|默念).*");
        }
        return line.matches(".*\\b(remembered|thought|felt|knew|wondered|realized)\\b.*");
    }

    /** 多行文本上的子串/正则查找（{@code String.matches} 的 {@code .} 不跨行，故用 find）。 */
    private static boolean find(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }

    private static String guessIntExt(String text, boolean zh) {
        if (zh) {
            if (find(text, "田埂|田间|地头|村口|街上|路上|野外|院子|门外|河边|山岗|江边|村头") && !find(text, "屋里|房里|堂屋|室内")) {
                return "EXT";
            }
            return "INT";
        }
        if (find(text, "\\b(field|street|road|outside|garden|yard|river|hill)\\b")) {
            return "EXT";
        }
        return "INT";
    }

    private static String guessTime(String text, boolean zh) {
        if (zh) {
            if (find(text, "夜|晚|夤夜|深夜|入夜|月色")) {
                return "夜";
            }
            if (find(text, "清晨|早晨|晨光|白日|晌午|午后|日头|阳光|天不亮|拂晓")) {
                return "日";
            }
            return "";
        }
        if (find(text, "\\b(night|midnight|evening|dusk)\\b")) {
            return "NIGHT";
        }
        if (find(text, "\\b(morning|noon|afternoon|dawn|daylight)\\b")) {
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
