package com.scriptforge.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.model.Screenplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量评测阶段单测：stub 离线时评测信封不可解析 → 走确定性规则兜底，仍返回完整可读结果；
 * 缺原著文本时拒绝。强制 stub，离线确定性。
 */
@SpringBootTest(properties = {"scriptforge.llm.provider=stub"})
class QualityEvalStageTest {

    @Autowired
    private QualityEvalStage evalStage;

    private final ObjectMapper lenient = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String SCREENPLAY = """
            {
              "meta": {"title": "测试改编", "language": "zh"},
              "characters": [{"id": "C1", "name": "福贵"}],
              "scenes": [
                {"id": "S1", "heading": {"int_ext": "INT", "location": "赌坊", "time_of_day": "夜"},
                 "present_characters": ["C1"],
                 "elements": [{"type": "dialogue", "character": "C1", "line": "再来一局！"}]}
              ]
            }""";

    private Screenplay screenplay() throws Exception {
        return lenient.treeToValue(lenient.readTree(SCREENPLAY), Screenplay.class);
    }

    @Test
    void fallsBackToRuleBasedWhenLlmOutputUnparseable() throws Exception {
        QualityEvalStage.EvalResult r = evalStage.evaluate(screenplay(), "福贵嗜赌，输光家产后学着活下去。", "zh");
        assertNotNull(r);
        assertFalse(r.aiEvaluated(), "stub 下应为规则版兜底");
        assertTrue(r.score() >= 0 && r.score() <= 100, "评分应在 0–100");
        assertTrue(r.assessment() != null && !r.assessment().isBlank(), "应有总体评价");
        assertFalse(r.suggestions().isEmpty(), "应有修改建议");
    }

    @Test
    void rejectsBlankNovel() throws Exception {
        Screenplay sp = screenplay();
        assertThrows(IllegalArgumentException.class, () -> evalStage.evaluate(sp, "   ", "zh"));
    }
}
