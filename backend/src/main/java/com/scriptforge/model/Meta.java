package com.scriptforge.model;

/**
 * 作品元信息。对应 schema 顶层 {@code meta}。
 *
 * @param title       改编后剧本标题（如「《活着》改编」）
 * @param sourceTitle 原著信息（如「改编自 余华《活着》」）
 * @param author      原著作者
 * @param language    主要语言（{@code zh} / {@code en}）
 * @param generatedBy 生成所用模型标识（provider/model，便于溯源；stub 模式为占位值）
 */
public record Meta(
        String title,
        String sourceTitle,
        String author,
        String language,
        String generatedBy
) {
}
