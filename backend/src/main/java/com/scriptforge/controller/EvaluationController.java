package com.scriptforge.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.model.Screenplay;
import com.scriptforge.pipeline.QualityEvalStage;

/**
 * 改编质量评测接口：{@code POST /api/evaluate/{sessionId}} —— 把<strong>当前剧本 + 该会话原著小说</strong>
 * 在隔离上下文下交给 AI 评判改编质量并给修改建议（原著由后端按 sessionId 取，前端无需重传）。
 *
 * <p>评测逻辑在 {@link QualityEvalStage}；本控制器只负责请求/响应映射与取原文。
 */
@RestController
@RequestMapping("/api")
public class EvaluationController {

    private final QualityEvalStage evalStage;
    private final GenerationService generation;

    /** 容错 snake_case 映射器：与 {@code ChatController} 一致地把剧本 JSON 转为领域对象。 */
    private final ObjectMapper lenient = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public EvaluationController(QualityEvalStage evalStage, GenerationService generation) {
        this.evalStage = evalStage;
        this.generation = generation;
    }

    /**
     * 评测请求。
     *
     * @param screenplay 当前工作区剧本（snake_case JSON）
     * @param language   语言（{@code zh}/{@code en}/{@code auto}）
     */
    public record EvalRequest(JsonNode screenplay, String language) {}

    /** 评测响应。 */
    public record EvalResponse(int score, String assessment, List<String> suggestions, boolean aiEvaluated) {}

    @PostMapping("/evaluate/{sessionId}")
    public EvalResponse evaluate(@PathVariable String sessionId, @RequestBody EvalRequest req) {
        if (req == null || req.screenplay() == null || req.screenplay().isNull()) {
            throw new IllegalArgumentException("缺少当前剧本，请先生成或加载剧本。");
        }
        String novel = generation.getOriginalText(sessionId);
        if (novel == null || novel.isBlank()) {
            throw new IllegalArgumentException("找不到该会话的原著文本（可能已过期或服务重启），请重新生成后再评测。");
        }
        Screenplay current;
        try {
            current = lenient.treeToValue(req.screenplay(), Screenplay.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析当前剧本：" + e.getMessage());
        }

        QualityEvalStage.EvalResult r = evalStage.evaluate(current, novel, req.language());
        return new EvalResponse(r.score(), r.assessment(), r.suggestions(), r.aiEvaluated());
    }
}
