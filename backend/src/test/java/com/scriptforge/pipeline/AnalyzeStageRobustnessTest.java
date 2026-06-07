package com.scriptforge.pipeline;

import org.junit.jupiter.api.Test;

import com.scriptforge.llm.LlmClient;
import com.scriptforge.model.Beat;
import com.scriptforge.model.Chapter;
import com.scriptforge.model.ChapterFacts;
import com.scriptforge.model.SceneFacts;
import com.scriptforge.model.StoryState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 理解层稳健性回归：LLM 返回的 JSON 字符串里若含「源文水印反斜杠」造成的非法转义
 * （如 {@code \小}），不得使整章解析失败而塌缩为兜底单块——对白/动作必须照常抽取。
 *
 * <p>背景：小说《重生都市至尊》第三章正文含防盗版水印「百~万\小!说」，模型原样抄入
 * JSON 文本值 → 严格 JSON 解析报「非法转义」→ 旧实现命中 fallback，丢失全部场景结构。
 */
class AnalyzeStageRobustnessTest {

    /** 返回固定原始文本的假客户端，用于精确复现「带非法反斜杠转义的 JSON」。 */
    private static LlmClient fixed(String raw) {
        return new LlmClient() {
            @Override public String complete(String systemPrompt, String userPrompt) {
                return raw;
            }
            @Override public String describe() {
                return "fake/fixed";
            }
        };
    }

    @Test
    void invalidBackslashEscapeInLlmJsonStillExtractsScenes() {
        // 文本值里出现「反斜杠+小」属 JSON 非法转义（JSON 仅允许反斜杠后接 " 反斜杠 / b f n r t 或 u+四位十六进制）。
        String raw = """
                {
                  "characters": [ { "name": "林萧", "aliases": [], "role": "主角", "tone": "冷淡" } ],
                  "scenes": [
                    {
                      "int_ext": "INT", "location": "教室", "time_of_day": "白天",
                      "present": ["林萧", "苏瑾"], "source": "课堂片段",
                      "beats": [
                        { "kind": "dialogue", "speaker": "苏瑾", "text": "林同学，你真的在百~万\\小!说吗？", "emotion": "无奈" },
                        { "kind": "dialogue", "speaker": "林萧", "text": "谢谢。" },
                        { "kind": "action", "speaker": "", "text": "林萧继续埋下头去百~万\\小!说。" }
                      ]
                    }
                  ]
                }""";

        Chapter ch = new Chapter(3, "第三章 睡神的学习方法", "正文略");
        ChapterFacts facts = new AnalyzeStage(fixed(raw))
                .analyze(ch, new StoryState(), "zh", PipelineListener.NOOP);

        // 旧实现会因解析失败塌缩为「1 个 未知场景 + 1 条 action」。修复后应得到结构化场景。
        assertEquals(1, facts.scenes().size(), "应解析出 1 个真实场景");
        SceneFacts sf = facts.scenes().get(0);
        assertEquals("教室", sf.location(), "场景地点应被正确抽取，而非兜底「未知场景」");
        assertEquals(3, sf.beats().size(), "三条节拍（含两条对白）都应保留");
        long dialogue = sf.beats().stream().filter(b -> Beat.DIALOGUE.equals(b.kind())).count();
        assertEquals(2, dialogue, "两条对白都应被抽取并归属说话人");
        assertTrue(sf.beats().stream().anyMatch(b -> b.text() != null && b.text().contains("小!说")),
                "水印文本应保留正文内容（去掉杂散反斜杠即可）");
    }
}
