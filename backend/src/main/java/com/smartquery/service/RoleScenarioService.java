package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartquery.entity.RoleScenario;
import com.smartquery.mapper.RoleScenarioMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色-场景授权服务
 *
 * <p>角色字段与 sq_user.role 对齐（字符串，当前默认 admin/user）；
 * 不引入独立角色表，避免过度设计。
 */
@Service
public class RoleScenarioService extends ServiceImpl<RoleScenarioMapper, RoleScenario> {

    /**
     * 查询某角色被授权的所有场景 ID
     */
    public List<Long> getScenarioIdsByRole(String role) {
        if (role == null || role.isBlank()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<RoleScenario> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleScenario::getRole, role);
        return list(wrapper).stream().map(RoleScenario::getScenarioId).collect(Collectors.toList());
    }

    /**
     * 查询某场景已授权的所有角色
     */
    public List<String> getRolesByScenario(Long scenarioId) {
        if (scenarioId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<RoleScenario> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleScenario::getScenarioId, scenarioId);
        return list(wrapper).stream().map(RoleScenario::getRole).collect(Collectors.toList());
    }

    /**
     * 批量设置某场景的角色授权（覆盖式：旧角色不在列表中则撤销）
     */
    @Transactional
    public void setScenarioRoles(Long scenarioId, Collection<String> roles) {
        // 清空旧授权
        LambdaQueryWrapper<RoleScenario> clearWrapper = new LambdaQueryWrapper<>();
        clearWrapper.eq(RoleScenario::getScenarioId, scenarioId);
        remove(clearWrapper);

        if (roles == null || roles.isEmpty()) {
            return;
        }

        // 去重后插入
        List<String> distinctRoles = roles.stream()
            .filter(r -> r != null && !r.isBlank())
            .distinct()
            .collect(Collectors.toList());

        List<RoleScenario> toInsert = new ArrayList<>();
        for (String role : distinctRoles) {
            RoleScenario rs = new RoleScenario();
            rs.setRole(role);
            rs.setScenarioId(scenarioId);
            toInsert.add(rs);
        }
        if (!toInsert.isEmpty()) {
            saveBatch(toInsert);
        }
    }
}
