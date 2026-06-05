package com.scriptforge.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 内置示例库 —— 为首页「内置示例」一键载入与离线 demo 提供原创样本文本。
 *
 * <p>样本以 UTF-8 文本存放于 classpath 的 {@code samples/} 目录下，每篇恰好 3 章、
 * 含中文/英文引号对白，便于规则桩（stub）抽出场景与对白，无需任何外部 API Key 即可演示。
 * 前端首页用 {@code data-sample} 值 {@code "huozhe"} / {@code "gift"} 引用对应样本。
 */
@Component
public class SampleLibrary {

    /** classpath 下样本目录前缀。 */
    private static final String DIR = "samples/";

    /**
     * 示例条目元信息（供前端列表展示与一键载入）。
     *
     * @param id          稳定标识，亦为前端 {@code data-sample} 值：{@code huozhe} / {@code gift}
     * @param title       展示标题
     * @param language    语言：{@code zh} / {@code en}
     * @param description 一句话简述
     * @param chapters    章节数
     */
    public record SampleInfo(String id, String title, String language, String description, int chapters) {
    }

    /**
     * 列出全部内置示例（顺序固定：先中文《活着》改编，后英文 The Gift）。
     *
     * @return 两条示例元信息
     */
    public List<SampleInfo> list() {
        return List.of(
                new SampleInfo("huozhe", "《活着》改编·节选", "zh",
                        "苦难年代乡土风格的原创同人短篇：阔少福贵赌输祖业，与妻家珍重拾田间生计。", 3),
                new SampleInfo("gift", "The Gift", "en",
                        "An original short story: Daniel returns to a harbor town to make amends with Clara.", 3)
        );
    }

    /**
     * 按 id 读取对应样本全文（UTF-8）。
     *
     * @param id 示例标识（{@code huozhe} / {@code gift}）
     * @return 样本文本
     * @throws IllegalArgumentException id 无对应样本时
     * @throws UncheckedIOException     样本文件存在性异常时（classpath 资源读取失败）
     */
    public String load(String id) {
        String file = switch (id == null ? "" : id) {
            case "huozhe" -> "huozhe.txt";
            case "gift" -> "the-gift.txt";
            default -> throw new IllegalArgumentException("未知的内置示例 id：" + id);
        };
        ClassPathResource resource = new ClassPathResource(DIR + file);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取内置示例失败：" + file, e);
        }
    }
}
