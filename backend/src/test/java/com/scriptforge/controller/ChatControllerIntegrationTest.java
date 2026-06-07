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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话精修接口（{@code POST /api/chat}）集成测试：成功改写、参数校验（空消息 / 缺剧本 → 400）。
 * 强制 stub 离线，确定性、不触网。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"scriptforge.llm.provider=stub", "scriptforge.llm.api-key=test-key"})
class ChatControllerIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;

    private static final String MINIMAL_SCREENPLAY = """
            {
              "meta": {"title": "测试剧本", "language": "zh"},
              "characters": [{"id": "C1", "name": "林深"}],
              "scenes": [
                {"id": "S1", "heading": {"int_ext": "INT", "location": "书房", "time_of_day": "夜"},
                 "present_characters": ["C1"],
                 "elements": [{"type": "action", "text": "林深推门而入。"}]}
              ]
            }""";

    private JsonNode screenplayNode() throws Exception {
        return mapper.readTree(MINIMAL_SCREENPLAY);
    }

    @Test
    void chatRefinesScreenplayAndStaysValid() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("screenplay", screenplayNode());
        req.put("message", "把标题改为「群山回唱」");
        req.put("language", "zh");

        ResponseEntity<String> resp = rest.postForEntity("/api/chat", req, String.class);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode json = mapper.readTree(resp.getBody());
        assertTrue(json.path("changed").asBoolean(), "应改动剧本");
        assertTrue(json.path("valid").asBoolean(), "改写后应 Schema 合法");
        assertEquals("群山回唱", json.path("screenplay").path("meta").path("title").asText());
    }

    @Test
    void chatRejectsEmptyMessage() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("screenplay", screenplayNode());
        req.put("message", "   ");
        ResponseEntity<String> resp = rest.postForEntity("/api/chat", req, String.class);
        assertEquals(400, resp.getStatusCode().value(), "空消息应 400");
    }

    @Test
    void chatRejectsMissingScreenplay() {
        Map<String, Object> req = new HashMap<>();
        req.put("message", "把标题改一下");
        ResponseEntity<String> resp = rest.postForEntity("/api/chat", req, String.class);
        assertEquals(400, resp.getStatusCode().value(), "缺少剧本应 400");
    }
}
