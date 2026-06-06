package com.scriptforge.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色圣经 / Story State —— 跨章一致性引擎的可变状态。
 *
 * <p>管线在各章块间持有同一个 {@code StoryState}：每处理完一章，
 * {@code AnalyzeStage} 调用本类把该章出现的称谓<strong>消解</strong>到统一角色 id，
 * 新角色登记、旧角色补别名，并把快照回填给下一章作为上下文。这正是本设计对
 * 「3 章以上跨章角色一致性」难点的核心回答（PRD 1.2）。
 *
 * <p>消解策略（{@link #resolveOrRegister}）：
 * <ol>
 *   <li>命中已知称谓（主名或别名，去空白后比较）→ 返回既有 id；</li>
 *   <li>否则登记新角色，分配形如 {@code C01} 的 id，主名记为该称谓。</li>
 * </ol>
 * 更进一步的别名归并（如 LLM 显式告知「老爷 == 福贵」）由 {@link #recordAlias} /
 * {@link #merge} 完成。本类不依赖任何 LLM，纯确定性，便于测试与离线演示。
 */
public final class StoryState {

    /** 可变的角色条目（对外快照时再转成不可变 {@link Character} 记录）。 */
    private static final class Entry {
        final String id;
        String name;
        String role;
        final List<String> aliases = new ArrayList<>();
        String tone;
        final List<Relation> relations = new ArrayList<>();
        String firstAppearance;

        Entry(String id, String name) {
            this.id = id;
            this.name = name;
        }

        Character toCharacter() {
            return new Character(id, name, role, List.copyOf(aliases), tone,
                    List.copyOf(relations), firstAppearance);
        }
    }

    /** id → 条目，保持插入顺序（即角色登场顺序）。 */
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    /** 归一化称谓 → id，用于 O(1) 消解。 */
    private final Map<String, String> aliasToId = new LinkedHashMap<>();
    private int counter = 0;

    /** 归一化称谓：去首尾空白；称谓为空视为无效。 */
    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 把一个称谓消解到角色 id：命中已知称谓则复用，否则登记新角色。
     *
     * @param appellation     原文中的称谓（主名 / 别名 / 代称）
     * @param firstAppearance 若新建角色，记录其首次登场章节（如「第1章」）
     * @return 该称谓对应的角色 id；称谓为空返回 {@code null}
     */
    public String resolveOrRegister(String appellation, String firstAppearance) {
        String key = norm(appellation);
        if (key.isEmpty()) {
            return null;
        }
        String existing = aliasToId.get(key);
        if (existing != null) {
            return existing;
        }
        String id = String.format("C%02d", ++counter);
        Entry e = new Entry(id, key);
        e.aliases.add(key);
        e.firstAppearance = firstAppearance;
        byId.put(id, e);
        aliasToId.put(key, id);
        return id;
    }

    /** 给已存在的角色追加别名，并登记到消解索引。重复别名忽略。 */
    public void recordAlias(String id, String alias) {
        String key = norm(alias);
        Entry e = byId.get(id);
        if (e == null || key.isEmpty()) {
            return;
        }
        if (!e.aliases.contains(key)) {
            e.aliases.add(key);
        }
        aliasToId.putIfAbsent(key, id);
    }

    /** 设定角色定位（如「主角」），仅在尚未设定时写入。 */
    public void setRoleIfAbsent(String id, String role) {
        Entry e = byId.get(id);
        if (e != null && (e.role == null || e.role.isBlank()) && role != null && !role.isBlank()) {
            e.role = role.trim();
        }
    }

    /** 设定口吻 / 性格基调，仅在尚未设定时写入。 */
    public void setToneIfAbsent(String id, String tone) {
        Entry e = byId.get(id);
        if (e != null && (e.tone == null || e.tone.isBlank()) && tone != null && !tone.isBlank()) {
            e.tone = tone.trim();
        }
    }

    /** 追加一条角色关系（去重）。 */
    public void addRelation(String id, String target, String relation) {
        Entry e = byId.get(id);
        if (e == null || norm(target).isEmpty() || norm(relation).isEmpty()) {
            return;
        }
        Relation r = new Relation(norm(target), norm(relation));
        if (!e.relations.contains(r)) {
            e.relations.add(r);
        }
    }

    /**
     * 把 {@code fromId} 合并进 {@code intoId}（实体消解纠错）：别名/关系迁移，
     * 别名索引重指向，删除被合并条目。被合并后 {@code fromId} 不再有效。
     */
    public void merge(String intoId, String fromId) {
        if (intoId == null || intoId.equals(fromId)) {
            return;
        }
        Entry into = byId.get(intoId);
        Entry from = byId.remove(fromId);
        if (into == null || from == null) {
            return;
        }
        for (String a : from.aliases) {
            if (!into.aliases.contains(a)) {
                into.aliases.add(a);
            }
        }
        for (Relation r : from.relations) {
            if (!into.relations.contains(r)) {
                into.relations.add(r);
            }
        }
        if (into.role == null || into.role.isBlank()) {
            into.role = from.role;
        }
        if (into.tone == null || into.tone.isBlank()) {
            into.tone = from.tone;
        }
        aliasToId.replaceAll((alias, id) -> id.equals(fromId) ? intoId : id);
    }

    /** 该 id 是否已登记。 */
    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** 当前已登记角色数。 */
    public int size() {
        return byId.size();
    }

    /**
     * 角色注册表快照（不可变），按登场顺序排列。
     * 用于回填下一章上下文、写入最终 {@link Screenplay#characters()}，以及 SSE 实时推送。
     */
    public List<Character> snapshot() {
        List<Character> out = new ArrayList<>(byId.size());
        for (Entry e : byId.values()) {
            out.add(e.toCharacter());
        }
        return List.copyOf(out);
    }
}
