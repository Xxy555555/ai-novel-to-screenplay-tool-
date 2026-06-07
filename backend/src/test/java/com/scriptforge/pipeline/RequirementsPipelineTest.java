package com.scriptforge.pipeline;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scriptforge.export.YamlExporter;
import com.scriptforge.llm.LlmProperties;
import com.scriptforge.llm.StubLlmClient;
import com.scriptforge.model.Screenplay;
import com.scriptforge.schema.AutoRepair;
import com.scriptforge.schema.SchemaValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature 1a 端到端（管线级）：用户上传时的「改编需求」应贯穿编排器并记入 {@code meta.user_requirements}，
 * 同时不破坏「输出 Schema 合法」契约；未填需求时该字段省略。用 stub 离线确定性验证。
 */
class RequirementsPipelineTest {

    private PipelineOrchestrator newOrchestrator(StubLlmClient stub, LlmProperties props) {
        SchemaValidator validator = new SchemaValidator();
        AutoRepair repair = new AutoRepair(validator, stub, props);
        return new PipelineOrchestrator(
                new ChapterSplitter(),
                new AnalyzeStage(stub),
                new ComposeStage(),
                new ValidateStage(repair),
                new QualityReporter(),
                stub);
    }

    @Test
    void requirementsRecordedInMetaAndStaysSchemaValid() {
        LlmProperties props = new LlmProperties();
        StubLlmClient stub = new StubLlmClient(props);
        PipelineOrchestrator orch = newOrchestrator(stub, props);
        String novel = new SampleLibrary().load("huozhe");

        Screenplay sp = orch.run(novel, "zh", "《活着》改编", "改编自 余华《活着》",
                "突出悬疑紧张氛围，多用画外音", PipelineListener.NOOP);

        assertEquals("突出悬疑紧张氛围，多用画外音", sp.meta().userRequirements(),
                "用户需求应记入 meta.user_requirements");
        assertTrue(sp.scenes().size() >= 3, "仍应生成 ≥3 场景");
        assertTrue(sp.report().schemaValid(), "带需求生成的结果仍应 Schema 合法");

        // 独立用 SchemaValidator 复核（不依赖 report 自报），杜绝假绿。
        ObjectMapper json = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        assertTrue(new SchemaValidator().validate(json.valueToTree(sp)).isEmpty(),
                "独立 Schema 校验应零错误");

        // 导出 YAML 应能看到 user_requirements 字段。
        String yaml = new YamlExporter().toYaml(sp);
        assertTrue(yaml.contains("user_requirements"), "YAML 应包含 user_requirements");
    }

    @Test
    void noRequirementsOmitsField() {
        LlmProperties props = new LlmProperties();
        StubLlmClient stub = new StubLlmClient(props);
        PipelineOrchestrator orch = newOrchestrator(stub, props);
        String novel = new SampleLibrary().load("huozhe");

        Screenplay sp = orch.run(novel, "zh", "《活着》改编", "改编自 余华《活着》", null, PipelineListener.NOOP);
        assertNull(sp.meta().userRequirements(), "未填需求时应为 null");
        String yaml = new YamlExporter().toYaml(sp);
        assertFalse(yaml.contains("user_requirements"), "空需求经 NON_EMPTY 应从 YAML 省略");
    }
}
