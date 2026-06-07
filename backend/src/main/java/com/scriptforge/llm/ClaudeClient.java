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
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        messages.add(msg("user", userPrompt == null ? "" : userPrompt));
        return send(systemPrompt, messages);
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history) {
        // 原生多轮：Anthropic 的 messages 仅含 user/assistant（system 单独传）。
        // Anthropic 要求 messages 必须以 user 开头、user/assistant 严格交替。这里做防御性规整：
        // 丢弃开头的 assistant、合并相邻同角色，保证序列合法（即便上游历史不规范也不会 400）。
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        if (history != null) {
            for (ChatMessage m : history) {
                if (m == null || m.content() == null) {
                    continue;
                }
                String role = "assistant".equals(m.role()) ? "assistant" : "user";
                if (messages.isEmpty() && "assistant".equals(role)) {
                    continue; // 丢弃开头的 assistant
                }
                Map<String, Object> last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
                if (last != null && role.equals(last.get("role"))) {
                    last.put("content", last.get("content") + "\n\n" + m.content()); // 合并相邻同角色
                } else {
                    messages.add(msg(role, m.content()));
                }
            }
        }
        if (messages.isEmpty()) {
            messages.add(msg("user", ""));
        }
        return send(systemPrompt, messages);
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String send(String systemPrompt, List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", messages);
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
