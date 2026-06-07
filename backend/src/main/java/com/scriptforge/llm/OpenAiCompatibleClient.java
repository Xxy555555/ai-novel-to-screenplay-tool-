package com.scriptforge.llm;

import java.nio.charset.StandardCharsets;
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
        this(props, defaultBuilder(props));
    }

    /** 可注入 builder 的构造器（测试用：绑定 MockRestServiceServer 验证响应解析鲁棒性）。 */
    OpenAiCompatibleClient(LlmProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    private static RestClient.Builder defaultBuilder(LlmProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        int ms = Math.max(1, props.getTimeoutSeconds()) * 1000;
        rf.setConnectTimeout(ms);
        rf.setReadTimeout(ms);
        return RestClient.builder().requestFactory(rf);
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
        return chatCompletions(messages);
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history) {
        // 原生多轮：system 在前，随后按序铺开对话历史（含本轮 user 指令）。
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (history != null) {
            for (ChatMessage m : history) {
                String role = m.role() == null ? "user" : m.role();
                messages.add(Map.of("role", role, "content", m.content() == null ? "" : m.content()));
            }
        }
        return chatCompletions(messages);
    }

    private String chatCompletions(List<Map<String, String>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());

        try {
            // 按原始字节取响应再自行 UTF-8 解码：绕开 content-type 转换器匹配，
            // 兼容上游把 JSON 响应头误标为 application/octet-stream 的情况（聚合网关偶发）。
            byte[] raw = http.post()
                    .uri(props.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            String resp = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(resp);
            return node.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 兼容端点调用失败（" + props.getBaseUrl() + "）：" + e.getMessage(), e);
        }
    }
}
