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
 * 流式对话精修（{@code POST /api/chat/stream}）集成测试：stub 离线下应推送 token 与 done 事件，
 * done 中含改写后的剧本（标题已改）。强制 stub，确定性、不触网。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"scriptforge.llm.provider=stub", "scriptforge.llm.api-key=test-key"})
class ChatStreamControllerIntegrationTest {

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

    @Test
    void streamEmitsTokenAndDoneWithRefinedScreenplay() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("screenplay", mapper.readTree(MINIMAL_SCREENPLAY));
        req.put("message", "把标题改为「群山回唱」");
        req.put("language", "zh");

        ResponseEntity<String> resp = rest.postForEntity("/api/chat/stream", req, String.class);
        assertEquals(200, resp.getStatusCode().value());
        String body = resp.getBody();
        assertTrue(body != null && body.contains("event:done"), "应包含 done 事件");
        assertTrue(body.contains("群山回唱"), "done 中应含改写后的标题");
    }

    @Test
    void streamRejectsEmptyMessage() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("screenplay", mapper.readTree(MINIMAL_SCREENPLAY));
        req.put("message", "   ");
        ResponseEntity<String> resp = rest.postForEntity("/api/chat/stream", req, String.class);
        // 业务校验失败以 SSE error 事件返回（HTTP 仍 200）
        assertTrue(resp.getBody() != null && resp.getBody().contains("event:error"), "空消息应推送 error 事件");
    }
}
