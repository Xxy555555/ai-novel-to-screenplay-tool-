package com.scriptforge.model;

/**
 * 角色关系。挂在 {@link Character#relations()} 下。
 *
 * @param target   关系对端的角色名或角色 id（如「家珍」或「C02」）
 * @param relation 关系描述（如「妻」「赌友 / 对手」「女」）
 */
public record Relation(
        String target,
        String relation
) {
}
