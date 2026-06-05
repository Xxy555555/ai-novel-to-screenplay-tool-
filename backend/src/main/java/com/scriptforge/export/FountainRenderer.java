package com.scriptforge.export;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.scriptforge.model.Character;
import com.scriptforge.model.Element;
import com.scriptforge.model.Heading;
import com.scriptforge.model.Meta;
import com.scriptforge.model.Scene;
import com.scriptforge.model.Screenplay;

/**
 * 把结构化剧本（{@link Screenplay}）渲染为 <a href="https://fountain.io">Fountain</a> 风格的
 * 可读剧本文本——行业标准的纯文本排版格式，便于复制、打印或导入专业剧本软件。
 *
 * <p>渲染规则与前端原型 {@code design/workbench.html} 里的 {@code fountainText()} 保持一致：
 * <ul>
 *   <li>标题页：{@code Title} / {@code Credit: 改编自} / {@code Author}（字段为空则省略该行）；</li>
 *   <li>场景头：{@code INT. 地点 - 时间} 一行（缺失要素以 {@code ?} 占位）；</li>
 *   <li>action / montage → 正文段落行；</li>
 *   <li>transition → 右顶格转场，前缀 {@code > }（如 {@code > CUT TO:}）；</li>
 *   <li>dialogue → 角色名（大写）一行 + 可选 {@code (parenthetical)} 一行 + 台词行；</li>
 *   <li>voiceover → 角色名后追加 {@code  (V.O.)} 再按对白排版。</li>
 * </ul>
 *
 * <p>对白元素里的 {@code character} 是<strong>角色 id</strong>，需借 {@link Screenplay#characters()}
 * 建立 id→name 映射转成可读姓名；映射缺失时回退为 id 原样（与原型行为一致）。
 *
 * <p>换行统一用 {@code \n}（Fountain 惯例），全链路 UTF-8。
 */
@Component
public class FountainRenderer {

    /** 统一换行符（不随平台变化，保证产出可移植、可 diff）。 */
    private static final String NL = "\n";

    /**
     * 渲染剧本为 Fountain 风格文本。
     *
     * @param sp 剧本对象
     * @return Fountain 风格的可读剧本文本；{@code sp} 为空时返回空串
     */
    public String render(Screenplay sp) {
        if (sp == null) {
            return "";
        }
        Map<String, String> idToName = buildCharacterIndex(sp);
        StringBuilder sb = new StringBuilder();

        appendTitlePage(sb, sp.meta());
        for (Scene scene : sp.scenes()) {
            appendScene(sb, scene, idToName);
        }

        // 去除尾部多余空行，保留单个结尾换行
        String out = sb.toString();
        return out.isBlank() ? "" : out.stripTrailing() + NL;
    }

    /** 建立「角色 id → 规范主名」映射，供对白说话人解析。 */
    private Map<String, String> buildCharacterIndex(Screenplay sp) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Character c : sp.characters()) {
            if (c != null && !blank(c.id())) {
                index.put(c.id(), c.name());
            }
        }
        return index;
    }

    /**
     * 输出标题页。{@code Author} 优先取原著信息 {@code source_title}，缺失再退到 {@code author}；
     * 标题为空则省略 {@code Title} 行，原著信息为空则省略 {@code Credit}/{@code Author} 两行。
     */
    private void appendTitlePage(StringBuilder sb, Meta meta) {
        if (meta == null) {
            return;
        }
        boolean wrote = false;
        if (!blank(meta.title())) {
            sb.append("Title: ").append(meta.title().trim()).append(NL);
            wrote = true;
        }
        String authorLine = !blank(meta.sourceTitle()) ? meta.sourceTitle() : meta.author();
        if (!blank(authorLine)) {
            sb.append("Credit: 改编自").append(NL);
            sb.append("Author: ").append(authorLine.trim()).append(NL);
            wrote = true;
        }
        if (wrote) {
            sb.append(NL);
        }
    }

    /** 输出单个场景：场景头 + 各元素，块间以空行分隔。 */
    private void appendScene(StringBuilder sb, Scene scene, Map<String, String> idToName) {
        if (scene == null) {
            return;
        }
        sb.append(headingLine(scene.heading())).append(NL).append(NL);
        for (Element e : scene.elements()) {
            appendElement(sb, e, idToName);
        }
    }

    /** 组装场景头一行：{@code INT. 地点 - 时间}；缺失要素用 {@code ?} 占位。 */
    private String headingLine(Heading h) {
        String intExt = (h == null || blank(h.intExt())) ? "?" : h.intExt().trim();
        String location = (h == null || blank(h.location())) ? "?" : h.location().trim();
        String time = (h == null || blank(h.timeOfDay())) ? "?" : h.timeOfDay().trim();
        return intExt + ". " + location + " - " + time;
    }

    /** 按元素类型分派排版，每个元素块后接一个空行。 */
    private void appendElement(StringBuilder sb, Element e, Map<String, String> idToName) {
        if (e == null || blank(e.type())) {
            return;
        }
        switch (e.type()) {
            case Element.ACTION, Element.MONTAGE ->
                    sb.append(nullToEmpty(e.text())).append(NL).append(NL);
            case Element.TRANSITION ->
                    sb.append("> ").append(nullToEmpty(e.text())).append(NL).append(NL);
            case Element.DIALOGUE -> appendSpoken(sb, e, idToName, false);
            case Element.VOICEOVER -> appendSpoken(sb, e, idToName, true);
            default -> {
                // 未知类型：忠实保留其文本，避免内容丢失
                if (!blank(e.text())) {
                    sb.append(e.text()).append(NL).append(NL);
                }
            }
        }
    }

    /**
     * 输出带说话人的元素（对白 / 画外音）：角色名（大写）一行、可选括注一行、台词一行。
     *
     * @param voiceover 为 {@code true} 时在角色名后追加 {@code  (V.O.)}
     */
    private void appendSpoken(StringBuilder sb, Element e, Map<String, String> idToName, boolean voiceover) {
        String name = resolveName(e.character(), idToName);
        if (voiceover) {
            name = name + " (V.O.)";
        }
        sb.append(name.toUpperCase(Locale.ROOT)).append(NL);
        if (!blank(e.parenthetical())) {
            sb.append('(').append(e.parenthetical().trim()).append(')').append(NL);
        }
        sb.append(nullToEmpty(e.line())).append(NL).append(NL);
    }

    /** 角色 id → 名字：命中映射用主名，否则回退 id 原样；都为空时用 {@code ?}。 */
    private String resolveName(String characterId, Map<String, String> idToName) {
        if (blank(characterId)) {
            return "?";
        }
        String name = idToName.get(characterId);
        return blank(name) ? characterId : name;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
