package com.scriptforge.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.scriptforge.llm.LlmClient;
import com.scriptforge.llm.PromptTemplates;
import com.scriptforge.model.Beat;
import com.scriptforge.model.Chapter;
import com.scriptforge.model.ChapterFacts;
import com.scriptforge.model.Character;
import com.scriptforge.model.SceneFacts;
import com.scriptforge.model.StoryState;

/**
 * 理解层 Analyze（★跨章一致性核心驱动）。
 *
 * <p>对每一章调用 {@link LlmClient} 抽取「故事事实 JSON」（形状见 {@link PromptTemplates}），
 * 解析后用同一个 {@link StoryState}（角色圣经）把章内出现的称谓<strong>消解为统一角色 id</strong>，
 * 新角色登记、旧角色补别名/关系，并通过 {@link PipelineListener} 把「识别角色」「归并别名」
 * 等关键动作暴露出来（供 SSE 实时可视化）。最终产出该章的 {@link ChapterFacts}。
 *
 * <p>稳健性：LLM 输出不可解析时降级为「整章作一个动作节拍」，保证管线继续。
 */
@Component
public class AnalyzeStage {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeStage.class);

    private final LlmClient llm;

    /**
     * 解析 LLM 返回 JSON 的容错读取器：允许「反斜杠转义任意字符」。
     *
     * <p>动机：小说正文常含防盗版水印（如「百~万\小!说」中的杂散反斜杠），模型会原样抄入
     * JSON 文本值。严格 JSON 视 {@code \小} 为非法转义会导致整章解析失败而塌缩为兜底单块，
     * 丢失全部对白/动作。开启该特性后 {@code \X} 按字面取 {@code X}，杂散反斜杠不再使整章丢失。
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    public AnalyzeStage(LlmClient llm) {
        this.llm = llm;
    }

    public ChapterFacts analyze(Chapter chapter, StoryState state, String language, PipelineListener listener) {
        String chapterRef = "第" + chapter.index() + "章";
        String raw;
        try {
            String sys = PromptTemplates.analyzeSystem(language);
            String usr = PromptTemplates.analyzeUser(chapter.index(), chapter.title(),
                    chapter.content(), state.snapshot(), language);
            raw = llm.complete(sys, usr);
        } catch (Exception e) {
            log.warn("{} LLM 调用失败：{}", chapterRef, e.getMessage());
            listener.log("warn", chapterRef + " 模型调用失败，使用兜底切分");
            return fallback(chapter);
        }

        JsonNode root;
        try {
            root = mapper.readTree(extractJson(raw));
        } catch (Exception e) {
            log.warn("{} 解析 LLM 输出失败：{}", chapterRef, e.getMessage());
            listener.log("warn", chapterRef + " 输出解析失败，使用兜底切分");
            return fallback(chapter);
        }

        // 1) 角色消解：先取本章之前的全部「已知称谓」，用于判断新角色/新别名。
        Set<String> knownBefore = new LinkedHashSet<>();
        for (Character c : state.snapshot()) {
            knownBefore.add(c.name());
            knownBefore.addAll(c.aliases());
        }
        for (JsonNode c : root.path("characters")) {
            String name = text(c, "name");
            if (name.isBlank()) {
                continue;
            }
            boolean isNew = !knownBefore.contains(name);
            String id = state.resolveOrRegister(name, chapterRef);
            for (JsonNode a : c.path("aliases")) {
                String alias = a.asText("").trim();
                if (alias.isBlank() || alias.equals(name)) {
                    continue;
                }
                boolean aliasNew = !knownBefore.contains(alias);
                state.recordAlias(id, alias);
                if (aliasNew) {
                    listener.aliasMerged(alias, name);
                    knownBefore.add(alias);
                }
            }
            state.setRoleIfAbsent(id, text(c, "role"));
            state.setToneIfAbsent(id, text(c, "tone"));
            for (JsonNode r : c.path("relations")) {
                state.addRelation(id, text(r, "target"), text(r, "relation"));
            }
            knownBefore.add(name);
            // 推送角色卡（取消解后的最新快照）。
            for (Character snap : state.snapshot()) {
                if (snap.id().equals(id)) {
                    if (isNew) {
                        listener.log("ok", chapterRef + " 识别角色：" + name);
                    }
                    listener.characterUpdated(snap);
                    break;
                }
            }
        }

        // 2) 场景事实：present 与 speaker 名字 → id。
        List<SceneFacts> scenes = new ArrayList<>();
        for (JsonNode s : root.path("scenes")) {
            List<String> present = new ArrayList<>();
            for (JsonNode p : s.path("present")) {
                String pid = state.resolveOrRegister(p.asText("").trim(), chapterRef);
                if (pid != null && !present.contains(pid)) {
                    present.add(pid);
                }
            }
            List<Beat> beats = new ArrayList<>();
            for (JsonNode b : s.path("beats")) {
                String kind = text(b, "kind");
                if (kind.isBlank()) {
                    kind = Beat.ACTION;
                }
                String speakerName = b.hasNonNull("speaker") ? b.get("speaker").asText().trim() : "";
                String speakerId = speakerName.isBlank() ? null : state.resolveOrRegister(speakerName, chapterRef);
                String emotion = b.hasNonNull("emotion") ? b.get("emotion").asText().trim() : null;
                if (emotion != null && emotion.isBlank()) {
                    emotion = null;
                }
                beats.add(new Beat(kind, speakerId, text(b, "text"), emotion));
            }
            scenes.add(new SceneFacts(text(s, "int_ext"), text(s, "location"),
                    text(s, "time_of_day"), present, beats, text(s, "source")));
        }

        if (scenes.isEmpty()) {
            return fallback(chapter);
        }
        return new ChapterFacts(chapter.index(), scenes);
    }

    private static ChapterFacts fallback(Chapter chapter) {
        String content = chapter.content() == null ? "" : chapter.content().strip();
        String src = content.length() > 42 ? content.substring(0, 42) + "…" : content;
        Beat beat = new Beat(Beat.ACTION, null, content.isEmpty() ? "（本章内容待补）" : content, null);
        SceneFacts sf = new SceneFacts("INT", "未知场景", "", List.of(), List.of(beat), src);
        return new ChapterFacts(chapter.index(), List.of(sf));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText().trim() : "";
    }

    /** 容错：去掉可能的 ```json 围栏，截取首个 '{' 到末个 '}' 之间的 JSON。 */
    static String extractJson(String s) {
        if (s == null) {
            return "{}";
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            return t.substring(a, b + 1);
        }
        return t;
    }
}
