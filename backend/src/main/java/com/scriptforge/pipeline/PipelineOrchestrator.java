package com.scriptforge.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.scriptforge.llm.LlmClient;
import com.scriptforge.model.Chapter;
import com.scriptforge.model.ChapterFacts;
import com.scriptforge.model.Meta;
import com.scriptforge.model.QualityReport;
import com.scriptforge.model.Scene;
import com.scriptforge.model.Screenplay;
import com.scriptforge.model.StoryState;
import com.scriptforge.schema.AutoRepair;

/**
 * 三阶段管线编排器 —— 把章节切分 → 理解 → 生成 → 质检 串成一条流水线，
 * 在各分块间维护同一个 {@link StoryState}（角色圣经），并通过 {@link PipelineListener}
 * 持续上报进度（供 SSE 实时可视化）。这是「过程可见 + 跨章一致性」两大亮点的落地点。
 */
@Component
public class PipelineOrchestrator {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    private final ChapterSplitter splitter;
    private final AnalyzeStage analyze;
    private final ComposeStage compose;
    private final ValidateStage validate;
    private final QualityReporter quality;
    private final LlmClient llm;

    public PipelineOrchestrator(ChapterSplitter splitter, AnalyzeStage analyze, ComposeStage compose,
                                ValidateStage validate, QualityReporter quality, LlmClient llm) {
        this.splitter = splitter;
        this.analyze = analyze;
        this.compose = compose;
        this.validate = validate;
        this.quality = quality;
        this.llm = llm;
    }

    /**
     * 端到端生成剧本。
     *
     * @param novelText   小说全文（UTF-8）
     * @param language    语言：{@code zh}/{@code en}/{@code auto}（auto 时按内容检测）
     * @param title       剧本标题
     * @param sourceTitle 原著信息（写入 meta）
     * @param listener    进度监听（{@link PipelineListener#NOOP} 表示忽略）
     * @return 最终（保证尽力 Schema 合法、含质量报告）的剧本
     * @throws IllegalArgumentException 章节不足 3 章
     */
    public Screenplay run(String novelText, String language, String title, String sourceTitle,
                          PipelineListener listener) {
        // [0] 章节切分
        listener.stage("split", "running");
        listener.progress(2);
        List<Chapter> chapters = splitter.split(novelText);
        if (!ChapterSplitter.hasEnoughChapters(chapters)) {
            listener.stage("split", "failed");
            throw new IllegalArgumentException(
                    "未检测到至少 " + ChapterSplitter.MIN_CHAPTERS + " 个章节，请检查章节标记（如「第X章」「Chapter X」）。");
        }
        listener.log("ok", "按章节标记切分到 " + chapters.size() + " 章");
        listener.stage("split", "done");
        listener.progress(12);

        // [A] 理解 + 角色圣经
        String lang = resolveLanguage(language, novelText);
        StoryState state = new StoryState();
        listener.stage("analyze", "running");
        List<ChapterFacts> facts = new ArrayList<>();
        int total = chapters.size();
        for (int i = 0; i < total; i++) {
            Chapter ch = chapters.get(i);
            listener.log("run", "第" + ch.index() + "章 → 理解中…");
            facts.add(analyze.analyze(ch, state, lang, listener));
            listener.progress(12 + (int) (40.0 * (i + 1) / total));
        }
        listener.log("ok", "角色圣经已建立 · 共 " + state.size() + " 个角色");
        listener.stage("analyze", "done");

        // [B] 生成
        listener.stage("compose", "running");
        listener.progress(56);
        List<Scene> scenes = compose.compose(facts, listener);
        listener.log("ok", "共生成 " + scenes.size() + " 个场景 · 对白已归属说话人");
        listener.stage("compose", "done");
        listener.progress(82);

        // [C] 质检（校验 + 自动修复 + 评分）
        Meta meta = new Meta(title, sourceTitle, null, lang, llm.describe());
        Screenplay draft = new Screenplay(meta, state.snapshot(), scenes, null);
        AutoRepair.RepairOutcome ro = validate.validate(draft, listener);
        listener.progress(94);
        QualityReport report = quality.report(ro.screenplay(), ro.errorCount());
        Screenplay result = new Screenplay(ro.screenplay().meta(), ro.screenplay().characters(),
                ro.screenplay().scenes(), report);
        listener.log("ok", "质检完成 · 综合评分 " + report.score() + "/100 · "
                + (report.schemaValid() ? "Schema 合法" : "仍有 " + report.schemaErrorCount() + " 处错误"));
        listener.progress(100);
        return result;
    }

    private static String resolveLanguage(String language, String text) {
        if ("zh".equalsIgnoreCase(language) || "en".equalsIgnoreCase(language)) {
            return language.toLowerCase();
        }
        return CJK.matcher(text == null ? "" : text).find() ? "zh" : "en";
    }
}
