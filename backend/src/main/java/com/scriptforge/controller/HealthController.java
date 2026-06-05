package com.scriptforge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查端点（框架自检用，非业务逻辑）。
 * 用于验证后端已启动、前端可联通：GET /api/health
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "app", "novel-to-screenplay",
                "version", "1.0.0"
        );
    }
}
