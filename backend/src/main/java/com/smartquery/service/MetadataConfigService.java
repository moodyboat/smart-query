package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartquery.entity.MetadataConfig;
import com.smartquery.mapper.MetadataConfigMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 元数据配置服务
 */
@Service
public class MetadataConfigService extends ServiceImpl<MetadataConfigMapper, MetadataConfig> {

    /**
     * 根据数据源ID获取元数据配置
     */
    public List<MetadataConfig> getByDataSourceId(Long dataSourceId) {
        LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetadataConfig::getDataSourceId, dataSourceId);
        return list(wrapper);
    }

    /**
     * 根据表名获取元数据配置
     */
    public List<MetadataConfig> getByTableName(String tableName) {
        LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetadataConfig::getTableName, tableName);
        return list(wrapper);
    }

    /**
     * 获取表的元数据配置
     */
    public List<MetadataConfig> getTableMetadata(Long dataSourceId, String tableName) {
        LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetadataConfig::getDataSourceId, dataSourceId)
                .eq(MetadataConfig::getTableName, tableName)
                .in(MetadataConfig::getConfigType, "table", "column");
        return list(wrapper);
    }

    /**
     * 获取业务术语列表
     */
    public List<MetadataConfig> getBusinessTerms(Long dataSourceId) {
        LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetadataConfig::getDataSourceId, dataSourceId)
                .eq(MetadataConfig::getConfigType, "business_term");
        return list(wrapper);
    }

    /**
     * 根据配置类型获取元数据
     */
    public List<MetadataConfig> getByConfigType(String configType) {
        LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetadataConfig::getConfigType, configType);
        return list(wrapper);
    }
}