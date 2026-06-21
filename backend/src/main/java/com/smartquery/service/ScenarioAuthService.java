package com.smartquery.service;

import com.smartquery.entity.Scenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 场景访问鉴权服务
 *
 * <p>规则：
 * <ul>
 *   <li>role == "admin"：直通，可访问所有场景</li>
 *   <li>其他角色：必须在 sq_role_scenario 表中有授权记录</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioAuthService {

    /** 管理员角色名（与 UserService.requireAdmin 对齐） */
    public static final String ROLE_ADMIN = "admin";

    private final RoleScenarioService roleScenarioService;
    private final ScenarioService scenarioService;

    /**
     * 校验角色是否有权访问某场景编码
     */
    public boolean canAccess(String role, String scenarioCode) {
        if (scenarioCode == null || scenarioCode.isBlank()) {
            return true; // 未指定场景视为通用对话，放行
        }
        if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        Scenario scenario = scenarioService.getByCode(scenarioCode);
        if (scenario == null) {
            log.warn("[SCENARIO-AUTH] 场景不存在: {}", scenarioCode);
            return false;
        }
        List<Long> allowedIds = roleScenarioService.getScenarioIdsByRole(role);
        return allowedIds.contains(scenario.getId());
    }

    /**
     * 校验角色是否有权访问某场景 ID
     */
    public boolean canAccessById(String role, Long scenarioId) {
        if (scenarioId == null) {
            return true;
        }
        if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        List<Long> allowedIds = roleScenarioService.getScenarioIdsByRole(role);
        return allowedIds.contains(scenarioId);
    }
}
