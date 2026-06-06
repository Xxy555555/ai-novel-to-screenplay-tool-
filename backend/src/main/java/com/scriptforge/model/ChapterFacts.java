package com.scriptforge.model;

import java.util.List;

/**
 * 理解层（Analyze）对单个章节的抽取结果。管线内部数据契约。
 *
 * <p>处理一章后，角色圣经（{@link StoryState}）已就地更新（别名归并、新角色登记），
 * 本对象则承载该章切出的场景事实，交给 Compose 阶段转写为剧本。
 *
 * @param chapterIndex 章节序号（对应 {@link Chapter#index()}）
 * @param scenes       该章切出的场景事实
 */
public record ChapterFacts(
        int chapterIndex,
        List<SceneFacts> scenes
) {
    public ChapterFacts {
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
    }
}
