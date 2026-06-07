package com.scriptforge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 生成接口：创建会话（POST）+ SSE 流式生成（GET）。
 *
 * <p>分两步避免竞态：先 {@code POST /api/generate} 拿 sessionId，再
 * {@code GET /api/generate/{id}/stream} 用 EventSource 打开 SSE，生成在连接后才启动。
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService service;

    public GenerationController(GenerationService service) {
        this.service = service;
    }

    /**
     * 生成请求：二选一传 {@code sampleId}（内置示例）或 {@code text}（上传正文）。
     *
     * @param requirements 用户上传时提出的改编需求（自由文本，可空）；会注入理解层并记入 meta
     */
    public record GenerateRequest(String sampleId, String text, String language, String title,
                                  String requirements) {}

    public record GenerateResponse(String sessionId) {}

    @PostMapping("/generate")
    public GenerateResponse generate(@RequestBody GenerateRequest req) {
        String id = service.createSession(req.sampleId(), req.text(), req.language(), req.title(), req.requirements());
        return new GenerateResponse(id);
    }

    @GetMapping("/generate/{id}/stream")
    public SseEmitter stream(@PathVariable String id) {
        return service.openStream(id);
    }
}
