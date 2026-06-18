package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.dto.ScenarioDTO;
import com.smartquery.entity.Scenario;
import com.smartquery.service.PromptTemplateService;
import com.smartquery.service.ScenarioService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 场景管理控制器
 */
@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    @Autowired
    private ScenarioService scenarioService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    /**
     * 获取所有场景
     */
    @GetMapping
    public Result<List<ScenarioDTO>> list() {
        List<Scenario> scenarios = scenarioService.getEnabledScenarios();
        List<ScenarioDTO> dtos = scenarios.stream().map(this::convertToDTO).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 获取系统预设场景
     */
    @GetMapping("/system")
    public Result<List<ScenarioDTO>> getSystemScenarios() {
        List<Scenario> scenarios = scenarioService.getSystemScenarios();
        List<ScenarioDTO> dtos = scenarios.stream().map(this::convertToDTO).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 根据ID获取场景
     */
    @GetMapping("/{id}")
    public Result<ScenarioDTO> getById(@PathVariable Long id) {
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 根据编码获取场景
     */
    @GetMapping("/code/{code}")
    public Result<ScenarioDTO> getByCode(@PathVariable String code) {
        Scenario scenario = scenarioService.getByCode(code);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 根据编码获取场景提示词
     */
    @GetMapping("/code/{code}/prompt")
    public Result<String> getPromptByCode(@PathVariable String code) {
        Scenario scenario = scenarioService.getByCode(code);
        if (scenario == null) {
            return Result.error("场景不存在");
        }

        var promptTemplate = promptTemplateService.getDefaultPrompt(scenario.getId());
        if (promptTemplate == null || !promptTemplate.getIsEnabled()) {
            return Result.error("场景暂无可用提示词");
        }

        return Result.ok(promptTemplate.getContent());
    }

    /**
     * 创建场景
     */
    @PostMapping
    public Result<ScenarioDTO> create(@RequestBody ScenarioDTO dto) {
        Scenario scenario = new Scenario();
        BeanUtils.copyProperties(dto, scenario);
        scenario.setIsSystem(false);
        scenario.setIsEnabled(true);
        scenarioService.save(scenario);
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 更新场景
     */
    @PutMapping("/{id}")
    public Result<ScenarioDTO> update(@PathVariable Long id, @RequestBody ScenarioDTO dto) {
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        BeanUtils.copyProperties(dto, scenario, "id", "createdAt", "isSystem");
        scenarioService.updateById(scenario);
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 删除场景
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        if (scenario.getIsSystem()) {
            return Result.error("系统预设场景不能删除");
        }
        scenarioService.removeById(id);
        return Result.ok();
    }

    private ScenarioDTO convertToDTO(Scenario scenario) {
        ScenarioDTO dto = new ScenarioDTO();
        BeanUtils.copyProperties(scenario, dto);
        return dto;
    }
}