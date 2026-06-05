package com.scriptforge.model;

import java.util.List;

/**
 * 改编质量报告。对应 schema 顶层 {@code report}（PRD F8 / 2.2 成功指标）。
 *
 * <p>各比率为 0~1 的小数（前端按百分比展示）。综合 {@code score} 为 0~100 整数，
 * 颜色随分值：≥80 绿 / 60–79 琥珀 / &lt;60 红。
 *
 * @param score                       综合评分 0~100
 * @param grade                       评级文案（如「优秀」「良好」「待改进」）
 * @param schemaValid                 是否通过 Schema 校验
 * @param schemaErrorCount            Schema 校验错误数（修复后应为 0）
 * @param dialogueAttributionRate     对白说话人归属覆盖率（目标 ≥0.90）
 * @param characterConsistencyRate    角色一致性 / 无悬空引用率（目标 ≥0.95）
 * @param sceneHeadingCompletenessRate 场景头三要素完整率（目标 ≥0.90）
 * @param showVsTellRatio             「演 / 说」比（动作类 vs 对白类元素占比，体现影像化程度）
 * @param sceneCount                  场景总数
 * @param characterCount              角色总数
 * @param avgElementsPerScene         平均每场元素数
 * @param issues                      待改进项列表
 */
public record QualityReport(
        int score,
        String grade,
        boolean schemaValid,
        int schemaErrorCount,
        double dialogueAttributionRate,
        double characterConsistencyRate,
        double sceneHeadingCompletenessRate,
        double showVsTellRatio,
        int sceneCount,
        int characterCount,
        double avgElementsPerScene,
        List<Issue> issues
) {
    public QualityReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
