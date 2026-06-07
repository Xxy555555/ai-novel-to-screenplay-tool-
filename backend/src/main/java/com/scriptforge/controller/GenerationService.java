package com.scriptforge.controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scriptforge.model.Character;
import com.scriptforge.model.Screenplay;
import com.scriptforge.pipeline.PipelineListener;
import com.scriptforge.pipeline.PipelineOrchestrator;
import com.scriptforge.pipeline.SampleLibrary;

/**
 * 生成会话服务 —— 管理「创建会话 → SSE 流式生成 → 取结果」三步，避免 SSE 与生成的竞态：
 * 生成在 SSE 连接建立后才启动（在后台线程里把 {@link PipelineListener} 回调实时写成 SSE 事件）。
 *
 * <p>无持久化（PRD 范围外）：会话与结果仅存内存。
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    /** 待生成会话的输入。 */
    private record InputSpec(String text, String language, String title, String sourceTitle, String requirements) {}

    private final Map<String, InputSpec> pending = new ConcurrentHashMap<>();
    private final Map<String, Screenplay> results = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "scriptforge-gen");
        t.setDaemon(true);
        return t;
    });

    private final PipelineOrchestrator orchestrator;
    private final SampleLibrary samples;
    private final ObjectMapper mapper;

    public GenerationService(PipelineOrchestrator orchestrator, SampleLibrary samples, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.samples = samples;
        this.mapper = mapper;
    }

    /** 兼容重载：不带用户需求的会话创建。 */
    public String createSession(String sampleId, String text, String language, String title) {
        return createSession(sampleId, text, language, title, null);
    }

    /** 创建会话，返回 sessionId；此时尚不启动生成。 */
    public String createSession(String sampleId, String text, String language, String title, String requirements) {
        String novel;
        String src;
        String ttl;
        if (sampleId != null && !sampleId.isBlank()) {
            novel = samples.load(sampleId.trim());
            src = switch (sampleId.trim()) {
                case "huozhe" -> "改编自 余华《活着》";
                case "gift" -> "adapted from an original short story";
                default -> "改编自原作";
            };
            ttl = title != null && !title.isBlank() ? title
                    : ("huozhe".equals(sampleId.trim()) ? "《活着》改编" : "The Gift · adaptation");
        } else {
            novel = text == null ? "" : text;
            src = "改编自原作";
            ttl = title != null && !title.isBlank() ? title : "未命名剧本改编";
        }
        if (novel.isBlank()) {
            throw new IllegalArgumentException("小说内容为空，请上传文本或选择内置示例。");
        }
        String id = UUID.randomUUID().toString();
        pending.put(id, new InputSpec(novel, language, ttl, src, requirements));
        return id;
    }

    /** 打开 SSE 流并在后台启动生成；事件实时推送，完成后发 {@code complete}（含剧本 JSON）。 */
    public SseEmitter openStream(String id) {
        InputSpec spec = pending.get(id);
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时
        if (spec == null) {
            sendQuietly(emitter, "error", Map.of("message", "会话不存在或已过期：" + id));
            emitter.complete();
            return emitter;
        }
        SsePipelineListener listener = new SsePipelineListener(emitter);
        executor.submit(() -> {
            try {
                Screenplay sp = orchestrator.run(spec.text(), spec.language(), spec.title(), spec.sourceTitle(),
                        spec.requirements(), listener);
                results.put(id, sp);
                sendQuietly(emitter, "complete", sp);
                emitter.complete();
            } catch (IllegalArgumentException e) {
                sendQuietly(emitter, "error", Map.of("message", e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                log.error("生成失败", e);
                sendQuietly(emitter, "error", Map.of("message", "生成失败：" + e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 取最终剧本（生成完成后可用，供工作台加载/刷新）。 */
    public Screenplay result(String id) {
        return results.get(id);
    }

    private void sendQuietly(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data),
                    org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 发送失败（{}）：{}", event, e.getMessage());
        }
    }

    /** 把管线回调桥接成 SSE 事件。 */
    private final class SsePipelineListener implements PipelineListener {
        private final SseEmitter emitter;

        SsePipelineListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void stage(String stage, String status) {
            sendQuietly(emitter, "stage", Map.of("stage", stage, "status", status));
        }

        @Override
        public void progress(int percent) {
            sendQuietly(emitter, "progress", Map.of("percent", percent));
        }

        @Override
        public void log(String level, String message) {
            sendQuietly(emitter, "log", Map.of("level", level, "message", message));
        }

        @Override
        public void characterUpdated(Character character) {
            sendQuietly(emitter, "character", character);
        }

        @Override
        public void aliasMerged(String alias, String intoName) {
            sendQuietly(emitter, "alias", Map.of("alias", alias, "into", intoName));
        }

        @Override
        public void sceneCreated(String sceneId, String slug) {
            sendQuietly(emitter, "scene", Map.of("scene_id", sceneId, "slug", slug));
        }
    }
}
