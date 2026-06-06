package com.scriptforge.schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.llm.LlmClient;
import com.scriptforge.llm.LlmProperties;
import com.scriptforge.llm.PromptTemplates;
import com.scriptforge.model.Character;
import com.scriptforge.model.Element;
import com.scriptforge.model.Heading;
import com.scriptforge.model.Meta;
import com.scriptforge.model.Scene;
import com.scriptforge.model.Screenplay;

/**
 * 自动修复 —— 保证最终输出「Schema 合法的剧本」（PRD 亮点 #3）。
 *
 * <p>流程：先校验；不合法则把错误连同输出<strong>回喂 LLM 限次修复</strong>
 * （{@code scriptforge.llm.max-repair-retries}）；若仍不合法，走<strong>规则兜底</strong>
 * （补必填、丢弃非法元素、清除悬空角色引用），确保万无一失。
 *
 * <p>由于生成层（{@code ComposeStage}）已做确定性装配，正常路径通常一次校验即通过，
 * 本类是兜底保险与「接真实 LLM 时」的纠错层。
 */
@Component
public class AutoRepair {

    private static final Logger log = LoggerFactory.getLogger(AutoRepair.class);
    private static final Set<String> ELEMENT_TYPES = Set.of(
            Element.ACTION, Element.DIALOGUE, Element.VOICEOVER, Element.TRANSITION, Element.MONTAGE);

    private final SchemaValidator validator;
    private final LlmClient llm;
    private final LlmProperties props;
    private final ObjectMapper json;

    public AutoRepair(SchemaValidator validator, LlmClient llm, LlmProperties props) {
        this.validator = validator;
        this.llm = llm;
        this.props = props;
        this.json = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 修复结果。 */
    public record RepairOutcome(Screenplay screenplay, int errorCount, boolean repaired) {}

    public RepairOutcome repair(Screenplay sp, com.scriptforge.pipeline.PipelineListener listener) {
        List<String> errors = errorsOf(sp);
        if (errors.isEmpty()) {
            return new RepairOutcome(sp, 0, false);
        }
        listener.log("run", "检测到 " + errors.size() + " 处不合法，尝试自动修复…");

        // 1) LLM 限次修复。
        Screenplay current = sp;
        int retries = Math.max(0, props.getMaxRepairRetries());
        for (int i = 0; i < retries; i++) {
            try {
                String raw = llm.complete(null, PromptTemplates.repairUser(toJsonString(current), String.join("\n", errors)));
                Screenplay candidate = json.readValue(extractJson(raw), Screenplay.class);
                List<String> e2 = errorsOf(candidate);
                if (e2.isEmpty()) {
                    listener.log("ok", "LLM 修复成功");
                    return new RepairOutcome(candidate, 0, true);
                }
                current = candidate;
                errors = e2;
            } catch (Exception ex) {
                log.warn("LLM 修复第 {} 次失败：{}", i + 1, ex.getMessage());
            }
        }

        // 2) 规则兜底。
        Screenplay fixed = ruleRepair(current);
        List<String> finalErrors = errorsOf(fixed);
        if (finalErrors.isEmpty()) {
            listener.log("ok", "规则兜底修复成功 · Schema 合法");
        } else {
            log.warn("规则兜底后仍有 {} 处不合法", finalErrors.size());
            listener.log("warn", "仍有 " + finalErrors.size() + " 处无法自动修复");
        }
        return new RepairOutcome(fixed, finalErrors.size(), true);
    }

    /** 直接拿某剧本的 Schema 错误数（供质检/编排使用）。 */
    public int errorCount(Screenplay sp) {
        return errorsOf(sp).size();
    }

    private List<String> errorsOf(Screenplay sp) {
        try {
            JsonNode node = json.valueToTree(sp);
            return validator.validate(node);
        } catch (Exception e) {
            return List.of("序列化失败: " + e.getMessage());
        }
    }

    private String toJsonString(Screenplay sp) throws Exception {
        return json.writeValueAsString(sp);
    }

    // ───────────────────────── 规则兜底 ─────────────────────────

    private Screenplay ruleRepair(Screenplay sp) {
        Meta meta = sp.meta();
        if (meta == null || meta.title() == null || meta.title().isBlank()) {
            String title = meta == null ? null : meta.title();
            meta = new Meta(title == null || title.isBlank() ? "未命名剧本" : title,
                    meta == null ? null : meta.sourceTitle(),
                    meta == null ? null : meta.author(),
                    meta == null ? null : meta.language(),
                    meta == null ? null : meta.generatedBy());
        }

        Set<String> validIds = new LinkedHashSet<>();
        for (Character c : sp.characters()) {
            if (c.id() != null && c.id().matches("^C[0-9]+$") && c.name() != null && !c.name().isBlank()) {
                validIds.add(c.id());
            }
        }

        List<Scene> scenes = new ArrayList<>();
        int idx = 0;
        for (Scene s : sp.scenes()) {
            idx++;
            String id = s.id() == null || !s.id().matches("^S[0-9]+$") ? "S" + idx : s.id();

            Heading h = s.heading();
            String intExt = h != null && "EXT".equals(h.intExt()) ? "EXT" : "INT";
            String location = h != null && h.location() != null && !h.location().isBlank() ? h.location() : "未知场景";
            String time = h == null ? "" : h.timeOfDay();
            Heading heading = new Heading(intExt, location, time);

            List<String> present = new ArrayList<>();
            for (String pid : s.presentCharacters()) {
                if (validIds.contains(pid)) {
                    present.add(pid);
                }
            }

            List<Element> elements = new ArrayList<>();
            for (Element e : s.elements()) {
                Element fixed = fixElement(e, validIds);
                if (fixed != null) {
                    elements.add(fixed);
                }
            }
            if (elements.isEmpty()) {
                elements.add(Element.narrative(Element.ACTION, "（场景内容待补）"));
            }

            scenes.add(new Scene(id, s.chapter(), heading, present, elements,
                    s.mood(), s.pacing(), s.shots(), s.source()));
        }

        return new Screenplay(meta, sp.characters(), scenes, sp.report());
    }

    /** 修一个元素；无法修复（缺必填且补不出）则返回 null 表示丢弃。 */
    private static Element fixElement(Element e, Set<String> validIds) {
        if (e == null || e.type() == null || !ELEMENT_TYPES.contains(e.type())) {
            return null;
        }
        if (e.isSpoken()) {
            if (e.line() == null || e.line().isBlank()) {
                return null; // 对白/画外音缺台词，丢弃。
            }
            String character = e.character() != null && validIds.contains(e.character()) ? e.character() : null;
            return Element.spoken(e.type(), character, e.line(), e.parenthetical());
        }
        if (e.text() == null || e.text().isBlank()) {
            return null; // 动作/转场/蒙太奇缺文本，丢弃。
        }
        return Element.narrative(e.type(), e.text());
    }

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
