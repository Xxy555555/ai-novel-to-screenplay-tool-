package com.scriptforge.model;

/**
 * 场景元素流中的单个元素。对应 schema 中 {@code scenes[].elements[]}。
 *
 * <p>剧本「页面」由一串<strong>有序异构</strong>元素构成。本项目用单一记录承载五种类型，
 * 凭 {@code type} 区分，靠 Jackson {@code non_empty} 自动省略无关的空字段，从而既贴合
 * 前端原型里的扁平 flow-map 写法，又便于校验：
 * <ul>
 *   <li>{@code action} 动作行 —— 用 {@code text}</li>
 *   <li>{@code montage} 蒙太奇 —— 用 {@code text}</li>
 *   <li>{@code transition} 转场 —— 用 {@code text}（如 {@code CUT TO:}）</li>
 *   <li>{@code dialogue} 对白 —— 用 {@code character} + {@code line}（可选 {@code parenthetical}）</li>
 *   <li>{@code voiceover} 画外音 V.O. —— 用 {@code character} + {@code line}（心理描写转画外音）</li>
 * </ul>
 *
 * @param type          元素类型（见上）
 * @param text          叙述性文本（action / montage / transition）
 * @param character     说话人角色 id（dialogue / voiceover）
 * @param line          台词内容（dialogue / voiceover）
 * @param parenthetical 情绪 / 动作提示（如「冷冷」「心虚」；可选）
 */
public record Element(
        String type,
        String text,
        String character,
        String line,
        String parenthetical
) {
    /** 元素类型常量，供管线 / 渲染 / 校验复用，避免散落的魔法字符串。 */
    public static final String ACTION = "action";
    public static final String DIALOGUE = "dialogue";
    public static final String VOICEOVER = "voiceover";
    public static final String TRANSITION = "transition";
    public static final String MONTAGE = "montage";

    /** 是否为带说话人的元素（对白 / 画外音）。 */
    public boolean isSpoken() {
        return DIALOGUE.equals(type) || VOICEOVER.equals(type);
    }

    /** 便捷构造：叙述类元素（action / montage / transition）。 */
    public static Element narrative(String type, String text) {
        return new Element(type, text, null, null, null);
    }

    /** 便捷构造：带说话人的元素（dialogue / voiceover）。 */
    public static Element spoken(String type, String character, String line, String parenthetical) {
        return new Element(type, null, character, line, parenthetical);
    }
}
