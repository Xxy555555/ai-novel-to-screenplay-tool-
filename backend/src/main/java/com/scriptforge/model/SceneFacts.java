package com.scriptforge.model;

import java.util.List;

/**
 * 理解层（Analyze）切出的一场戏的「事实」。管线内部数据契约。
 *
 * <p>与最终 {@link Scene} 的区别：这里只有从原文抽取的客观事实（地点/时间/在场/节拍），
 * 尚未编号、未补转场、未做影像化转写、未加标注层——那些由 Compose 阶段完成。
 *
 * @param intExt            内外景（INT / EXT；可空，缺失会在质检中暴露）
 * @param location          地点
 * @param timeOfDay         时间
 * @param presentCharacters 在场角色 id 列表（已由角色圣经消解）
 * @param beats             有序故事节拍
 * @param source            原文溯源片段
 */
public record SceneFacts(
        String intExt,
        String location,
        String timeOfDay,
        List<String> presentCharacters,
        List<Beat> beats,
        String source
) {
    public SceneFacts {
        presentCharacters = presentCharacters == null ? List.of() : List.copyOf(presentCharacters);
        beats = beats == null ? List.of() : List.copyOf(beats);
    }
}
