package com.scriptforge.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.llm.LlmClient;
import com.scriptforge.model.Screenplay;
import com.scriptforge.pipeline.RefineStage;

/**
 * 流式对话精修接口：{@code POST /api/chat/stream} —— 与 {@link ChatController} 同语义，但以 SSE
 * 逐 token 推送 AI 原始增量（前端据此实时显示回复），最终用 {@code done} 事件下发改写后的剧本。
 *
 * <p>事件：{@code token}（{"text": 增量}）/ {@code done}（同 {@code ChatResponse}）/ {@code error}。
 * 镜像 {@code GenerationService} 的 SSE + 后台线程范式，避免阻塞 Tomcat 线程。
 */
@RestController
@RequestMapping("/api")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    private final RefineStage refine;
    private final ObjectMapper mapper;

    /** 与 ChatController 一致的容错 snake_case 映射器（解析请求里的剧本）。 */
    private final ObjectMapper lenient = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "scriptforge-chat");
        t.setDaemon(true);
        return t;
    });

    public ChatStreamController(RefineStage refine, ObjectMapper mapper) {
        this.refine = refine;
        this.mapper = mapper;
    }

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatController.ChatRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟（大剧本整本改写较慢）
        if (req == null || req.message() == null || req.message().isBlank()) {
            sendQuietly(emitter, "error", Map.of("message", "消息不能为空。"));
            emitter.complete();
            return emitter;
        }
        if (req.screenplay() == null || req.screenplay().isNull()) {
            sendQuietly(emitter, "error", Map.of("message", "缺少当前剧本，请先生成或加载剧本。"));
            emitter.complete();
            return emitter;
        }
        Screenplay current;
        try {
            current = lenient.treeToValue(req.screenplay(), Screenplay.class);
        } catch (Exception e) {
            sendQuietly(emitter, "error", Map.of("message", "无法解析当前剧本：" + e.getMessage()));
            emitter.complete();
            return emitter;
        }
        List<LlmClient.ChatMessage> history = new ArrayList<>();
        if (req.history() != null) {
            for (ChatController.ChatMessageDto m : req.history()) {
                if (m != null && m.content() != null) {
                    history.add(new LlmClient.ChatMessage(m.role() == null ? "user" : m.role(), m.content()));
                }
            }
        }

        executor.submit(() -> {
            try {
                RefineStage.RefineResult r = refine.refineStream(current, req.message(), history, req.language(),
                        token -> sendQuietly(emitter, "token", Map.of("text", token)));
                sendQuietly(emitter, "done", new ChatController.ChatResponse(
                        r.reply(), r.screenplay(), r.changed(), r.errorCount() == 0, r.errorCount(), r.errors()));
                emitter.complete();
            } catch (Exception e) {
                log.warn("流式对话精修失败：{}", e.getMessage());
                sendQuietly(emitter, "error", Map.of("message", e.getMessage() == null ? "对话失败" : e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void sendQuietly(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(mapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 发送失败（{}）：{}", event, e.getMessage());
        }
    }
}
