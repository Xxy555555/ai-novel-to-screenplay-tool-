package com.scriptforge.model;

import java.util.List;

/**
 * 角色注册表中的一个角色。对应 schema 顶层 {@code characters[]}。
 *
 * <p>这是「角色圣经 / 跨章一致性」的最终落地形态：每个角色有唯一 {@code id}，
 * 场景通过 {@link Scene#presentCharacters()} 与 {@link Element#character()} 以 id 引用，
 * 从而避免同一角色在不同章节因称谓不同（「林深 / 林先生 / 师兄」）被拆成多个人。
 *
 * @param id              唯一标识，形如 {@code C01}（snake_case 不影响，纯标识）
 * @param name            规范化主名（如「福贵」）
 * @param role            角色定位（如「主角」「反派」「配角」；自由文本以支持中英）
 * @param aliases         别名列表（消解后归并到本角色的所有称谓）
 * @param tone            说话口吻 / 性格基调（如「朴实、自嘲」）
 * @param relations       与其他角色的关系
 * @param firstAppearance 首次登场章节（如「第1章」）
 */
public record Character(
        String id,
        String name,
        String role,
        List<String> aliases,
        String tone,
        List<Relation> relations,
        String firstAppearance
) {
    public Character {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}
