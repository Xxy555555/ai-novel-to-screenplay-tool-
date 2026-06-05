package com.scriptforge.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Screenplay JSON Schema 校验器（基于 networknt json-schema-validator，draft-07）。
 *
 * <p>本类是「保证 Schema 合法的 YAML」这一输出契约的判据：管线生成的剧本数据先经此校验，
 * 不合法即交由 AutoRepair 回喂 LLM 修复。Schema 文件为 classpath 下的
 * {@code screenplay.schema.json}，在构造时一次性加载并缓存编译后的 {@link JsonSchema}，
 * 后续校验复用，避免重复解析。
 *
 * <p>线程安全：{@link JsonSchema} 与 {@link YAMLMapper} 在加载后均只读使用，
 * 可被多个请求线程并发调用。
 */
@Component
public class SchemaValidator {

    /** classpath 下的 Schema 资源名。 */
    private static final String SCHEMA_RESOURCE = "screenplay.schema.json";

    /** 编译并缓存的 Schema（draft-07），构造时加载一次。 */
    private final JsonSchema schema;

    /** 解析 YAML 文本为 {@link JsonNode}，供 {@link #validateYaml(String)} 使用。 */
    private final YAMLMapper yamlMapper = new YAMLMapper();

    /**
     * 从 classpath 加载并编译 Schema。
     *
     * <p>Schema 缺失或不可解析属致命的配置错误，直接抛出 {@link IllegalStateException}
     * 让应用启动失败（fail-fast），而非把损坏的校验器交付给上层。
     */
    public SchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            this.schema = factory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 Schema 资源: " + SCHEMA_RESOURCE, e);
        }
    }

    /**
     * 校验一个 {@link JsonNode} 是否符合 Schema。
     *
     * @param node 待校验的 JSON 节点
     * @return 人类可读的错误信息列表；列表为空表示完全合法
     */
    public List<String> validate(JsonNode node) {
        if (node == null) {
            return List.of("待校验内容为空（null）");
        }
        Set<ValidationMessage> messages = schema.validate(node);
        List<String> errors = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            errors.add(message.getMessage());
        }
        return errors;
    }

    /**
     * 解析 YAML 文本后再按 Schema 校验。
     *
     * <p>YAML 是本项目对外的输出格式，故先用 {@link YAMLMapper} 转为 {@link JsonNode}
     * 再复用 {@link #validate(JsonNode)}。解析失败时返回单条「YAML 解析失败」错误，
     * 不抛异常，便于上层将「语法错误」与「结构不合法」统一当作可修复问题处理。
     *
     * @param yaml 待校验的 YAML 文本
     * @return 人类可读的错误信息列表；列表为空表示完全合法
     */
    public List<String> validateYaml(String yaml) {
        JsonNode node;
        try {
            node = yamlMapper.readTree(yaml);
        } catch (IOException e) {
            return List.of("YAML 解析失败: " + e.getMessage());
        }
        if (node == null || node.isMissingNode()) {
            return List.of("YAML 解析失败: 内容为空");
        }
        return validate(node);
    }

    /**
     * 便捷判断：节点是否完全符合 Schema。
     *
     * @param node 待校验的 JSON 节点
     * @return 合法返回 {@code true}
     */
    public boolean isValid(JsonNode node) {
        return validate(node).isEmpty();
    }
}
