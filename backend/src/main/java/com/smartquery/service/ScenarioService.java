package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartquery.entity.Scenario;
import com.smartquery.mapper.ScenarioMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 场景服务
 */
@Service
public class ScenarioService extends ServiceImpl<ScenarioMapper, Scenario> {

    /**
     * 获取所有启用的场景
     */
    public List<Scenario> getEnabledScenarios() {
        LambdaQueryWrapper<Scenario> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Scenario::getIsEnabled, true)
                .orderByAsc(Scenario::getSortOrder)
                .orderByDesc(Scenario::getCreatedAt);
        return list(wrapper);
    }

    /**
     * 根据编码获取场景
     */
    public Scenario getByCode(String code) {
        LambdaQueryWrapper<Scenario> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Scenario::getCode, code);
        return getOne(wrapper);
    }

    /**
     * 获取系统预设场景
     */
    public List<Scenario> getSystemScenarios() {
        LambdaQueryWrapper<Scenario> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Scenario::getIsSystem, true)
                .eq(Scenario::getIsEnabled, true)
                .orderByAsc(Scenario::getSortOrder);
        return list(wrapper);
    }
}