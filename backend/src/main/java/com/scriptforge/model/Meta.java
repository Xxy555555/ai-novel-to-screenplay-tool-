package com.scriptforge.model;

/**
 * 作品元信息。对应 schema 顶层 {@code meta}。
 *
 * @param title            改编后剧本标题（如「《活着》改编」）
 * @param sourceTitle      原著信息（如「改编自 余华《活着》」）
 * @param author           原著作者
 * @param language         主要语言（{@code zh} / {@code en}）
 * @param generatedBy      生成所用模型标识（provider/model，便于溯源；stub 模式为占位值）
 * @param userRequirements 用户在上传时提出的改编需求（自由文本，影响理解层提示并随剧本溯源；
 *                         schema 顶层 {@code meta} 未限制额外字段，故按 {@code user_requirements}
 *                         记录，空串经 {@code NON_EMPTY} 自动省略）
 */
public record Meta(
        String title,
        String sourceTitle,
        String author,
        String language,
        String generatedBy,
        String userRequirements
) {
}
