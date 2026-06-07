package com.scriptforge.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通用 OpenAI 兼容客户端 —— 覆盖绝大多数厂商（OpenAI / DeepSeek / Kimi / 智谱 GLM /
 * 通义 / 本地 Ollama / OpenRouter 等），它们都提供 {@code /chat/completions} 接口。
 * 换模型只需改 {@code base-url + model + api-key} 三个配置（PRD 6.4）。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    /** 瞬时错误（网关 5xx / 大慢响应提取失败 / 读超时）时的总尝试次数。 */
    private static final int MAX_ATTEMPTS = 2;

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
        return chatCompletions(buildMessages(systemPrompt, history));
    }

    /** 原生多轮：system 在前，随后按序铺开对话历史（含本轮 user 指令）。 */
    private List<Map<String, String>> buildMessages(String systemPrompt, List<ChatMessage> history) {
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
        return messages;
    }

    @Override
    public String chatStream(String systemPrompt, List<ChatMessage> messages, Consumer<String> onToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", buildMessages(systemPrompt, messages));
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        body.put("stream", true);

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            StringBuilder full = new StringBuilder();
            try {
                http.post()
                        .uri(props.getBaseUrl() + "/chat/completions")
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((request, response) -> {
                            if (response.getStatusCode().isError()) {
                                throw new RuntimeException("HTTP " + response.getStatusCode());
                            }
                            try (BufferedReader r = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = r.readLine()) != null) {
                                    String t = line.trim();
                                    if (!t.startsWith("data:")) {
                                        continue;
                                    }
                                    String data = t.substring(5).trim();
                                    if (data.isEmpty() || "[DONE]".equals(data)) {
                                        continue;
                                    }
                                    try {
                                        JsonNode n = mapper.readTree(data);
                                        String tok = n.path("choices").path(0).path("delta").path("content").asText("");
                                        if (!tok.isEmpty()) {
                                            full.append(tok);
                                            if (onToken != null) {
                                                onToken.accept(tok);
                                            }
                                        }
                                    } catch (Exception ignore) {
                                        // 跳过非 JSON 的 SSE 行
                                    }
                                }
                            }
                            return null;
                        });
                return full.toString();
            } catch (Exception e) {
                last = new RuntimeException("OpenAI 兼容端点流式调用失败（" + props.getBaseUrl() + "）：" + e.getMessage(), e);
                log.warn("LLM 流式调用失败（第 {}/{} 次）：{}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        throw last;
    }

    private String chatCompletions(List<Map<String, String>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());

        // 大/慢响应时聚合网关偶发瞬时错误（5xx、提取失败、读超时）——重试若干次。
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
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
                last = new RuntimeException("OpenAI 兼容端点调用失败（" + props.getBaseUrl() + "）：" + e.getMessage(), e);
                log.warn("LLM 调用失败（第 {}/{} 次）：{}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        throw last;
    }
}
