package com.scriptforge.pipeline;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.scriptforge.llm.LlmClient;
import com.scriptforge.llm.LlmProperties;
import com.scriptforge.llm.StubLlmClient;
import com.scriptforge.model.Character;
import com.scriptforge.model.Element;
import com.scriptforge.model.Heading;
import com.scriptforge.model.Meta;
import com.scriptforge.model.Scene;
import com.scriptforge.model.Screenplay;
import com.scriptforge.schema.AutoRepair;
import com.scriptforge.schema.SchemaValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话精修（Feature 1b）核心单测：用离线 stub 验证「指令 → 改写 → 保证 Schema 合法 → 重新评分」，
 * 覆盖情绪/节奏、画外音、改标题、删除场景、无操作与<strong>确定性</strong>。无需 Spring 容器 / 网络。
 */
class RefineStageTest {

    private RefineStage newRefine() {
        LlmProperties props = new LlmProperties(); // 默认 provider=stub
        StubLlmClient stub = new StubLlmClient(props);
        SchemaValidator validator = new SchemaValidator();
        AutoRepair repair = new AutoRepair(validator, stub, props);
        QualityReporter quality = new QualityReporter();
        return new RefineStage(stub, repair, quality, validator);
    }

    /** 构造一份 Schema 合法的基准剧本：1 角色 + 2 场景。 */
    private Screenplay baseScreenplay() {
        Character c1 = new Character("C1", "林深", "主角", List.of("林先生"), "冷峻", List.of(), "第1章");
        Scene s1 = new Scene("S1", "第1章", new Heading("INT", "书房", "夜"), List.of("C1"),
                List.of(Element.narrative(Element.ACTION, "林深推门而入。"),
                        Element.spoken(Element.DIALOGUE, "C1", "我回来了。", null)),
                "平静", "中", List.of("中景"), "原文片段一");
        Scene s2 = new Scene("S2", "第1章", new Heading("INT", "客厅", "夜"), List.of("C1"),
                List.of(Element.narrative(Element.ACTION, "屋里一片漆黑。")),
                "平静", "中", List.of(), "原文片段二");
        return new Screenplay(new Meta("测试剧本", "改编自原作", null, "zh", "stub/test", null),
                List.of(c1), List.of(s1, s2), null);
    }

    private static Scene scene(Screenplay sp, String id) {
        Optional<Scene> s = sp.scenes().stream().filter(x -> id.equals(x.id())).findFirst();
        assertTrue(s.isPresent(), "应存在场景 " + id);
        return s.get();
    }

    @Test
    void moodAndPacingForTargetSceneOnly() {
        RefineStage refine = newRefine();
        RefineStage.RefineResult r = refine.refine(baseScreenplay(), "把 S2 改得更紧张", List.of(), "zh");

        assertTrue(r.changed(), "应判定为已改动");
        assertEquals(0, r.errorCount(), "改写后应 Schema 合法");
        assertTrue(r.errors().isEmpty());
        assertEquals("紧张", scene(r.screenplay(), "S2").mood(), "S2 情绪应改为紧张");
        assertEquals("快", scene(r.screenplay(), "S2").pacing(), "S2 节奏应改为快");
        assertEquals("平静", scene(r.screenplay(), "S1").mood(), "S1 未被指定，应保持不变");
        assertNotNull(r.reply());
        assertFalse(r.reply().isBlank());
    }

    @Test
    void addVoiceoverElementWithValidCharacter() {
        RefineStage refine = newRefine();
        RefineStage.RefineResult r = refine.refine(baseScreenplay(), "给 S1 加一句画外音", List.of(), "zh");

        assertTrue(r.changed());
        assertEquals(0, r.errorCount(), "新增画外音后仍应 Schema 合法");
        long vo = scene(r.screenplay(), "S1").elements().stream()
                .filter(e -> Element.VOICEOVER.equals(e.type())).count();
        assertEquals(1, vo, "S1 应新增一条画外音");
        Element voEl = scene(r.screenplay(), "S1").elements().stream()
                .filter(e -> Element.VOICEOVER.equals(e.type())).findFirst().orElseThrow();
        assertEquals("C1", voEl.character(), "画外音应归属到合法角色 id");
        assertNotNull(voEl.line());
        assertFalse(voEl.line().isBlank(), "画外音台词不应为空");
    }

    @Test
    void changeTitle() {
        RefineStage refine = newRefine();
        RefineStage.RefineResult r = refine.refine(baseScreenplay(), "把标题改为「群山回唱」", List.of(), "zh");
        assertTrue(r.changed());
        assertEquals(0, r.errorCount());
        assertEquals("群山回唱", r.screenplay().meta().title());
    }

    @Test
    void deleteScene() {
        RefineStage refine = newRefine();
        RefineStage.RefineResult r = refine.refine(baseScreenplay(), "删除 S2", List.of(), "zh");
        assertTrue(r.changed());
        assertEquals(0, r.errorCount());
        assertEquals(1, r.screenplay().scenes().size(), "应只剩 1 个场景");
        assertEquals("S1", r.screenplay().scenes().get(0).id());
    }

