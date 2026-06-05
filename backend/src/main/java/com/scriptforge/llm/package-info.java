/**
 * 通用大模型适配器层（骨架占位）。
 *
 * <p>业务实现时在此填充：LlmClient 接口 + StubLlmClient / OpenAiCompatibleClient /
 * ClaudeClient 实现 + LlmClientFactory + PromptTemplates。
 * 设计：一个适配器，换 (base-url + model + api-key) 配置即切换任意模型。详见 PRD 6.4。
 */
package com.scriptforge.llm;
