package com.scriptforge.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.llm.LlmClient;
import com.scriptforge.llm.PromptTemplates;
import com.scriptforge.model.QualityReport;
import com.scriptforge.model.Screenplay;
import com.scriptforge.schema.AutoRepair;
import com.scriptforge.schema.SchemaValidator;

/**
 * 对话精修阶段（编辑层）—— 支撑「用户↔AI 多轮对话直接修改剧本」（PRD 5.4 可视化编辑的延伸）。
 *
 * <p>流程：把<strong>当前剧本 + 对话历史 + 本轮指令</strong>组织为多轮提示交给 {@link LlmClient#chat}，
 * 解析模型返回的 {@code {"reply", "screenplay"}} 信封；对改写后的剧本复用 {@link AutoRepair}
 * <strong>保证 Schema 合法</strong>，再用 {@link QualityReporter} 重新评分。
 * stub 离线模式下 {@link LlmClient#chat} 走规则化确定性改写，故全流程无 Key 可演示、可测试。
 *
 * <p>无服务端状态：当前剧本由前端随请求带上（前端是工作区编辑的事实源），本阶段是纯函数式的
 * 「剧本 + 指令 → 新剧本」，与既有 {@code /validate} 一致地保持后端无状态、易测。
 */
@Component
public class RefineStage {

    private static final Logger log = LoggerFactory.getLogger(RefineStage.class);

    private final LlmClient llm;
    private final AutoRepair autoRepair;
    private final QualityReporter quality;
    private final SchemaValidator validator;

    /** 与 {@link AutoRepair}/{@link com.scriptforge.export.YamlExporter} 一致的 snake_case 容错映射器。 */
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RefineStage(LlmClient llm, AutoRepair autoRepair, QualityReporter quality, SchemaValidator validator) {
        this.llm = llm;
        this.autoRepair = autoRepair;
        this.quality = quality;
        this.validator = validator;
    }

    /**
     * 精修结果。
     *
     * @param reply      AI 对本轮的自然语言回复
     * @param screenplay 精修后（保证 Schema 合法、含最新质量报告）的剧本；未改动时为当前剧本
     * @param changed    剧本是否实际发生变化（前端据此决定是否替换工作区）
     * @param errorCount 最终 Schema 错误数（修复后应为 0）
     * @param errors     最终 Schema 错误明细（供前端展示）
     */
    public record RefineResult(String reply, Screenplay screenplay, boolean changed,
                               int errorCount, List<String> errors) {}