    @Test
    void unrecognizedInstructionLeavesScreenplayUnchanged() {
        RefineStage refine = newRefine();
        RefineStage.RefineResult r = refine.refine(baseScreenplay(), "今天天气怎么样？", List.of(), "zh");
        assertFalse(r.changed(), "无可执行指令时不应改动剧本");
        assertEquals(0, r.errorCount());
        assertEquals(2, r.screenplay().scenes().size());
        assertNotNull(r.reply());
        assertFalse(r.reply().isBlank());
    }

    /** 用一个固定返回 {@code raw} 的假 LLM 构造 RefineStage，模拟真实模型的各种返回形态。 */
    private RefineStage refineReturning(String raw) {
        LlmProperties props = new LlmProperties();
        StubLlmClient stub = new StubLlmClient(props);
        SchemaValidator validator = new SchemaValidator();
        AutoRepair repair = new AutoRepair(validator, stub, props);
        QualityReporter quality = new QualityReporter();
        LlmClient fake = new LlmClient() {
            @Override public String complete(String s, String u) { return raw; }
            @Override public String describe() { return "fake"; }
            @Override public String chat(String s, List<LlmClient.ChatMessage> m) { return raw; }
        };
        return new RefineStage(fake, repair, quality, validator);
    }

    private static final String VALID_SP =
            "{\"meta\":{\"title\":\"群山回唱\",\"language\":\"zh\"},"
            + "\"characters\":[{\"id\":\"C1\",\"name\":\"林深\"}],"
            + "\"scenes\":[{\"id\":\"S1\",\"heading\":{\"int_ext\":\"INT\",\"location\":\"书房\",\"time_of_day\":\"夜\"},"
            + "\"present_characters\":[\"C1\"],\"elements\":[{\"type\":\"action\",\"text\":\"林深推门而入。\"}]}]}";

    @Test
    void garbledOrTruncatedOutputIsNotEchoedAsReply() {
        // 真实模型把剧本/Schema 当文本吐回、且 JSON 被截断 → 无法解析为剧本。
        String raw = "好的，这是更新后的剧本以及它遵循的 Schema：\n```json\n"
                + "{\"meta\":{\"title\":\"X\"},\"scenes\":[{\"id\":\"S1\",\"heading\":{\"int_ext\":\"INT\"";
        RefineStage.RefineResult r = refineReturning(raw).refine(baseScreenplay(), "把 S1 改紧张", List.of(), "zh");
        assertFalse(r.changed(), "无法解析时不应改动");
        assertEquals(2, r.screenplay().scenes().size(), "应保留原剧本");
        assertFalse(r.reply().contains("\"scenes\""), "回复不应包含 Schema/JSON 字段转储");
        assertFalse(r.reply().contains("```"), "回复不应包含 markdown 围栏");
        assertTrue(r.reply().length() < 200, "回复应简短");
    }

    @Test
    void verboseReplyWithEmbeddedJsonIsSanitized() {
        // 模型返回了合法信封，但 reply 里塞了 Schema/JSON 转储。
        String raw = "{\"reply\":\"已把标题改为群山回唱。完整 Schema 如下：{\\\"scenes\\\":[{\\\"id\\\":\\\"S1\\\"}]} 以上。\","
                + "\"screenplay\":" + VALID_SP + "}";
        RefineStage.RefineResult r = refineReturning(raw).refine(baseScreenplay(), "把标题改为群山回唱", List.of(), "zh");
        assertTrue(r.changed(), "应解析出剧本并判定改动");
        assertEquals("群山回唱", r.screenplay().meta().title());
        assertTrue(r.reply().contains("已把标题改为群山回唱"), "应保留自然语言部分");
        assertFalse(r.reply().contains("\"scenes\""), "应剔除 reply 中的 Schema/JSON 转储");
    }

    @Test
    void markdownWrappedEnvelopeStillSyncs() {
        // 回归：模型用 ```json 围栏 + 前后散文包裹合法信封，仍应解析并同步。
        String raw = "当然，这是结果：\n```json\n{\"reply\":\"已把标题改为群山回唱\",\"screenplay\":" + VALID_SP + "}\n```";
        RefineStage.RefineResult r = refineReturning(raw).refine(baseScreenplay(), "把标题改为群山回唱", List.of(), "zh");
        assertTrue(r.changed());
        assertEquals("群山回唱", r.screenplay().meta().title());
        assertEquals("已把标题改为群山回唱", r.reply());
    }

    @Test
    void refineIsDeterministic() {
        RefineStage refine = newRefine();
        RefineStage.RefineResult a = refine.refine(baseScreenplay(), "把 S1 改得更紧张并加一句画外音", List.of(), "zh");
        RefineStage.RefineResult b = refine.refine(baseScreenplay(), "把 S1 改得更紧张并加一句画外音", List.of(), "zh");
        assertEquals(a.reply(), b.reply(), "相同输入 reply 应一致");
        assertEquals(a.screenplay().toString(), b.screenplay().toString(), "相同输入剧本应一致（确定性）");
        assertTrue(a.changed());
        assertEquals(0, a.errorCount());
    }
}
