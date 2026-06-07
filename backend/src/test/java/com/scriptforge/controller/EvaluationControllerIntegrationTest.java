package com.scriptforge.controller;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量评测接口（{@code POST /api/evaluate/{sessionId}}）集成测试：
 * 原著由后端按 sessionId 取，stub 离线下走规则版兜底（aiEvaluated=false）但仍返回完整结果；
 * 会话无原文时返回 400。强制 stub 离线，确定性、不触网。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"scriptforge.llm.provider=stub", "scriptforge.llm.api-key=test-key"})
class EvaluationControllerIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private GenerationService generation;

    private static final String NOVEL = """
            福贵年轻时是地主家的少爷，嗜赌成性，终于把家产输给了龙二。
            家道中落后，他被迫学着种田，家珍始终守在他身边。
            此后岁月里，亲人接连离他而去，他却倔强地活着。""";

    private static final String MINIMAL_SCREENPLAY = """
            {
              "meta": {"title": "《活着》改编", "language": "zh"},
              "characters": [{"id": "C1", "name": "福贵"}, {"id": "C2", "name": "家珍"}],
              "scenes": [
                {"id": "S1", "heading": {"int_ext": "INT", "location": "赌坊", "time_of_day": "夜"},
                 "present_characters": ["C1"],
                 "elements": [{"type": "action", "text": "福贵把最后一注推了出去。"}]}
              ]
            }""";

    private JsonNode screenplayNode() throws Exception {
        return mapper.readTree(MINIMAL_SCREENPLAY);
    }

    @Test
    void evaluateReturnsScoreAssessmentAndSuggestions() throws Exception {
        // 先创建会话以在后端登记原著文本（评测时按 sessionId 取）。
        String sid = generation.createSession(null, NOVEL, "zh", "《活着》改编");

        Map<String, Object> req = new HashMap<>();
        req.put("screenplay", screenplayNode());
        req.put("language", "zh");

        ResponseEntity<String> resp = rest.postForEntity("/api/evaluate/" + sid, req, String.class);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode json = mapper.readTree(resp.getBody());

        int score = json.path("score").asInt(-1);
        assertTrue(score >= 0 && score <= 100, "评分应在 0–100：" + score);
        assertTrue(json.path("assessment").asText("").length() > 0, "应有总体评价");
        assertTrue(json.path("suggestions").isArray() && json.path("suggestions").size() > 0, "应有修改建议");
        // stub 离线：评测信封不可解析 → 走确定性规则兜底（响应为 snake_case）。
        assertTrue(json.has("ai_evaluated"), "响应应含 ai_evaluated 字段");
        assertFalse(json.path("ai_evaluated").asBoolean(true), "stub 下应为规则版兜底（ai_evaluated=false）");
    }

    @Test
    void evaluateRejectsUnknownSession() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("screenplay", screenplayNode());
        ResponseEntity<String> resp = rest.postForEntity("/api/evaluate/not-a-real-session", req, String.class);
        assertEquals(400, resp.getStatusCode().value(), "无原著文本应 400");
    }

    @Test
    void evaluateRejectsMissingScreenplay() {
        String sid = generation.createSession(null, NOVEL, "zh", "x");
        Map<String, Object> req = new HashMap<>();
        ResponseEntity<String> resp = rest.postForEntity("/api/evaluate/" + sid, req, String.class);
        assertEquals(400, resp.getStatusCode().value(), "缺少剧本应 400");
    }
}
