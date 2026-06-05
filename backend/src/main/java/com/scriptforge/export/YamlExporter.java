package com.scriptforge.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.springframework.stereotype.Component;

import com.scriptforge.model.Screenplay;

/**
 * 剧本数据与 YAML 文本之间的互转（{@link Screenplay} ⇄ YAML）。
 *
 * <p>YAML 是本项目对外的输出契约格式（详见 {@code docs/yaml-schema-design.md}）：
 * 可机器校验、可人手编辑、可 git diff。本类持有一个<strong>专用</strong> {@link ObjectMapper}
 * （而非 Spring 容器里那个面向 REST/JSON 的实例），原因是它要绑定 {@link YAMLFactory}
 * 并施加若干「让 YAML 更干净易读」的写出选项，与默认 JSON 序列化器互不干扰。
 *
 * <p>序列化策略与全局保持一致——{@code SNAKE_CASE}（camelCase 字段→snake_case）
 * 与 {@code NON_EMPTY}（空集合/空串省略），从而 record 里默认成空集合的字段不会污染输出。
 * 另外关闭文档起始标记（不输出顶部的 {@code ---}）、开启最小化引号（标量尽量不加引号），
 * 使产出贴近设计文档与前端原型里的 YAML 写法。
 *
 * <p>记录类型（record）的反/序列化由 Jackson 原生支持（2.12+，按 record 组件名解析规范构造器），
 * 无需额外注册 parameter-names 模块。
 *
 * <p>线程安全：{@link ObjectMapper} 配置完成后只读使用，可被多请求线程并发调用。
 */
@Component
public class YamlExporter {

    /** 专用 YAML 映射器：绑定 YAMLFactory 并按本项目约定配置命名/省略/引号策略。 */
    private final ObjectMapper mapper;

    public YamlExporter() {
        YAMLFactory yamlFactory = new YAMLFactory()
                // 不在文档顶部输出「---」起始标记，YAML 更干净
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                // 标量尽量不加引号，贴近手写 YAML，更易读
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        this.mapper = new ObjectMapper(yamlFactory)
                // 与全局一致：camelCase 字段序列化为 snake_case
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // 与全局一致：省略空集合/空字符串，输出保持干净
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                // 反序列化容错：来自前端/LLM 的 YAML 偶有多余字段，不应因此整体失败
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 把剧本对象序列化为 YAML 文本。
     *
     * @param sp 剧本对象
     * @return 符合输出契约的 YAML 文本
     * @throws IllegalStateException 序列化失败时（属编程/数据错误，包装为非受检异常）
     */
    public String toYaml(Screenplay sp) {
        try {
            return mapper.writeValueAsString(sp);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("剧本序列化为 YAML 失败", e);
        }
    }

    /**
     * 把 YAML 文本反序列化为剧本对象。
     *
     * @param yaml YAML 文本
     * @return 剧本对象
     * @throws IllegalStateException 解析失败时（包装为非受检异常，便于上层统一处理）
     */
    public Screenplay fromYaml(String yaml) {
        try {
            return mapper.readValue(yaml, Screenplay.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("YAML 反序列化为剧本失败", e);
        }
    }
}
