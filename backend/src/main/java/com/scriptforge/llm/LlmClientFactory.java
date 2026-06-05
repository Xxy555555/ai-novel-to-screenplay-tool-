package com.scriptforge.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 大模型客户端工厂 —— 按 {@code scriptforge.llm.provider} 选择实现，注册为唯一的
 * {@link LlmClient} Bean。换 provider 仅改配置、无需改代码（PRD 6.4）。
 */
@Configuration
public class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        String provider = props.getProvider() == null ? "stub" : props.getProvider().trim().toLowerCase();
        LlmClient client = switch (provider) {
            case "openai" -> new OpenAiCompatibleClient(props);
            case "claude" -> new ClaudeClient(props);
            case "stub" -> new StubLlmClient(props);
            default -> {
                log.warn("未知的 scriptforge.llm.provider='{}'，回退到 stub（离线规则桩）。", provider);
                yield new StubLlmClient(props);
            }
        };
        log.info("LLM 适配器就绪：provider={}, model={}", provider, client.describe());
        return client;
    }
}
