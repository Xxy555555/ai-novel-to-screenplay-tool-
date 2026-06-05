package com.scriptforge.model;

/**
 * 质量报告中的一条「待改进」项。对应 schema 中 {@code report.issues[]}。
 *
 * <p>{@code sceneId} 用于前端质量面板点击「定位」时跳转到对应场景（联动左栏 ⚠）。
 *
 * @param sceneId  关联场景 id（如「S4」）；非场景级问题可为空
 * @param message  人类可读的问题描述（如「场景缺少时间」）
 * @param severity 严重级别：{@code error} / {@code warning} / {@code info}
 */
public record Issue(
        String sceneId,
        String message,
        String severity
) {
}
