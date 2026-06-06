package com.scriptforge.pipeline;

import com.scriptforge.model.Character;
import com.scriptforge.model.Element;
import com.scriptforge.model.Heading;
import com.scriptforge.model.Issue;
import com.scriptforge.model.QualityReport;
import com.scriptforge.model.Scene;
import com.scriptforge.model.Screenplay;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 质量报告器（管线质检层）。把一份 {@link Screenplay} 连同 Schema 校验错误数，
 * 度量为对应 schema 顶层 {@code report} 的 {@link QualityReport}。
 *
 * <p>本类是<strong>纯函数、确定性</strong>的：同样的输入永远得到同样的输出，不读外部状态、
 * 不依赖时间/随机数。各项指标的含义见 {@code docs/yaml-schema-design.md} 第 5 节与 PRD 2.2。
 *
 * <p>分工约定：Schema 能表达的结构约束由校验层（networknt）保证，
 * Schema 管不到的<strong>跨数组引用</strong>（id 必须存在、对白必须有归属）由本类度量并计入质量分。
 */
@Component
public class QualityReporter {

    /** 综合评分权重：对白归属率。 */
    private static final double W_DIALOGUE = 0.28;
    /** 综合评分权重：角色一致性率。 */
    private static final double W_CONSISTENCY = 0.27;
    /** 综合评分权重：场景头完整率。 */
    private static final double W_HEADING = 0.25;
    /** 综合评分权重：Schema 合法（0/1）。 */
    private static final double W_SCHEMA = 0.20;

    /**
     * 生成改编质量报告。
     *
     * @param sp               待评估剧本（其集合字段由 record 构造器保证非 null，可能为空）
     * @param schemaErrorCount Schema 校验错误数（修复后应为 0）
     * @return 度量结果，与 schema 顶层 {@code report} 一一对应
     */
    public QualityReport report(Screenplay sp, int schemaErrorCount) {
        // 1) 角色 id 集合：作为「引用能否解析」的判据（单一事实源）。
        Set<String> characterIds = new HashSet<>();
        for (Character c : sp.characters()) {
            if (c != null && notEmpty(c.id())) {
                characterIds.add(c.id());
            }
        }

        // 2) 单趟遍历场景，累计各项计数。
        int sceneCount = sp.scenes().size();
        int totalElements = 0;     // 全部元素（用于平均）
        int headingComplete = 0;   // heading 三要素均非空的场景数
        int showCount = 0;         // 演：action + montage
        int tellCount = 0;         // 说：dialogue + voiceover
        int spokenTotal = 0;       // 带说话人的元素总数（dialogue + voiceover）
        int spokenAttributed = 0;  // 其中 character 非空且能解析到角色的条数
        int totalRefs = 0;         // 全部角色引用（present_characters + 每条对白的 character）
        int resolvedRefs = 0;      // 其中能在角色集合解析到的引用数
        int unattributedCount = 0; // 未归属对白条数
        String firstUnattributedSceneId = null; // 首个出现未归属对白的场景 id

        List<Issue> issues = new ArrayList<>();

        for (Scene scene : sp.scenes()) {
            // 2.1 场景头三要素完整性：int_ext + location + time_of_day 均非空。
            Heading h = scene.heading();
            boolean intExtOk = h != null && notEmpty(h.intExt());
            boolean locationOk = h != null && notEmpty(h.location());
            boolean timeOk = h != null && notEmpty(h.timeOfDay());
            if (intExtOk && locationOk && timeOk) {
                headingComplete++;
            }
            // 缺时间是「软问题」：记一条可定位的警告（前端可跳转修补），而非 Schema 非法。
            if (!timeOk) {
                issues.add(new Issue(scene.id(), "场景缺少时间", "warning"));
            }

            // 2.2 在场角色引用：每个 present_characters 条目计一次引用。
            for (String cid : scene.presentCharacters()) {
                totalRefs++;
                if (notEmpty(cid) && characterIds.contains(cid)) {
                    resolvedRefs++;
                }
            }

            // 2.3 元素：分类计数，并统计对白归属与引用解析。
            for (Element e : scene.elements()) {
                if (e == null) {
                    continue;
                }
                totalElements++;
                String type = e.type();
                if (Element.ACTION.equals(type) || Element.MONTAGE.equals(type)) {
                    showCount++;
                }
                if (e.isSpoken()) {
                    tellCount++;
                    spokenTotal++;
                    // 每条对白的 character 也是一次角色引用。
                    totalRefs++;
                    boolean attributed = notEmpty(e.character()) && characterIds.contains(e.character());
                    if (attributed) {
                        spokenAttributed++;
                        resolvedRefs++;
                    } else {
                        unattributedCount++;
                        if (firstUnattributedSceneId == null) {
                            firstUnattributedSceneId = scene.id();
                        }
                    }
                }
            }
        }

        // 3) 由计数求比率（分母为 0 时按「无可挑剔」取 1.0），并四舍五入到 2 位小数便于展示。
        double dialogueAttr = round2(spokenTotal == 0 ? 1.0 : (double) spokenAttributed / spokenTotal);
        double charConsistency = round2(totalRefs == 0 ? 1.0 : (double) resolvedRefs / totalRefs);
        double headingRate = round2(sceneCount == 0 ? 1.0 : (double) headingComplete / sceneCount);
        // 演/说比：分母为 0 时退化为演的数量；保留 1 位小数。
        double showVsTell = round1(tellCount == 0 ? showCount : (double) showCount / tellCount);
        double avgElements = round1(sceneCount == 0 ? 0.0 : (double) totalElements / sceneCount);

        boolean schemaValid = schemaErrorCount == 0;

        // 4) 综合评分：四项加权（权重和为 1），放大到百分制后四舍五入取整。
        double composite = W_DIALOGUE * dialogueAttr
                + W_CONSISTENCY * charConsistency
                + W_HEADING * headingRate
                + W_SCHEMA * (schemaValid ? 1.0 : 0.0);
        int score = (int) Math.round(100.0 * composite);
        String grade = grade(score);

        // 5) 汇总剩余问题（顺序：场景缺时间 → 对白未归属 → Schema 错误）。
        if (unattributedCount > 0) {
            issues.add(new Issue(firstUnattributedSceneId,
                    "有" + unattributedCount + "句对白未归属说话人", "warning"));
        }
        if (schemaErrorCount > 0) {
            issues.add(new Issue(null,
                    "Schema 校验存在" + schemaErrorCount + "处错误", "error"));
        }

        return new QualityReport(
                score,
                grade,
                schemaValid,
                schemaErrorCount,
                dialogueAttr,
                charConsistency,
                headingRate,
                showVsTell,
                sceneCount,
                sp.characters().size(),
                avgElements,
                issues
        );
    }

    /** 由综合分给出评级文案：≥90 优秀 / 75–89 良好 / 60–74 中等 / &lt;60 待改进。 */
    private static String grade(int score) {
        if (score >= 90) {
            return "优秀";
        }
        if (score >= 75) {
            return "良好";
        }
        if (score >= 60) {
            return "中等";
        }
        return "待改进";
    }

    /** 字符串「非空」判定：null 或纯空白视为空。 */
    private static boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }

    /** 四舍五入到 1 位小数。 */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 四舍五入到 2 位小数。 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
