package com.scriptforge.pipeline;

import org.springframework.stereotype.Component;

import com.scriptforge.model.Screenplay;
import com.scriptforge.schema.AutoRepair;

/**
 * 质检层 Validate —— 管线面向编排器的「校验 + 自动修复」步骤。
 *
 * <p>委托 {@link AutoRepair} 完成「Schema 校验 → LLM 修复 → 规则兜底」，并把过程
 * 通过 {@link PipelineListener} 暴露给前端。返回最终（已保证尽力合法的）剧本与残余错误数，
 * 供 {@code QualityReporter} 计算 schema 合法率。
 */
@Component
public class ValidateStage {

    private final AutoRepair autoRepair;

    public ValidateStage(AutoRepair autoRepair) {
        this.autoRepair = autoRepair;
    }

    public AutoRepair.RepairOutcome validate(Screenplay screenplay, PipelineListener listener) {
        listener.stage("validate", "running");
        AutoRepair.RepairOutcome outcome = autoRepair.repair(screenplay, listener);
        if (outcome.errorCount() == 0) {
            listener.log("ok", outcome.repaired() ? "校验通过 · 已自动修复" : "Schema 校验通过 · 0 错误");
        } else {
            listener.log("warn", "Schema 仍有 " + outcome.errorCount() + " 处错误");
        }
        listener.stage("validate", "done");
        return outcome;
    }
}