    /**
     * 按指令精修剧本。
     *
     * @param current  当前剧本（前端工作区的事实源，必填）
     * @param message  本轮用户指令
     * @param history  之前的对话历史（不含本轮；可为空）
     * @param language 语言（{@code zh}/{@code en}/{@code auto}），影响提示语言
     */
    public RefineResult refine(Screenplay current, String message, List<LlmClient.ChatMessage> history,
                               String language) {
        if (current == null) {
            throw new IllegalArgumentException("缺少当前剧本，无法精修。");
        }
        String origJson = canon(current);

        String sys = PromptTemplates.refineSystem(language);
        List<LlmClient.ChatMessage> msgs = new ArrayList<>();
        if (history != null) {
            for (LlmClient.ChatMessage m : history) {
                if (m != null && m.role() != null && m.content() != null) {
                    msgs.add(m);
                }
            }
        }
        msgs.add(new LlmClient.ChatMessage("user", PromptTemplates.refineUser(origJson, message)));

        String raw;
        try {
            raw = llm.chat(sys, msgs);
        } catch (Exception e) {
            log.warn("对话精修 LLM 调用失败：{}", e.getMessage());
            return unchanged(current, "对话精修调用失败：" + e.getMessage() + "（剧本未改动）。");
        }

        // 解析 {"reply","screenplay"} 信封；容错：模型可能直接回裸剧本对象。
        Parsed p = parseEnvelope(raw);
        // 模型偶发不按信封返回（散文 / markdown / 截断）—— 追加纠正指令、重试一次。
        if (p.refined() == null) {
            List<LlmClient.ChatMessage> retry = new ArrayList<>(msgs);
            retry.add(new LlmClient.ChatMessage("user",
                    "上一次没有按要求返回。请严格只输出 JSON 信封：{\"reply\":\"…\",\"screenplay\":{…完整剧本…}}，"
                            + "不要任何解释、不要 markdown 代码块。"));
            try {
                Parsed p2 = parseEnvelope(llm.chat(sys, retry));
                if (p2.refined() != null) {
                    p = p2;
                }
            } catch (Exception e) {
                log.warn("对话精修重试调用失败：{}", e.getMessage());
            }
        }
        String reply = p.reply();
        Screenplay refined = p.refined();

        if (refined == null) {
            // 解析不到剧本：绝不把原始输出（可能含 Schema/JSON 转储或被截断的内容）回灌给用户。
            String note = sanitizeReply(reply);
            if (note == null || note.isBlank()) {
                note = "未能解析本次返回的剧本改动，已保留当前剧本，请重试或换一种说法。";
            }
            return unchanged(current, note);
        }

        // 保证 Schema 合法（与生成主链路一致的兜底），再重新评分。
        AutoRepair.RepairOutcome ro = autoRepair.repair(refined, PipelineListener.NOOP);
        Screenplay repaired = ro.screenplay();
        boolean changed = !origJson.equals(canon(repaired));
        QualityReport report = quality.report(repaired, ro.errorCount());
        Screenplay withReport = new Screenplay(repaired.meta(), repaired.characters(), repaired.scenes(), report);
        List<String> errors = errorsOf(withReport);

        // 诚实性兜底：剧本无实际变化时，不沿用模型可能的「过度声称」，如实告知未做可见改动。
        String r;
        if (!changed) {
            r = "本次未改动剧本的可见内容（可能已符合要求；如需修改，请换一种更具体的说法）。";
        } else {
            String note = sanitizeReply(reply);
            r = note != null && !note.isBlank() ? note : "已根据你的指令更新剧本。";
        }
        return new RefineResult(r, withReport, changed, ro.errorCount(), errors);
    }

    /** 解析后的信封：自然语言回复 + 剧本（解析不到时 refined 为 null）。 */
    private record Parsed(String reply, Screenplay refined) {}

    /** 从模型原始输出解析 {@code {"reply","screenplay"}} 信封；容错裸剧本对象、markdown 围栏与散文包裹。 */
    private Parsed parseEnvelope(String raw) {
        String reply = null;
        Screenplay refined = null;
        try {
            JsonNode root = json.readTree(extractJson(raw));
            if (root.has("reply")) {
                reply = root.get("reply").asText();
            }
            JsonNode spNode = root.has("screenplay") ? root.get("screenplay")
                    : (root.has("scenes") || root.has("meta") ? root : null);
            if (spNode != null && spNode.isObject()) {
                refined = json.treeToValue(spNode, Screenplay.class);
            }
        } catch (Exception e) {
            log.warn("对话精修输出解析失败：{}", e.getMessage());
        }
        return new Parsed(reply, refined);
    }

    private RefineResult unchanged(Screenplay sp, String reply) {
        return new RefineResult(reply, sp, false, autoRepair.errorCount(sp), errorsOf(sp));
    }

    /** 剔除 report 后的规范 JSON，用于「是否改动」的等价比较。 */
    private String canon(Screenplay sp) {
        try {
            return json.writeValueAsString(new Screenplay(sp.meta(), sp.characters(), sp.scenes(), null));
        } catch (Exception e) {
            return "@" + System.identityHashCode(sp);
        }
    }

    private List<String> errorsOf(Screenplay sp) {
        try {
            return validator.validate(json.valueToTree(sp));
        } catch (Exception e) {
            return List.of("序列化失败: " + e.getMessage());
        }
    }

    /**
     * 清洗 AI 自然语言回复：去 markdown 围栏、截断到首个 JSON 大括号之前、限制长度。
     * 目的：回复只保留「改了哪里」的自然语言，绝不把 Schema / JSON 转储灌给用户（评审反馈）。
     */
    static String sanitizeReply(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("```json", " ").replace("```JSON", " ").replace("```", " ").trim();
        int brace = t.indexOf('{');
        if (brace >= 0) {
            t = t.substring(0, brace).trim();
        }
        if (t.length() > 300) {
            t = t.substring(0, 300).trim() + "…";
        }
        return t;
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
