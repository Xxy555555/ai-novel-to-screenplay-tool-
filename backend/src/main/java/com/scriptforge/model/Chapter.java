package com.scriptforge.model;

/**
 * 章节切分（{@code ChapterSplitter}）的产物。管线内部数据契约。
 *
 * @param index   章节序号（从 1 开始）
 * @param title   章节标题（如「第二章」「Chapter 2」；无标题时为启发式生成）
 * @param content 该章正文（UTF-8 原文）
 */
public record Chapter(
        int index,
        String title,
        String content
) {
}
