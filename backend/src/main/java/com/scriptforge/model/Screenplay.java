package com.scriptforge.model;

import java.util.List;

/**
 * 剧本输出契约的根对象，与 {@code screenplay.schema.json} 顶层结构一一对应。
 *
 * <p>四个顶层字段对应 PRD 6.3：
 * <ul>
 *   <li>{@link Meta} —— 作品元信息；</li>
 *   <li>{@code characters} —— 角色注册表（单一事实源，以 id 引用，防止名字漂移）；</li>
 *   <li>{@code scenes} —— 场景序列（每个场景持有有序异构元素流）；</li>
 *   <li>{@link QualityReport} —— 改编质量报告。</li>
 * </ul>
 *
 * <p>序列化：Jackson 全局 {@code SNAKE_CASE + non_empty}，故 Java camelCase 字段会
 * 输出为 schema/前端期望的 snake_case 字段名，空集合/空串自动省略。
 */
public record Screenplay(
        Meta meta,
        List<Character> characters,
        List<Scene> scenes,
        QualityReport report
) {
    public Screenplay {
        characters = characters == null ? List.of() : List.copyOf(characters);
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
    }
}
