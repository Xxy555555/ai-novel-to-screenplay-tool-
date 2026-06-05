package com.scriptforge.llm;

/**
 * 通用大模型客户端接口 —— 整个适配层只暴露这一个抽象。
 *
 * <p>设计：不为每个厂商写一套对接代码，而是用<strong>一个通用适配器</strong>，通过
 * {@code (base-url + model + api-key)} 三个配置切换任意模型（PRD 6.4）。具体实现由
 * {@link LlmClientFactory} 按 {@code scriptforge.llm.provider} 选择：
 * {@code stub}（离线桩，默认）/ {@code openai}（OpenAI 兼容）/ {@code claude}（原生 Anthropic）。
 *
 * <p>管线各阶段只依赖本接口，不感知底层厂商——换模型仅改配置、无需改代码。
 */
public interface LlmClient {

    /**
     * 单轮补全。
     *
     * @param systemPrompt 系统提示（可为 {@code null}）
     * @param userPrompt   用户提示（通常含章节文本与抽取指令）
     * @return 模型返回的原始文本（通常是一段 JSON，由调用方解析）
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * 模型标识，用于写入 {@code meta.generated_by} 以便溯源。
     * 例如 {@code "stub/scriptforge-stub-1"} 或 {@code "openai/gpt-4o"}。
     */
    String describe();
}
