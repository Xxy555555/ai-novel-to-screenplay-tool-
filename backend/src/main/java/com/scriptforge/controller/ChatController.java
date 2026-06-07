package com.scriptforge.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.llm.LlmClient;
import com.scriptforge.model.Screenplay;
import com.scriptforge.pipeline.RefineStage;

/**
 * 对话精修接口：{@code POST /api/chat} —— 用户↔AI 多轮对话，直接修改当前剧本。
 *
 * <p>无状态设计：请求体携带<strong>当前工作区剧本</strong>（前端是编辑事实源）、本轮消息与对话历史；
 * 返回 AI 回复 + 改写后的剧本（保证 Schema 合法、含最新质量报告）+ 是否改动 + 校验结果。
 * 真正的精修逻辑在 {@link RefineStage}；本控制器只负责请求/响应映射。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final RefineStage refine;

    /** 容错 snake_case 映射器：把请求里的剧本 JSON 转为领域对象（与导出/修复层一致）。 */
    private final ObjectMapper lenient = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ChatController(RefineStage refine) {
        this.refine = refine;
    }

    /** 对话消息（角色 + 内容）。 */
    public record ChatMessageDto(String role, String content) {}

    /**
     * 精修请求。
     *
     * @param screenplay 当前工作区剧本（snake_case JSON，前端直接带上）
     * @param message    本轮用户指令
     * @param history    之前的对话历史（不含本轮；可空）
     * @param language   语言（{@code zh}/{@code en}/{@code auto}）
     */
    public record ChatRequest(JsonNode screenplay, String message, List<ChatMessageDto> history, String language) {}

    /** 精修响应。 */
    public record ChatResponse(String reply, Screenplay screenplay, boolean changed,
                               boolean valid, int errorCount, List<String> errors) {}

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("消息不能为空。");
        }
        if (req.screenplay() == null || req.screenplay().isNull()) {
            throw new IllegalArgumentException("缺少当前剧本，请先生成或加载剧本。");
        }
        Screenplay current;
        try {
            current = lenient.treeToValue(req.screenplay(), Screenplay.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析当前剧本：" + e.getMessage());
        }

        List<LlmClient.ChatMessage> history = new ArrayList<>();
        if (req.history() != null) {
            for (ChatMessageDto m : req.history()) {
                if (m != null && m.content() != null) {
                    history.add(new LlmClient.ChatMessage(m.role() == null ? "user" : m.role(), m.content()));
                }
            }
        }

        RefineStage.RefineResult r = refine.refine(current, req.message(), history, req.language());
        return new ChatResponse(r.reply(), r.screenplay(), r.changed(),
                r.errorCount() == 0, r.errorCount(), r.errors());
    }
}
