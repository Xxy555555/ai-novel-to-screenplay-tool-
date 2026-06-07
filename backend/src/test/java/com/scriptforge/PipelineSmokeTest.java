package com.scriptforge;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.scriptforge.export.YamlExporter;
import com.scriptforge.llm.LlmProperties;
import com.scriptforge.llm.StubLlmClient;
import com.scriptforge.model.Chapter;
import com.scriptforge.model.ChapterFacts;
import com.scriptforge.model.Meta;
import com.scriptforge.model.QualityReport;
import com.scriptforge.model.Scene;
import com.scriptforge.model.Screenplay;
import com.scriptforge.model.StoryState;
import com.scriptforge.pipeline.AnalyzeStage;
import com.scriptforge.pipeline.ChapterSplitter;
import com.scriptforge.pipeline.ComposeStage;
import com.scriptforge.pipeline.PipelineListener;
import com.scriptforge.pipeline.QualityReporter;
import com.scriptforge.pipeline.SampleLibrary;
import com.scriptforge.pipeline.ValidateStage;
import com.scriptforge.schema.AutoRepair;
import com.scriptforge.schema.SchemaValidator;

/** 临时冒烟测试：stub + 内置样本跑通全后端管线，验证产出 Schema 合法。 */
class PipelineSmokeTest {

    @Test
    void huozheEndToEnd() {
        LlmProperties props = new LlmProperties();
        StubLlmClient stub = new StubLlmClient(props);
        ChapterSplitter splitter = new ChapterSplitter();
        AnalyzeStage analyze = new AnalyzeStage(stub);
        ComposeStage compose = new ComposeStage();
        SchemaValidator validator = new SchemaValidator();
        AutoRepair repair = new AutoRepair(validator, stub, props);
        ValidateStage validate = new ValidateStage(repair);
        QualityReporter quality = new QualityReporter();
        YamlExporter exporter = new YamlExporter();
        SampleLibrary samples = new SampleLibrary();

        String novel = samples.load("huozhe");
        List<Chapter> chapters = splitter.split(novel);
        System.out.println("章节数 = " + chapters.size());

        StoryState state = new StoryState();
        List<ChapterFacts> facts = new ArrayList<>();
        for (Chapter ch : chapters) {
            facts.add(analyze.analyze(ch, state, "zh", PipelineListener.NOOP));
        }
        List<Scene> scenes = compose.compose(facts, PipelineListener.NOOP);

        Screenplay draft = new Screenplay(
                new Meta("《活着》改编", "改编自 余华《活着》", "余华", "zh", stub.describe(), null),
                state.snapshot(), scenes, null);
        AutoRepair.RepairOutcome ro = validate.validate(draft, PipelineListener.NOOP);
        QualityReport report = quality.report(ro.screenplay(), ro.errorCount());
        Screenplay finalSp = new Screenplay(ro.screenplay().meta(), ro.screenplay().characters(),
                ro.screenplay().scenes(), report);

        String yaml = exporter.toYaml(finalSp);
        org.junit.jupiter.api.Assertions.assertFalse(yaml.isBlank(), "应能导出 YAML");
        System.out.println("========== 指标 ==========");
        System.out.println("schemaErrorCount = " + ro.errorCount());
        System.out.println("角色 = " + finalSp.characters().size() + " · 场景 = " + finalSp.scenes().size());
        System.out.println("评分 = " + report.score() + " (" + report.grade() + ") · 对白覆盖 "
                + report.dialogueAttributionRate() + " · 一致性 " + report.characterConsistencyRate());
        System.out.println("角色名: " + finalSp.characters().stream().map(c -> c.name() + c.aliases()).toList());

        org.junit.jupiter.api.Assertions.assertEquals(0, ro.errorCount(), "应 Schema 合法");
        org.junit.jupiter.api.Assertions.assertTrue(chapters.size() >= 3, "应切出≥3章");
        org.junit.jupiter.api.Assertions.assertTrue(finalSp.scenes().size() >= 3, "应有≥3场景");
        org.junit.jupiter.api.Assertions.assertTrue(finalSp.characters().size() >= 3, "应识别≥3角色");
    }
}
