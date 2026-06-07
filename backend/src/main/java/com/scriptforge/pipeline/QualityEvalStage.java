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
import com.scriptforge.model.Issue;
import com.scriptforge.model.QualityReport;
import com.scriptforge.model.Screenplay;
import com.scriptforge.schema.SchemaValidator;

/**
 * 改编质量评测阶段 —— 把<strong>原著小说 + 改编后剧本</strong>交给 LLM，在<strong>隔离上下文</strong>下
 * （单轮 {@link LlmClient#complete}，不带角色圣经/对话历史/用户需求等任何其它上下文）评判改编质量并给建议。
 *
 * <p>与 {@link RefineStage} 同构：解析 {@code {"score","assessment","suggestions"}} 信封；
 * 当模型输出不可解析（如 stub 离线、网络异常）时<strong>兜底</strong>到确定性的规则版
 * {@link QualityReporter}，据其分数与 issues 生成可读评价与建议 —— 故全流程无 Key 可演示、可测试。
 */
@Component
public class QualityEvalStage {

    private static final Logger log = LoggerFactory.getLogger(QualityEvalStage.class);

    private final LlmClient llm;
    private final QualityReporter quality;
    private final SchemaValidator validator;

    /** 与精修/导出层一致的 snake_case 容错映射器。 */
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public QualityEvalStage(LlmClient llm, QualityReporter quality, SchemaValidator validator) {
        this.llm = llm;
        this.quality = quality;
        this.validator = validator;
    }

    /**
     * 评测结果。
     *
     * @param score       综合评分（0–100）
     * @param assessment  总体评价（2~4 句）
     * @param suggestions 具体修改建议（3~6 条）
     * @param aiEvaluated true=由模型评测；false=模型不可用时的规则版兜底
     */
    public record EvalResult(int score, String assessment, List<String> suggestions, boolean aiEvaluated) {}

    /**
     * 隔离评测改编质量。
     *
     * @param current   改编后剧本（前端工作区事实源，必填）
     * @param novelText 原著小说原文（必填）
     * @param language  语言（{@code zh}/{@code en}/{@code auto}），影响提示语言
     */
    public EvalResult evaluate(Screenplay current, String novelText, String language) {
        if (current == null) {
            throw new IllegalArgumentException("缺少剧本，无法评测。");
        }
        if (novelText == null || novelText.isBlank()) {
            throw new IllegalArgumentException("缺少原著文本，无法评测。");
        }

        String spJson;
        try {
            // 仅传 meta/characters/scenes，剥离我们自算的 report，避免把既有评分泄露给模型影响判断。
            spJson = json.writeValueAsString(
                    new Screenplay(current.meta(), current.characters(), current.scenes(), null));
        } catch (Exception e) {
            spJson = "{}";
        }

        String sys = PromptTemplates.evaluateSystem(language);
        String usr = PromptTemplates.evaluateUser(spJson, novelText);

        String raw;
        try {
            raw = llm.complete(sys, usr);
        } catch (Exception e) {
            log.warn("质量评测 LLM 调用失败：{}", e.getMessage());
            return fallback(current, "AI 暂不可用，已改用离线规则评估。");
        }

        try {
            JsonNode root = json.readTree(RefineStage.extractJson(raw));
            if (root != null && root.isObject()
                    && (root.has("assessment") || root.has("suggestions") || root.has("score"))) {
                String assessment = root.path("assessment").asText("").trim();
                List<String> suggestions = new ArrayList<>();
                JsonNode arr = root.get("suggestions");
                if (arr != null && arr.isArray()) {
                    for (JsonNode n : arr) {
                        String s = n.asText("").trim();
                        if (!s.isBlank()) {
                            suggestions.add(s);
                        }
                    }
                }
                if (!assessment.isBlank() || !suggestions.isEmpty()) {
                    int score = clamp(root.path("score").asInt(ruleScore(current)));
                    if (assessment.isBlank()) {
                        assessment = "已根据原著与剧本完成评测。";
                    }
                    if (suggestions.isEmpty()) {
                        suggestions.add("剧本已较完整，可继续打磨对白与场景节奏。");
                    }
                    return new EvalResult(score, assessment, suggestions, true);
                }
            }
        } catch (Exception e) {
            log.warn("质量评测输出解析失败：{}", e.getMessage());
        }
        return fallback(current, null);
    }

    /** 规则版兜底：用确定性的 {@link QualityReporter} 给分，并据其 issues 生成可读评价与建议。 */
    private EvalResult fallback(Screenplay sp, String note) {
        QualityReport rep = report(sp);
        List<String> suggestions = new ArrayList<>();
        for (Issue i : rep.issues()) {
            if (i != null && i.message() != null && !i.message().isBlank()) {
                suggestions.add(i.message());
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("结构指标良好，可进一步打磨对白的潜台词与场景间的节奏对比。");
        }
        String prefix = note == null || note.isBlank() ? "" : note + " ";
        String assessment = prefix + "（离线规则评估）综合评分 " + rep.score() + "（" + rep.grade()
                + "）：对白归属 " + pct(rep.dialogueAttributionRate()) + "%、角色一致性 "
                + pct(rep.characterConsistencyRate()) + "%、场景头完整 "
                + pct(rep.sceneHeadingCompletenessRate()) + "%。";
        return new EvalResult(rep.score(), assessment, suggestions, false);
    }

    private int ruleScore(Screenplay sp) {
        return report(sp).score();
    }

    private QualityReport report(Screenplay sp) {
        int errCount;
        try {
            errCount = validator.validate(json.valueToTree(sp)).size();
        } catch (Exception e) {
            errCount = 0;
        }
        return quality.report(sp, errCount);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int pct(double v) {
        return (int) Math.round(v * 100.0);
    }
}
