package com.smartquery.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.smartquery.dto.ScenarioDTO;
import com.smartquery.entity.Scenario;
import com.smartquery.service.PromptTemplateService;
import com.smartquery.service.RoleScenarioService;
import com.smartquery.common.PermissionCodes;
import com.smartquery.service.RoleService;
import com.smartquery.service.ScenarioAuthService;
import com.smartquery.service.ScenarioService;
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
 *   <li>GET /scenarios：按当前用户角色授权过滤；具备场景管理权限时可查看全量</li>
 *   <li>CRUD + 角色授权：需要数据库配置的场景管理权限</li>
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
    private RoleService roleService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 获取当前用户可用的场景（按角色过滤）
     */
    @GetMapping
    public Result<List<ScenarioDTO>> list() {
        String role = currentRole();
        List<Scenario> scenarios = roleService.hasPermission(role, PermissionCodes.SCENARIO_MANAGE)
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
        requireScenarioManager();
        List<Scenario> scenarios = scenarioService.getSystemScenarios();
        List<ScenarioDTO> dtos = scenarios.stream().map(this::convertToDTO).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * admin 获取全部场景（含禁用），供场景管理页使用
     */
    @GetMapping("/admin/all")
    public Result<List<ScenarioDTO>> getAllForAdmin() {
        requireScenarioManager();
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
        requireScenarioManager();
        return Result.ok(roleScenarioService.getRolesByScenario(id));
    }

    /**
     * 批量设置某场景的角色授权（admin 用，覆盖式）
     */
    @PutMapping("/{id}/roles")
    public Result<Void> setScenarioRoles(@PathVariable Long id, @RequestBody java.util.Map<String, List<String>> body) {
        requireScenarioManager();
        List<String> roles = body.get("roles");
        roleScenarioService.setScenarioRoles(id, roles);
        return Result.ok();
    }

    /**
     * 创建场景（admin 用）
     */
    @PostMapping
    public Result<ScenarioDTO> create(@RequestBody ScenarioDTO dto) {
        requireScenarioManager();
        Scenario scenario = new Scenario();
        BeanUtils.copyProperties(dto, scenario, "uiConfig", "promptTemplates", "allowedTableList");
        serializeUiConfig(dto, scenario);
        serializeAllowedTables(dto, scenario);
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
        requireScenarioManager();
        Scenario scenario = scenarioService.getById(id);
        if (scenario == null) {
            return Result.error("场景不存在");
        }
        BeanUtils.copyProperties(dto, scenario, "id", "createdAt", "isSystem", "uiConfig", "promptTemplates", "allowedTableList");
        serializeUiConfig(dto, scenario);
        serializeAllowedTables(dto, scenario);
        scenarioService.updateById(scenario);
        return Result.ok(convertToDTO(scenario));
    }

    /**
     * 删除场景（admin 用，系统预设场景不能删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireScenarioManager();
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

    private void requireScenarioManager() {
        roleService.requireCurrentUser(PermissionCodes.SCENARIO_MANAGE, "无权限管理业务场景");
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

    /**
     * 将 DTO 的 allowedTableList (List<String>) join 成 allowedTables (CSV 字符串) 写入实体。
     * 空列表或 null 写入 null（语义=不限表）。
     */
    private void serializeAllowedTables(ScenarioDTO dto, Scenario scenario) {
        if (dto == null || dto.getAllowedTableList() == null || dto.getAllowedTableList().isEmpty()) {
            scenario.setAllowedTables(null);
            return;
        }
        scenario.setAllowedTables(dto.getAllowedTableList().stream()
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(",")));
    }

    private ScenarioDTO convertToDTO(Scenario scenario) {
        ScenarioDTO dto = new ScenarioDTO();
        BeanUtils.copyProperties(scenario, dto, "uiConfig", "allowedTables");

        if (scenario.getUiConfig() != null && !scenario.getUiConfig().isBlank()) {
            try {
                dto.setUiConfig(objectMapper.readValue(scenario.getUiConfig(),
                    new TypeReference<ScenarioDTO.ScenarioUiConfig>() {}));
            } catch (Exception e) {
                // 旧数据 uiConfig 格式不符则忽略，保证列表不挂
            }
        }

        // CSV → List<String>（前端友好）；空字符串 → 空列表（语义=不限表）
        if (scenario.getAllowedTables() != null && !scenario.getAllowedTables().isBlank()) {
            dto.setAllowedTableList(java.util.Arrays.stream(scenario.getAllowedTables().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList()));
        } else {
            dto.setAllowedTableList(java.util.Collections.emptyList());
        }
        return dto;
    }
}
