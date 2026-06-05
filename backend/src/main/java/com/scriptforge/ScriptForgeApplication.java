package com.scriptforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 小说转剧本工具后端入口（框架骨架）。
 *
 * <p>分层结构（业务实现时在各包内填充）：
 * <ul>
 *   <li>{@code config}     —— 框架配置（CORS、编码等）</li>
 *   <li>{@code controller} —— REST / SSE 接口层</li>
 *   <li>{@code model}      —— 剧本领域模型</li>
 *   <li>{@code llm}        —— 通用大模型适配器</li>
 *   <li>{@code pipeline}   —— 三阶段管线 + 角色圣经</li>
 *   <li>{@code schema}     —— JSON Schema 校验 + 自动修复</li>
 *   <li>{@code export}     —— YAML / 可读剧本导出</li>
 * </ul>
 */
@SpringBootApplication
public class ScriptForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScriptForgeApplication.class, args);
    }
}
