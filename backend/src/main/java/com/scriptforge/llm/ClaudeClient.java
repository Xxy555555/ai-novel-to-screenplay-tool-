package com.scriptforge.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 原生 Anthropic Messages API 客户端 —— 报文格式与 OpenAI 不同，单独适配。
 *
 * <p>端点 {@code POST {base-url}/v1/messages}（base-url 形如 {@code https://api.anthropic.com}，
 * 不含 {@code /v1}）；鉴权用 {@code x-api-key} 头 + {@code anthropic-version: 2023-06-01}。
 *
 * <p>★注意：<strong>绝不发送 {@code temperature/top_p/top_k}</strong> —— Claude Opus 4.7/4.8
 * 已移除这些采样参数，带上会返回 400。{@code max_tokens} 为必填。
 */
public class ClaudeClient implements LlmClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http;

    public ClaudeClient(LlmProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        int ms = Math.max(1, props.getTimeoutSeconds()) * 1000;
        rf.setConnectTimeout(ms);
        rf.setReadTimeout(ms);
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public String describe() {
        return "claude/" + props.getModel();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)));
        // 故意不放 temperature / top_p / top_k。

        String base = props.getBaseUrl();
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3); // 容错：用户若把 base-url 配成含 /v1，去掉避免重复。
        }
        try {
            String resp = http.post()
                    .uri(base + "/v1/messages")
                    .header("x-api-key", props.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode content = mapper.readTree(resp).path("content");
            StringBuilder sb = new StringBuilder();
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Anthropic Messages API 调用失败（" + base + "）：" + e.getMessage(), e);
        }
    }
}
