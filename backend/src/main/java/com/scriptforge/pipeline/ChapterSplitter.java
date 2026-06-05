package com.scriptforge.pipeline;

import com.scriptforge.model.Chapter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 章节切分器：三阶段管线的第一步。
 *
 * <p>把整篇小说原文按「章节标题行」切成有序 {@link Chapter} 列表，供后续
 * {@code AnalyzeStage} 逐章理解。本步只做结构切分，不做任何语义抽取。
 *
 * <p>识别规则（按行匹配，允许行首少量空白）：
 * <ul>
 *   <li>中文：行首「第 + (一二三四五六七八九十百零两 或阿拉伯数字)+ + 章/回/节」，如「第二章」「第10回」；</li>
 *   <li>英文：行首「Chapter + 空白 + 内容」，大小写不敏感，如「Chapter 2」「CHAPTER ONE: ...」。</li>
 * </ul>
 *
 * <p>若全篇未匹配到任何标题，则把全文作为单章（标题「全文」）返回，由上层用
 * {@link #hasEnoughChapters(List)} 判断章节数是否达到 {@link #MIN_CHAPTERS}。
 */
@Component
public class ChapterSplitter {

    /** 题目要求的最少章节数（小说需 3 章以上才适合本工具改编）。 */
    public static final int MIN_CHAPTERS = 3;

    /** 中文章节标题：行首「第…章/回/节」。 */
    private static final Pattern CN_HEADING =
            Pattern.compile("^\\s*第[一二三四五六七八九十百零两0-9]+[章回节]");

    /** 英文章节标题：行首「Chapter <内容>」，大小写不敏感。 */
    private static final Pattern EN_HEADING =
            Pattern.compile("^\\s*chapter\\s+\\S.*", Pattern.CASE_INSENSITIVE);

    /**
     * 把整篇小说切分为章节。
     *
     * @param novelText 小说全文（UTF-8）；为 {@code null} 或纯空白时返回空列表
     * @return 有序章节列表（index 从 1 递增）；无标题时为单元素「全文」列表
     */
    public List<Chapter> split(String novelText) {
        List<Chapter> chapters = new ArrayList<>();
        if (novelText == null) {
            return chapters;
        }
        // 规范化换行：\r\n 与孤立 \r 统一为 \n
        String norm = novelText.replace("\r\n", "\n").replace('\r', '\n');
        if (norm.isBlank()) {
            return chapters;
        }

        // 保留尾部空行（limit = -1），便于按行号精确还原每章正文
        String[] lines = norm.split("\n", -1);

        // 收集所有标题行的位置及其语种（语种用于无标题文本时生成兜底标题）
        List<Integer> headingLine = new ArrayList<>();
        List<Boolean> headingIsCn = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (CN_HEADING.matcher(lines[i]).find()) {
                headingLine.add(i);
                headingIsCn.add(true);
            } else if (EN_HEADING.matcher(lines[i]).find()) {
                headingLine.add(i);
                headingIsCn.add(false);
            }
        }

        // 全篇无标题：整篇作为单章，交由上层判断章节数是否达标
        if (headingLine.isEmpty()) {
            chapters.add(new Chapter(1, "全文", norm.trim()));
            return chapters;
        }

        // 逐个标题切块：正文为「本标题行之后、下一标题行之前」的内容
        for (int k = 0; k < headingLine.size(); k++) {
            int start = headingLine.get(k);
            int end = (k + 1 < headingLine.size()) ? headingLine.get(k + 1) : lines.length;

            String titleLine = lines[start].trim();

            StringBuilder body = new StringBuilder();
            for (int i = start + 1; i < end; i++) {
                body.append(lines[i]);
                if (i < end - 1) {
                    body.append('\n');
                }
            }

            int index = k + 1;
            // 标题取整行 trim；极端情况下标题行无文本时按语种生成「第N章」/「Chapter N」
            String title = titleLine.isEmpty()
                    ? (headingIsCn.get(k) ? "第" + index + "章" : "Chapter " + index)
                    : titleLine;

            chapters.add(new Chapter(index, title, body.toString().trim()));
        }
        return chapters;
    }

    /**
     * 判断章节数是否达到改编门槛（{@link #MIN_CHAPTERS}）。
     *
     * @param chapters 章节列表（{@code null} 视为不达标）
     * @return 章节数 ≥ {@link #MIN_CHAPTERS} 时为 {@code true}
     */
    public static boolean hasEnoughChapters(List<?> chapters) {
        return chapters != null && chapters.size() >= MIN_CHAPTERS;
    }

    /**
     * 把超长文本按段落边界切成不超过 {@code maxChars} 的块，供超长章节分块送入 LLM 时使用。
     *
     * <p>优先在段落边界（换行）处切分；单个段落仍超长时退化到句子边界（。！？!?；; 等）；
     * 单句仍超长时才按 {@code maxChars} 硬切（最后兜底）。每块经 {@code strip}，跳过空白块，
     * 因此不会在句子中间硬截断。
     *
     * @param text     待切分文本（{@code null} 返回空列表）
     * @param maxChars 单块最大字符数（≤0 时直接整篇作为单块返回）
     * @return 顺序块列表
     */
    public static List<String> chunk(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        String norm = text.replace("\r\n", "\n").replace('\r', '\n');
        if (maxChars <= 0 || norm.length() <= maxChars) {
            if (!norm.isBlank()) {
                chunks.add(norm.strip());
            }
            return chunks;
        }

        StringBuilder buf = new StringBuilder();
        // 段落边界：在每个换行之后切一刀，并把换行保留在前一段末尾以便无损重组
        for (String para : norm.split("(?<=\n)")) {
            if (para.isEmpty()) {
                continue;
            }
            if (para.length() > maxChars) {
                // 超长段落：进一步按句子边界切，避免句中硬截断
                for (String sentence : splitBySentence(para, maxChars)) {
                    pack(buf, sentence, maxChars, chunks);
                }
            } else {
                pack(buf, para, maxChars, chunks);
            }
        }
        flush(buf, chunks);
        return chunks;
    }

    /** 把一个超长段落按句末标点切成若干 ≤ maxChars 的片段；单句仍超长则硬切兜底。 */
    private static List<String> splitBySentence(String paragraph, int maxChars) {
        List<String> pieces = new ArrayList<>();
        // 在句末标点（中英文句号/问号/叹号/分号）之后切分，标点随前句保留
        for (String sentence : paragraph.split("(?<=[。！？!?；;])")) {
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() <= maxChars) {
                pieces.add(sentence);
            } else {
                // 极端：单句仍超长，按 maxChars 硬切（最后兜底）
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    pieces.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
            }
        }
        return pieces;
    }

    /** 把片段塞入缓冲；若塞入后超限则先把缓冲落盘成一块再塞。片段长度已保证 ≤ maxChars。 */
    private static void pack(StringBuilder buf, String piece, int maxChars, List<String> chunks) {
        if (buf.length() > 0 && buf.length() + piece.length() > maxChars) {
            flush(buf, chunks);
        }
        buf.append(piece);
    }

    /** 把缓冲 strip 后作为一块加入结果（空白块跳过），并清空缓冲。 */
    private static void flush(StringBuilder buf, List<String> chunks) {
        String s = buf.toString().strip();
        if (!s.isEmpty()) {
            chunks.add(s);
        }
        buf.setLength(0);
    }
}
