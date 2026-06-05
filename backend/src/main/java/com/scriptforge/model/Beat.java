package com.scriptforge.model;

/**
 * 理解层（Analyze）从原文抽取的最小「故事节拍」。管线内部数据契约。
 *
 * <p>这是「事实」而非「剧本」：Compose 阶段才把节拍转写为 {@link Element}
 * （如把 {@code narration} 心理描写转成画外音 V.O.，补转场等）。
 *
 * @param kind    节拍类型：{@code action}（动作/事件）/ {@code dialogue}（对白）/
 *                {@code narration}（叙述/心理描写）
 * @param speaker 说话人角色 id（仅 {@code dialogue}；已由角色圣经消解为 id）
 * @param text    节拍文本（事件描述 / 台词 / 叙述）
 * @param emotion 情绪提示（可选，如「冷冷」「心虚」）
 */
public record Beat(
        String kind,
        String speaker,
        String text,
        String emotion
) {
    public static final String ACTION = "action";
    public static final String DIALOGUE = "dialogue";
    public static final String NARRATION = "narration";
}
