package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartquery.entity.PromptTemplate;
import com.smartquery.mapper.PromptTemplateMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提示词模板服务
 */
@Service
public class PromptTemplateService extends ServiceImpl<PromptTemplateMapper, PromptTemplate> {

    /**
     * 根据场景ID获取提示词模板
     */
    public List<PromptTemplate> getByScenarioId(Long scenarioId) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getScenarioId, scenarioId)
                .eq(PromptTemplate::getIsEnabled, true)
                .orderByDesc(PromptTemplate::getIsDefault)
                .orderByDesc(PromptTemplate::getCreatedAt);
        return list(wrapper);
    }

    /**
     * 获取场景的默认提示词
     */
    public PromptTemplate getDefaultPrompt(Long scenarioId) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getScenarioId, scenarioId)
                .eq(PromptTemplate::getIsDefault, true)
                .eq(PromptTemplate::getIsEnabled, true)
                .eq(PromptTemplate::getType, "system")
                .last("LIMIT 1");
        return getOne(wrapper);
    }

    /**
     * 根据编码获取提示词
     */
    public PromptTemplate getByCode(String code) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getCode, code);
        return getOne(wrapper);
    }
}