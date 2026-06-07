package com.scriptforge.llm;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.scriptforge.model.Character;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词契约单测：验证「用户改编需求」注入理解层提示（Feature 1a），以及对话精修
 * 信封提示（Feature 1b）含约定标记 —— 这是 stub 离线解析与真实模型共同遵循的契约。
 */
class PromptTemplatesTest {

    @Test
    void analyzeUserInjectsRequirementsWhenPresent() {
        String withReq = PromptTemplates.analyzeUser(1, "第一章", "正文……", List.<Character>of(),
                "zh", "突出悬疑紧张氛围");
        assertTrue(withReq.contains("用户改编需求"), "应包含中文需求标题");
        assertTrue(withReq.contains("突出悬疑紧张氛围"), "应内联用户需求原文");
    }

    @Test
    void analyzeUserOmitsRequirementsBlockWhenBlank() {
        String none = PromptTemplates.analyzeUser(1, "第一章", "正文……", List.<Character>of(), "zh", "  ");
        assertFalse(none.contains("用户改编需求"), "空需求不应注入需求块");
        // 兼容重载（不带需求参数）与之等价。
        String legacy = PromptTemplates.analyzeUser(1, "第一章", "正文……", List.<Character>of(), "zh");
        assertFalse(legacy.contains("用户改编需求"));
    }

    @Test
    void analyzeUserEnglishRequirements() {
        String en = PromptTemplates.analyzeUser(1, "Chapter 1", "text...", List.<Character>of(),
                "en", "emphasize suspense");
        assertTrue(en.contains("User adaptation requirements"));
        assertTrue(en.contains("emphasize suspense"));
    }

    @Test
    void refinePromptsCarryAgreedMarkers() {
        String sys = PromptTemplates.refineSystem("zh");
        assertTrue(sys.contains("reply") && sys.contains("screenplay"), "系统提示应声明信封字段");
        assertTrue(sys.contains("简短"), "系统提示应要求 reply 尽量简短");
        assertTrue(sys.contains("元数据"), "系统提示应禁止在回复中复述元数据");
        assertTrue(PromptTemplates.refineSystem("en").contains("as short as possible"), "英文提示亦应要求简短");
        assertTrue(PromptTemplates.refineSystem("en").contains("metadata"), "英文提示应禁止在回复中复述元数据");

        String user = PromptTemplates.refineUser("{\"meta\":{\"title\":\"x\"}}", "把 S2 改得更紧张");
        assertTrue(user.contains(PromptTemplates.REFINE_SCREENPLAY_MARKER), "应含剧本标记");
        assertTrue(user.contains(PromptTemplates.REFINE_INSTRUCTION_MARKER), "应含指令标记");
        assertTrue(user.contains("把 S2 改得更紧张"), "应内联用户指令");
        assertTrue(user.contains("\"title\":\"x\""), "应内联剧本 JSON");
    }
}
