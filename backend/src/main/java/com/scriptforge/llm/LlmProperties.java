package com.scriptforge.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型适配器配置，绑定 {@code application.yml} 的 {@code scriptforge.llm.*}，
 * 全部支持 {@code SCRIPTFORGE_LLM_*} 环境变量覆盖（无需改代码即可切换模型）。
 *
 * <p>用可变 POJO + setter 绑定，是 Spring Boot 最稳妥的配置绑定方式。
 */
@Component
@ConfigurationProperties(prefix = "scriptforge.llm")
public class LlmProperties {

    /** provider：{@code stub}（默认）/ {@code openai}（OpenAI 兼容）/ {@code claude}（原生 Anthropic）。 */
    private String provider = "stub";
    /** API Key；stub 模式可留空。 */
    private String apiKey = "";
    /** 接口基址，如 {@code https://api.openai.com/v1} 或 {@code https://api.anthropic.com}。 */
    private String baseUrl = "https://api.openai.com/v1";
    /** 模型名，如 {@code gpt-4o} / {@code deepseek-chat} / {@code claude-opus-4-8}。 */
    private String model = "gpt-4o-mini";
    /** 采样温度（仅 openai 兼容端点使用；claude opus 4.7/4.8 不接受该参数）。 */
    private double temperature = 0.4;
    /** 请求超时（秒）。 */
    private int timeoutSeconds = 120;
    /** 分块大小（字符数），用于超长文本切块。 */
    private int chunkSize = 3500;
    /** Schema 不合法时回喂模型修复的最大重试次数。 */
    private int maxRepairRetries = 2;
    /** 单次补全的最大输出 token（claude 必填；openai 作上限）。 */
    private int maxTokens = 4096;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getMaxRepairRetries() { return maxRepairRetries; }
    public void setMaxRepairRetries(int maxRepairRetries) { this.maxRepairRetries = maxRepairRetries; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
