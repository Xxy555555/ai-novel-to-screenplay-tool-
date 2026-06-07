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
import com.scriptforge.llm.LlmClient;
import com.scriptforge.llm.LlmProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 全栈端到端（Feature 2）：在 stub 离线模式下打真实 REST + SSE 接口，跑通
 * <strong>上传(带需求) → SSE 生成 → 取剧本/YAML/Fountain → 对话精修 → 重校验</strong> 全链路。
 *
 * <p>用 {@code properties} 强制 {@code provider=stub}（其优先级高于环境变量，确保确定性、不触网）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "scriptforge.llm.provider=stub",
                "scriptforge.llm.base-url=stub://local",
                "scriptforge.llm.api-key=test-key"
        })
class GenerationFlowIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private LlmClient llm;
    @Autowired
    private LlmProperties props;

    @Test
    void stubProviderIsActiveUnderTest() {
        // 证明 properties 覆盖了环境变量里的真实 provider，测试始终离线确定性。
        assertEquals("stub", props.getProvider(), "配置层 provider 必须被强制为 stub");
        assertTrue(llm.describe().startsWith("stub/"), "运行时必须使用 stub 适配器，实得：" + llm.describe());
    }

    @Test
    void fullHttpFlowFromUploadToChatRefine() throws Exception {
        // [1] 创建会话（内置样本 + 用户改编需求）
        Map<String, Object> gen = new HashMap<>();
        gen.put("sample_id", "huozhe");
        gen.put("language", "zh");
        gen.put("requirements", "突出悬疑紧张氛围");
        ResponseEntity<String> genResp = rest.postForEntity("/api/generate", gen, String.class);
        assertEquals(200, genResp.getStatusCode().value(), "POST /api/generate 应成功");
        String sessionId = mapper.readTree(genResp.getBody()).path("session_id").asText();
        assertFalse(sessionId.isBlank(), "应返回 session_id");

        // [2] 打开 SSE 流 —— 驱动后台生成直至完成（emitter complete 后连接关闭）
        ResponseEntity<String> stream = rest.getForEntity("/api/generate/{id}/stream", String.class, sessionId);
        assertEquals(200, stream.getStatusCode().value());
        String sse = stream.getBody();
        assertTrue(sse != null && sse.contains("complete"), "SSE 应推送 complete 事件");
        assertTrue(sse.contains("stage") && sse.contains("scene"), "SSE 应含 stage/scene 过程事件");

        // [3] 取最终剧本 JSON
        ResponseEntity<String> spResp = rest.getForEntity("/api/screenplay/{id}", String.class, sessionId);
        assertEquals(200, spResp.getStatusCode().value());
        JsonNode sp = mapper.readTree(spResp.getBody());
        assertTrue(sp.path("scenes").size() >= 3, "应生成 ≥3 场景");
        assertTrue(sp.path("characters").size() >= 3, "应识别 ≥3 角色");
        assertEquals("突出悬疑紧张氛围", sp.path("meta").path("user_requirements").asText(),
                "meta 应记录用户改编需求");
        assertTrue(sp.path("report").path("schema_valid").asBoolean(), "输出应 Schema 合法");

        // [4] 导出 YAML / Fountain
        ResponseEntity<String> yaml = rest.getForEntity("/api/screenplay/{id}/yaml", String.class, sessionId);
        assertTrue(yaml.getBody() != null && yaml.getBody().contains("scenes:"), "应导出 YAML");
        ResponseEntity<String> fountain = rest.getForEntity("/api/screenplay/{id}/fountain", String.class, sessionId);
        assertFalse(fountain.getBody() == null || fountain.getBody().isBlank(), "应导出 Fountain 文本");

        // [5] 重校验当前 YAML
        ResponseEntity<String> val = rest.postForEntity("/api/validate", Map.of("yaml", yaml.getBody()), String.class);
        assertEquals(200, val.getStatusCode().value());
        assertTrue(mapper.readTree(val.getBody()).path("valid").asBoolean(), "导出的 YAML 应重校验通过");

        // [6] 多轮对话精修：把首个场景改得更紧张
        JsonNode firstSceneBefore = sp.path("scenes").get(0);
        String firstSceneId = firstSceneBefore.path("id").asText();
        String moodBefore = firstSceneBefore.path("mood").asText("");
        Map<String, Object> chat = new HashMap<>();
        chat.put("screenplay", sp);
        chat.put("message", "把 " + firstSceneId + " 改得更紧张");
        chat.put("language", "zh");
        ResponseEntity<String> chatResp = rest.postForEntity("/api/chat", chat, String.class);
        assertEquals(200, chatResp.getStatusCode().value(), "POST /api/chat 应成功");
        JsonNode chatJson = mapper.readTree(chatResp.getBody());
        assertTrue(chatJson.path("changed").asBoolean(), "精修应改动剧本");
        assertTrue(chatJson.path("valid").asBoolean(), "精修后应 Schema 合法");
        assertFalse(chatJson.path("reply").asText().isBlank(), "应有 AI 回复");

        JsonNode refinedScene = null;
        for (JsonNode s : chatJson.path("screenplay").path("scenes")) {
            if (firstSceneId.equals(s.path("id").asText())) {
                refinedScene = s;
                break;
            }
        }
        assertTrue(refinedScene != null, "精修后剧本应仍含该场景");
        assertEquals("紧张", refinedScene.path("mood").asText(), "目标场景情绪应变为紧张");
        org.junit.jupiter.api.Assertions.assertNotEquals("紧张", moodBefore,
                "基线情绪应不是「紧张」，才能证明确实由对话改动而来");
    }
}
