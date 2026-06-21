package com.smartquery.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.smartquery.dto.ScenarioDTO;
import com.smartquery.entity.Scenario;
import com.smartquery.service.PromptTemplateService;
import com.smartquery.service.RoleScenarioService;
import com.smartquery.service.ScenarioAuthService;
import com.smartquery.service.ScenarioService;
import com.smartquery.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 场景管理控制器
 *
 * <p>权限规则：
 * <ul>
 *   <li>GET /scenarios：按当前用户角色过滤（admin 全量，其他角色仅授权场景）</li>
 *   <li>CRUD + 角色授权：仅 admin（依赖 UserService.requireAdmin）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    @Autowired
    private ScenarioService scenarioService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private RoleScenarioService roleScenarioService;

    @Autowired
    private ScenarioAuthService scenarioAuthService;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 获取当前用户可用的场景（按角色过滤）
     */
    @GetMapping
    public Result<List<ScenarioDTO>> list() {
        String role = currentRole();
        List<Scenario> scenarios = ScenarioAuthService.ROLE_ADMIN.equals(role)
            ? scenarioService.getEnabledScenarios()
            : scenarioService.getEnabledScenariosByIds(roleScenarioService.getScenarioIdsByRole(role));
        List<ScenarioDTO> dtos = scenarios.stream().map(this::convertToDTO).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 获取系统预设场景（admin 用）
     */
    @GetMapping("/system")
    public Result<List<ScenarioDTO>> getSystemScenarios() {
        userService.requireAdmin();
        List<Scenario> scenarios = scenarioService.getSystemScenarios();
        List<ScenarioDTO> dtos = scenarios.stream().map(this::convertToDTO).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * admin 获取全部场景（含禁用），供场景管理页使用
     */
    @GetMapping("/admin/all")
    public Result<List<ScenarioDTO>> getAllForAdmin() {
        userService.requireAdmin();
        List<Scenario> scenarios = scenarioService.list();
        List<ScenarioDTO> dtos = scenarios.stream().map(this::convertToDTO).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 根据ID获取场景（校验访问权限）
     */
    @GetMapping("/{id}")
    public Result<ScenarioDTO> getById(@PathVariable Long id) {
        if (!scenarioAuthService.canAccessById(currentRole(), id)) {
            return Result.error(403, "无权访问该场景");
        }
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 根据编码获取场景（不校验权限，便于前端展示公开信息；提示词使用由 Chat 流程校验）
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
     * 根据编码获取场景提示词（校验权限，防止越权读取敏感业务提示词）
     */
    @GetMapping("/code/{code}/prompt")
    public Result<String> getPromptByCode(@PathVariable String code) {
        if (!scenarioAuthService.canAccess(currentRole(), code)) {
            return Result.error(403, "无权访问该场景提示词");
        }
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
     * 查询某场景已授权的角色列表（admin 用）
     */
    @GetMapping("/{id}/roles")
    public Result<List<String>> getRolesByScenario(@PathVariable Long id) {
        userService.requireAdmin();
        return Result.ok(roleScenarioService.getRolesByScenario(id));
    }

    /**
     * 批量设置某场景的角色授权（admin 用，覆盖式）
     */
    @PutMapping("/{id}/roles")
    public Result<Void> setScenarioRoles(@PathVariable Long id, @RequestBody java.util.Map<String, List<String>> body) {
        userService.requireAdmin();
        List<String> roles = body.get("roles");
        roleScenarioService.setScenarioRoles(id, roles);
        return Result.ok();
    }

    /**
     * 创建场景（admin 用）
     */
    @PostMapping
    public Result<ScenarioDTO> create(@RequestBody ScenarioDTO dto) {
        userService.requireAdmin();
        Scenario scenario = new Scenario();
        BeanUtils.copyProperties(dto, scenario, "uiConfig", "promptTemplates");
        serializeUiConfig(dto, scenario);
        scenario.setIsSystem(false);
        scenario.setIsEnabled(true);
        scenarioService.save(scenario);
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 更新场景（admin 用，系统预设场景只允许改部分字段）
     */
    @PutMapping("/{id}")
    public Result<ScenarioDTO> update(@PathVariable Long id, @RequestBody ScenarioDTO dto) {
        userService.requireAdmin();
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        BeanUtils.copyProperties(dto, scenario, "id", "createdAt", "isSystem", "uiConfig", "promptTemplates");
        serializeUiConfig(dto, scenario);
        scenarioService.updateById(scenario);
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 删除场景（admin 用，系统预设场景不能删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.requireAdmin();
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        if (scenario.getIsSystem()) {
            return Result.error("系统预设场景不能删除");
        }
        scenarioService.removeById(id);
        roleScenarioService.setScenarioRoles(id, java.util.Collections.emptyList());
        return Result.ok();
    }

    private String currentRole() {
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        return ctx == null ? null : ctx.role();
    }

    private void serializeUiConfig(ScenarioDTO dto, Scenario scenario) {
        if (dto == null || dto.getUiConfig() == null) {
            return;
        }
        try {
            scenario.setUiConfig(objectMapper.writeValueAsString(dto.getUiConfig()));
        } catch (Exception e) {
            throw new IllegalArgumentException("UI 配置序列化失败: " + e.getMessage());
        }
    }

    private ScenarioDTO convertToDTO(Scenario scenario) {
        ScenarioDTO dto = new ScenarioDTO();
        BeanUtils.copyProperties(scenario, dto, "uiConfig");

        if (scenario.getUiConfig() != null && !scenario.getUiConfig().isBlank()) {
            try {
                dto.setUiConfig(objectMapper.readValue(scenario.getUiConfig(),
                    new TypeReference<ScenarioDTO.ScenarioUiConfig>() {}));
            } catch (Exception e) {
                // 旧数据 uiConfig 格式不符则忽略，保证列表不挂
            }
        }
        return dto;
    }
}
