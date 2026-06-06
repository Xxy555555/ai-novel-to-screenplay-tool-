package com.scriptforge.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scriptforge.export.FountainRenderer;
import com.scriptforge.export.YamlExporter;
import com.scriptforge.model.QualityReport;
import com.scriptforge.model.Screenplay;
import com.scriptforge.pipeline.QualityReporter;
import com.scriptforge.pipeline.SampleLibrary;
import com.scriptforge.schema.SchemaValidator;

/**
 * 工作台接口：内置示例列表、取剧本（JSON/YAML/Fountain）、重校验。
 */
@RestController
@RequestMapping("/api")
public class WorkbenchController {

    private final GenerationService generation;
    private final SampleLibrary samples;
    private final YamlExporter yamlExporter;
    private final FountainRenderer fountainRenderer;
    private final SchemaValidator validator;
    private final QualityReporter quality;
    private final ObjectMapper mapper;

    public WorkbenchController(GenerationService generation, SampleLibrary samples, YamlExporter yamlExporter,
                              FountainRenderer fountainRenderer, SchemaValidator validator,
                              QualityReporter quality, ObjectMapper mapper) {
        this.generation = generation;
        this.samples = samples;
        this.yamlExporter = yamlExporter;
        this.fountainRenderer = fountainRenderer;
        this.validator = validator;
        this.quality = quality;
        this.mapper = mapper;
    }

    @GetMapping("/samples")
    public List<SampleLibrary.SampleInfo> samples() {
        return samples.list();
    }

    @GetMapping("/screenplay/{id}")
    public Screenplay screenplay(@PathVariable String id) {
        return require(id);
    }

    @GetMapping(value = "/screenplay/{id}/yaml", produces = "text/plain;charset=UTF-8")
    public String yaml(@PathVariable String id) {
        return yamlExporter.toYaml(require(id));
    }

    @GetMapping(value = "/screenplay/{id}/fountain", produces = "text/plain;charset=UTF-8")
    public String fountain(@PathVariable String id) {
        return fountainRenderer.render(require(id));
    }

    /** 重校验请求：直接传当前编辑中的 YAML。 */
    public record ValidateRequest(String yaml) {}

    public record ValidateResponse(boolean valid, int errorCount, List<String> errors, QualityReport report) {}

    @PostMapping("/validate")
    public ValidateResponse validate(@RequestBody ValidateRequest req) {
        Screenplay sp;
        try {
            sp = yamlExporter.fromYaml(req.yaml() == null ? "" : req.yaml());
        } catch (Exception e) {
            return new ValidateResponse(false, 1, List.of("YAML 解析失败：" + e.getMessage()), null);
        }
        List<String> errors = validator.validate(mapper.valueToTree(sp));
        QualityReport report = quality.report(sp, errors.size());
        return new ValidateResponse(errors.isEmpty(), errors.size(), errors, report);
    }

    private Screenplay require(String id) {
        Screenplay sp = generation.result(id);
        if (sp == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "剧本不存在或尚未生成完成：" + id);
        }
        return sp;
    }
}
