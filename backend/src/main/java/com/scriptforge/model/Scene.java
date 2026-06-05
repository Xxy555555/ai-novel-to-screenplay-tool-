package com.scriptforge.model;

import java.util.List;

/**
 * 一场戏。对应 schema 顶层 {@code scenes[]}。
 *
 * <p>一章 ≠ 一场戏：场景按「时间 + 地点 + 在场人物」切分（PRD 1.2 难点之一）。
 *
 * @param presentCharacters 在场角色 id 列表（引用 {@link Character#id()}）
 * @param elements          有序异构元素流（{@link Element}）
 * @param mood              情绪基调（标注层，如「压抑」）
 * @param pacing            节奏标签（标注层，如「缓」「快」）
 * @param shots             分镜 / 机位建议（标注层，自由短语，如「推镜·门」「中景」）
 * @param source            原文溯源片段（建立可信、支持局部重生成）
 * @param chapter           来源章节（如「第2章」）
 */
public record Scene(
        String id,
        String chapter,
        Heading heading,
        List<String> presentCharacters,
        List<Element> elements,
        String mood,
        String pacing,
        List<String> shots,
        String source
) {
    public Scene {
        presentCharacters = presentCharacters == null ? List.of() : List.copyOf(presentCharacters);
        elements = elements == null ? List.of() : List.copyOf(elements);
        shots = shots == null ? List.of() : List.copyOf(shots);
    }
}
