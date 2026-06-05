package com.scriptforge.pipeline;

import com.scriptforge.model.Character;

/**
 * 管线进度监听器 —— 各阶段通过它上报「正在发生什么」，让生成过程可见（呼应 SSE 亮点）。
 *
 * <p>全部为 default 空实现：离线/测试场景可传 {@link #NOOP} 忽略；
 * {@code PipelineOrchestrator} 则用一个把事件转成 SSE 的实现（R3 接入）。
 * 解耦的好处：理解/生成/质检阶段不感知 SSE，仍可把「识别角色」「归并别名」「切出场景」
 * 等关键动作暴露出来。
 */
public interface PipelineListener {

    /** 什么都不做的监听器。 */
    PipelineListener NOOP = new PipelineListener() {};

    /** 阶段状态变化。{@code stage} 如「analyze」；{@code status} 如「running」「done」。 */
    default void stage(String stage, String status) {}

    /** 总进度百分比（0~100）。 */
    default void progress(int percent) {}

    /** 一条实时日志（{@code level} 如 info/ok/run）。 */
    default void log(String level, String message) {}

    /** 发现/更新了一个角色（用于右侧「角色圣经」实时增长）。 */
    default void characterUpdated(Character character) {}

    /** 把别名 {@code alias} 归并到角色 {@code intoName}（跨章一致性的可视化点）。 */
    default void aliasMerged(String alias, String intoName) {}

    /** 切出/生成了一个场景。{@code slug} 形如「内·走廊·夜」。 */
    default void sceneCreated(String sceneId, String slug) {}
}
