package com.scriptforge.llm;

import java.util.List;
import java.util.function.Consumer;

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

    /**
     * 多轮对话中的一条消息。
     *
     * @param role    角色：{@code system} / {@code user} / {@code assistant}
     * @param content 文本内容
     */
    record ChatMessage(String role, String content) {}

    /**
     * 多轮对话补全 —— 供「对话精修剧本」使用（带历史 + 当前剧本 → 改写）。
     *
     * <p>{@code messages} 为按时间顺序排列的对话（通常以 {@code user} 结尾，即本轮指令）；
     * {@code systemPrompt} 独立传入。默认实现把历史<strong>扁平化</strong>为单轮 {@link #complete}，
     * OpenAI 兼容 / Claude 客户端覆盖为原生多轮，stub 覆盖为离线确定性精修。
     *
     * @param systemPrompt 系统提示（可为 {@code null}）
     * @param messages     对话消息序列（不含 system）
     * @return 模型返回的原始文本
     */
    default String chat(String systemPrompt, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        if (messages != null) {
            for (ChatMessage m : messages) {
                sb.append(m.role()).append(": ").append(m.content()).append("\n\n");
            }
        }
        return complete(systemPrompt, sb.toString());
    }

    /**
     * 流式多轮补全 —— 供「对话精修流式回复」使用。每收到一段增量文本就回调
     * {@code onToken}；返回拼接后的完整文本（与 {@link #chat} 等价）。
     *
     * <p>默认实现不真正流式：直接调用 {@link #chat} 拿到完整结果，整段回调一次再返回
     * （stub / 未实现流式的客户端走此兜底）。{@code OpenAiCompatibleClient} 覆盖为
     * 原生 {@code stream=true} 的逐 token 流式。
     *
     * @param onToken 增量文本回调（可能被调用 0..N 次）
     * @return 完整文本
     */
    default String chatStream(String systemPrompt, List<ChatMessage> messages, Consumer<String> onToken) {
        String full = chat(systemPrompt, messages);
        if (onToken != null && full != null && !full.isEmpty()) {
            onToken.accept(full);
        }
        return full;
    }
}
