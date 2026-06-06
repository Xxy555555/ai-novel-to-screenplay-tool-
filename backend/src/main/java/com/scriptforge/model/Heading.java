package com.scriptforge.model;

/**
 * 场景头（slug line）三要素。对应 schema 中场景的 {@code heading}。
 *
 * <p>三要素完整率是质量指标之一（PRD 2.2）：缺 {@code timeOfDay} 等会被质检标记。
 *
 * @param intExt    内外景：{@code INT}（内景）/ {@code EXT}（外景）
 * @param location  地点（如「走廊」「赌场」）
 * @param timeOfDay 时间（如「日」「夜」/ {@code DAY} / {@code NIGHT}）
 */
public record Heading(
        String intExt,
        String location,
        String timeOfDay
) {
}
