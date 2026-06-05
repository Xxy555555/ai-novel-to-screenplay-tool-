package com.scriptforge.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.scriptforge.model.Beat;
import com.scriptforge.model.ChapterFacts;
import com.scriptforge.model.Element;
import com.scriptforge.model.Heading;
import com.scriptforge.model.Scene;
import com.scriptforge.model.SceneFacts;

/**
 * 生成层 Compose —— 把理解层的「故事事实」<strong>确定性装配</strong>为剧本场景。
 *
 * <p>设计取舍：生成层不再调用 LLM，而是用规则把 {@link Beat} 转写为剧本 {@link Element}
 * （叙述/心理 → 画外音 V.O.、对白 → 对白、事件 → 动作），跨章连续编号 S1.. ，并按内容
 * 启发式补全标注层（情绪/节奏/分镜）。这样把 LLM 的智能集中在理解层（事实抽取），
 * 生成层则<strong>保证结构合法</strong>——是对「输出 100% 合法 YAML」目标最稳的实现，
 * 与「理解 → 生成」两阶段分离一致（PRD 6.1）。
 */
@Component
public class ComposeStage {

    public List<Scene> compose(List<ChapterFacts> chapters, PipelineListener listener) {
        List<Scene> scenes = new ArrayList<>();
        int seq = 0;
        for (ChapterFacts cf : chapters) {
            String chapterLabel = "第" + cf.chapterIndex() + "章";
            for (SceneFacts sf : cf.scenes()) {
                seq++;
                String id = "S" + seq;

                List<Element> elements = new ArrayList<>();
                for (Beat b : sf.beats()) {
                    elements.add(toElement(b));
                }

                String intExt = normalizeIntExt(sf.intExt());
                String location = blankTo(sf.location(), "未知场景");
                Heading heading = new Heading(intExt, location, nullToEmpty(sf.timeOfDay()));

                String mood = deriveMood(elements);
                String pacing = derivePacing(elements);
                List<String> shots = deriveShots(intExt, elements);

                Scene scene = new Scene(id, chapterLabel, heading, sf.presentCharacters(),
                        elements, mood, pacing, shots, sf.source());
                scenes.add(scene);

                String time = heading.timeOfDay() == null || heading.timeOfDay().isBlank() ? "—" : heading.timeOfDay();
                listener.sceneCreated(id, ("INT".equals(intExt) ? "内" : "外") + "·" + location + "·" + time);
            }
        }
        return scenes;
    }

    /** 节拍 → 元素：narration 有说话人转 V.O.，无说话人作动作；dialogue 转对白（说话人可空）。 */
    private static Element toElement(Beat b) {
        String text = b.text() == null ? "" : b.text();
        return switch (b.kind() == null ? "" : b.kind()) {
            case Beat.DIALOGUE -> Element.spoken(Element.DIALOGUE, b.speaker(), text, b.emotion());
            case Beat.NARRATION -> b.speaker() != null && !b.speaker().isBlank()
                    ? Element.spoken(Element.VOICEOVER, b.speaker(), text, b.emotion())
                    : Element.narrative(Element.ACTION, text);
            default -> Element.narrative(Element.ACTION, text);
        };
    }

    private static String normalizeIntExt(String s) {
        return "EXT".equalsIgnoreCase(s == null ? "" : s.trim()) ? "EXT" : "INT";
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    // ───────────────────────── 标注层启发式（确定性） ─────────────────────────

    private static String deriveMood(List<Element> els) {
        String all = joinText(els);
        if (matches(all, "漆黑|赌|发抖|哭|死|输|债|苦|寒|雪|绝望|后悔")) {
            return "压抑";
        }
        if (matches(all, "吵|争|怒|喊|逼|抢|赌局|颤")) {
            return "紧张";
        }
        if (matches(all, "笑|喜|乐|暖|甜|团圆|丰收")) {
            return "轻快";
        }
        return "平静";
    }

    private static String derivePacing(List<Element> els) {
        long spoken = els.stream().filter(Element::isSpoken).count();
        long action = els.size() - spoken;
        if (spoken > action) {
            return "快";
        }
        if (els.size() >= 6) {
            return "缓";
        }
        return "中";
    }

    private static List<String> deriveShots(String intExt, List<Element> els) {
        boolean hasSpoken = els.stream().anyMatch(Element::isSpoken);
        if ("EXT".equals(intExt)) {
            return List.of("大远景");
        }
        if (hasSpoken) {
            return List.of("中景", "正反打");
        }
        return List.of("全景");
    }

    private static String joinText(List<Element> els) {
        StringBuilder sb = new StringBuilder();
        for (Element e : els) {
            if (e.text() != null) {
                sb.append(e.text()).append(' ');
            }
            if (e.line() != null) {
                sb.append(e.line()).append(' ');
            }
        }
        return sb.toString();
    }

    private static boolean matches(String text, String regex) {
        return java.util.regex.Pattern.compile(regex).matcher(text).find();
    }
}
