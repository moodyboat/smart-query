package com.smartquery.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.dto.PromptTemplateDTO;
import com.smartquery.entity.PromptTemplate;
import com.smartquery.service.PromptTemplateService;
import com.smartquery.service.ScenarioAuthService;
import com.smartquery.service.ScenarioService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词模板管理控制器
 */
@RestController
@RequestMapping("/api/v1/prompt-templates")
public class PromptTemplateController {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private ScenarioService scenarioService;

    @Autowired
    private ScenarioAuthService scenarioAuthService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 获取所有提示词模板
     */
    @GetMapping
    public Result<List<PromptTemplateDTO>> list() {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getIsEnabled, true)
                .orderByDesc(PromptTemplate::getCreatedAt);
        List<PromptTemplate> templates = promptTemplateService.list(wrapper);
        List<PromptTemplateDTO> dtos = templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 根据场景ID获取提示词模板（普通用户仅可访问授权场景）
     */
    @GetMapping("/scenario/{scenarioId}")
    public Result<List<PromptTemplateDTO>> getByScenarioId(@PathVariable Long scenarioId) {
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        String role = ctx == null ? null : ctx.role();
        if (!scenarioAuthService.canAccessById(role, scenarioId)) {
            return Result.error(403, "无权访问该场景的提示词");
        }
        List<PromptTemplate> templates = promptTemplateService.getByScenarioId(scenarioId);
        List<PromptTemplateDTO> dtos = templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 根据场景编码获取默认提示词
     */
    @GetMapping("/default/scenario/{scenarioCode}")
    public Result<PromptTemplateDTO> getDefaultPromptByScenarioCode(@PathVariable String scenarioCode) {
        var scenario = scenarioService.getByCode(scenarioCode);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        PromptTemplate template = promptTemplateService.getDefaultPrompt(scenario.getId());
        if (template == null) {
            return Result.error("未找到默认提示词");
        }
        return Result.ok(convertToDTO(template));
    }

    /**
     * 根据ID获取提示词模板
     */
    @GetMapping("/{id}")
    public Result<PromptTemplateDTO> getById(@PathVariable Long id) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return Result.error("提示词模板不存在");
        }
        return Result.ok(convertToDTO(template));
    }

    /**
     * 创建提示词模板
     */
    @PostMapping
    public Result<PromptTemplateDTO> create(@RequestBody PromptTemplateDTO dto) {
        PromptTemplate template = new PromptTemplate();
        BeanUtils.copyProperties(dto, template, "variables", "modelConfig");
        template.setIsSystem(false);
        template.setIsEnabled(true);
        template.setIsDefault(false);

        // 序列化复杂对象
        try {
            if (dto.getVariables() != null) {
                template.setVariables(objectMapper.writeValueAsString(dto.getVariables()));
            }
            if (dto.getModelConfig() != null) {
                template.setModelConfig(objectMapper.writeValueAsString(dto.getModelConfig()));
            }
        } catch (Exception e) {
            return Result.error("序列化配置失败: " + e.getMessage());
        }

        promptTemplateService.save(template);
        return Result.ok(convertToDTO(template));
    }

    /**
     * 更新提示词模板
     */
    @PutMapping("/{id}")
    public Result<PromptTemplateDTO> update(@PathVariable Long id, @RequestBody PromptTemplateDTO dto) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return Result.error("提示词模板不存在");
        }
        if (template.getIsSystem()) {
            return Result.error("系统预设模板不能修改");
        }

        BeanUtils.copyProperties(dto, template, "id", "createdAt", "isSystem", "variables", "modelConfig");

        // 序列化复杂对象
        try {
            if (dto.getVariables() != null) {
                template.setVariables(objectMapper.writeValueAsString(dto.getVariables()));
            }
            if (dto.getModelConfig() != null) {
                template.setModelConfig(objectMapper.writeValueAsString(dto.getModelConfig()));
            }
        } catch (Exception e) {
            return Result.error("序列化配置失败: " + e.getMessage());
        }

        promptTemplateService.updateById(template);
        return Result.ok(convertToDTO(template));
    }

    /**
     * 删除提示词模板
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return Result.error("提示词模板不存在");
        }
        if (template.getIsSystem()) {
            return Result.error("系统预设模板不能删除");
        }
        promptTemplateService.removeById(id);
        return Result.ok();
    }

    /**
     * 设置为默认模板
     */
    @PutMapping("/{id}/set-default")
    public Result<Void> setDefault(@PathVariable Long id) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return Result.error("提示词模板不存在");
        }

        // 取消该场景下的其他默认模板
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getScenarioId, template.getScenarioId())
                .eq(PromptTemplate::getIsDefault, true);
        List<PromptTemplate> defaults = promptTemplateService.list(wrapper);
        defaults.forEach(t -> {
            t.setIsDefault(false);
            promptTemplateService.updateById(t);
        });

        // 设置当前模板为默认
        template.setIsDefault(true);
        promptTemplateService.updateById(template);

        return Result.ok();
    }

    private PromptTemplateDTO convertToDTO(PromptTemplate template) {
        PromptTemplateDTO dto = new PromptTemplateDTO();
        BeanUtils.copyProperties(template, dto, "variables", "modelConfig");

        // 反序列化复杂对象
        try {
            if (template.getVariables() != null && !template.getVariables().isEmpty()) {
                dto.setVariables(objectMapper.readValue(template.getVariables(),
                        new TypeReference<List<PromptTemplateDTO.VariableConfig>>() {}));
            }
            if (template.getModelConfig() != null && !template.getModelConfig().isEmpty()) {
                dto.setModelConfig(objectMapper.readValue(template.getModelConfig(),
                        new TypeReference<PromptTemplateDTO.ModelConfig>() {}));
            }
        } catch (Exception e) {
            // 忽略反序列化错误
        }

        return dto;
    }
}