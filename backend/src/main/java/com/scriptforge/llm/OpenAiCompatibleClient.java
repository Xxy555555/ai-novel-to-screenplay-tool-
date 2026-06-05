package com.scriptforge.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通用 OpenAI 兼容客户端 —— 覆盖绝大多数厂商（OpenAI / DeepSeek / Kimi / 智谱 GLM /
 * 通义 / 本地 Ollama / OpenRouter 等），它们都提供 {@code /chat/completions} 接口。
 * 换模型只需改 {@code base-url + model + api-key} 三个配置（PRD 6.4）。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http;

    public OpenAiCompatibleClient(LlmProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        int ms = Math.max(1, props.getTimeoutSeconds()) * 1000;
        rf.setConnectTimeout(ms);
        rf.setReadTimeout(ms);
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public String describe() {
        return "openai/" + props.getModel();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());

        try {
            String resp = http.post()
                    .uri(props.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = mapper.readTree(resp);
            return node.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 兼容端点调用失败（" + props.getBaseUrl() + "）：" + e.getMessage(), e);
        }
    }
}
