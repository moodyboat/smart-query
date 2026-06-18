package com.smartquery.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.smartquery.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.dto.MetadataConfigDTO;
import com.smartquery.entity.MetadataConfig;
import com.smartquery.service.MetadataConfigService;
import com.smartquery.datasource.DataSourceManager;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 元数据配置管理控制器
 */
@RestController
@RequestMapping("/api/v1/metadata")
public class MetadataConfigController {

    @Autowired
    private MetadataConfigService metadataConfigService;

    @Autowired
    private DataSourceManager dataSourceManager;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 根据数据源ID获取元数据配置
     */
    @GetMapping("/datasource/{dataSourceId}")
    public Result<List<MetadataConfigDTO>> getByDataSourceId(@PathVariable Long dataSourceId) {
        List<MetadataConfig> configs = metadataConfigService.getByDataSourceId(dataSourceId);
        List<MetadataConfigDTO> dtos = configs.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 获取表的元数据配置
     */
    @GetMapping("/datasource/{dataSourceId}/table/{tableName}")
    public Result<List<MetadataConfigDTO>> getTableMetadata(
            @PathVariable Long dataSourceId,
            @PathVariable String tableName) {
        List<MetadataConfig> configs = metadataConfigService.getTableMetadata(dataSourceId, tableName);
        List<MetadataConfigDTO> dtos = configs.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 获取业务术语列表
     */
    @GetMapping("/datasource/{dataSourceId}/terms")
    public Result<List<MetadataConfigDTO>> getBusinessTerms(@PathVariable Long dataSourceId) {
        List<MetadataConfig> configs = metadataConfigService.getBusinessTerms(dataSourceId);
        List<MetadataConfigDTO> dtos = configs.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 根据配置类型获取元数据
     */
    @GetMapping("/type/{configType}")
    public Result<List<MetadataConfigDTO>> getByConfigType(@PathVariable String configType) {
        List<MetadataConfig> configs = metadataConfigService.getByConfigType(configType);
        List<MetadataConfigDTO> dtos = configs.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 根据ID获取元数据配置
     */
    @GetMapping("/{id}")
    public Result<MetadataConfigDTO> getById(@PathVariable Long id) {
        MetadataConfig config = metadataConfigService.getById(id);
        if (config == null) {
            return Result.error("元数据配置不存在");
        }
        return Result.ok(convertToDTO(config));
    }

    /**
     * 创建元数据配置
     */
    @PostMapping
    public Result<MetadataConfigDTO> create(@RequestBody MetadataConfigDTO dto) {
        MetadataConfig config = new MetadataConfig();
        BeanUtils.copyProperties(dto, config, "aliases", "dictionary");

        // 序列化复杂对象
        try {
            if (dto.getAliases() != null && !dto.getAliases().isEmpty()) {
                config.setAliases(objectMapper.writeValueAsString(dto.getAliases()));
            }
            if (dto.getDictionary() != null) {
                config.setDictionary(objectMapper.writeValueAsString(dto.getDictionary()));
            }
        } catch (Exception e) {
            return Result.error("序列化配置失败: " + e.getMessage());
        }

        metadataConfigService.save(config);
        return Result.ok(convertToDTO(config));
    }

    /**
     * 更新元数据配置
     */
    @PutMapping("/{id}")
    public Result<MetadataConfigDTO> update(@PathVariable Long id, @RequestBody MetadataConfigDTO dto) {
        MetadataConfig config = metadataConfigService.getById(id);
        if (config == null) {
            return Result.error("元数据配置不存在");
        }

        BeanUtils.copyProperties(dto, config, "id", "createdAt", "aliases", "dictionary");

        // 序列化复杂对象
        try {
            if (dto.getAliases() != null) {
                config.setAliases(objectMapper.writeValueAsString(dto.getAliases()));
            }
            if (dto.getDictionary() != null) {
                config.setDictionary(objectMapper.writeValueAsString(dto.getDictionary()));
            }
        } catch (Exception e) {
            return Result.error("序列化配置失败: " + e.getMessage());
        }

        metadataConfigService.updateById(config);
        return Result.ok(convertToDTO(config));
    }

    /**
     * 删除元数据配置
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        MetadataConfig config = metadataConfigService.getById(id);
        if (config == null) {
            return Result.error("元数据配置不存在");
        }
        metadataConfigService.removeById(id);
        return Result.ok();
    }

    /**
     * 从表结构导入元数据配置
     */
    @PostMapping("/import/{dataSourceId}")
    public Result<List<MetadataConfigDTO>> importFromSchema(@PathVariable Long dataSourceId) {
        try {
            // 使用DataSourceManager获取JdbcTemplate
            var jdbc = dataSourceManager.getJdbcTemplate(dataSourceId);

            // 获取数据源的所有表
            List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT TABLE_NAME AS name, TABLE_COMMENT AS comment, TABLE_ROWS AS `rows` " +
                "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME");

            List<MetadataConfigDTO> importedConfigs = new ArrayList<>();
            int importedCount = 0;
            int skippedCount = 0;

            for (Map<String, Object> tableInfo : tables) {
                String tableName = (String) tableInfo.get("name");
                String tableComment = (String) tableInfo.get("comment");

                if (tableName == null || tableName.isEmpty()) {
                    continue;
                }

                // 首先检查表级别的配置是否已存在
                LambdaQueryWrapper<MetadataConfig> tableWrapper = new LambdaQueryWrapper<>();
                tableWrapper.eq(MetadataConfig::getDataSourceId, dataSourceId)
                          .eq(MetadataConfig::getTableName, tableName)
                          .eq(MetadataConfig::getConfigType, "table");

                MetadataConfig tableConfig = metadataConfigService.getOne(tableWrapper);

                // 如果表配置不存在，创建一个
                boolean tableConfigCreated = false;
                if (tableConfig == null) {
                    tableConfig = new MetadataConfig();
                    tableConfig.setDataSourceId(dataSourceId);
                    tableConfig.setTableName(tableName);
                    tableConfig.setColumnName(""); // 表级配置使用空字符串而不是null
                    tableConfig.setName(tableComment != null && !tableComment.isEmpty() ? tableComment : tableName);
                    tableConfig.setConfigType("table");
                    tableConfig.setDescription("表配置");
                    metadataConfigService.save(tableConfig);
                    tableConfigCreated = true;
                    importedConfigs.add(convertToDTO(tableConfig));
                }

                // 获取表的列信息
                List<Map<String, Object>> columns = jdbc.queryForList(
                    "SELECT COLUMN_NAME AS name, COLUMN_TYPE AS type, IS_NULLABLE AS nullable, " +
                    "COLUMN_KEY AS `key`, COLUMN_COMMENT AS comment " +
                    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                    tableName);

                for (Map<String, Object> columnInfo : columns) {
                    String columnName = (String) columnInfo.get("name");
                    String columnComment = (String) columnInfo.get("comment");

                    // 只导入有注释的字段，或者至少有字段名的
                    if (columnName != null && !columnName.isEmpty()) {

                        // 检查是否已存在该配置
                        LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
                        wrapper.eq(MetadataConfig::getDataSourceId, dataSourceId)
                               .eq(MetadataConfig::getTableName, tableName)
                               .eq(MetadataConfig::getColumnName, columnName);

                        MetadataConfig existing = metadataConfigService.getOne(wrapper);

                        if (existing == null) {
                            // 创建新的元数据配置
                            MetadataConfig config = new MetadataConfig();
                            config.setDataSourceId(dataSourceId);
                            config.setTableName(tableName);
                            config.setColumnName(columnName);
                            config.setName(columnComment != null && !columnComment.isEmpty() ? columnComment : columnName);
                            config.setConfigType("column"); // 使用正确的配置类型
                            config.setDescription("从表结构自动导入");

                            // 如果是枚举类型，尝试解析枚举值作为数据字典
                            String columnType = (String) columnInfo.get("type");
                            if (columnType != null && columnType.startsWith("enum")) {
                                List<MetadataConfigDTO.Dictionary> dictionary = parseEnumDictionary(columnType);
                                if (!dictionary.isEmpty()) {
                                    try {
                                        config.setDictionary(objectMapper.writeValueAsString(dictionary));
                                    } catch (Exception e) {
                                        // 忽略序列化错误
                                    }
                                }
                            }

                            metadataConfigService.save(config);
                            importedConfigs.add(convertToDTO(config));
                            importedCount++;
                        } else {
                            skippedCount++;
                        }
                    }
                }
            }

            return Result.ok(importedConfigs);

        } catch (Exception e) {
            return Result.error("导入失败：" + e.getMessage());
        }
    }

    /**
     * 清理指定数据源的所有元数据配置
     */
    @DeleteMapping("/datasource/{dataSourceId}/clear")
    public Result<Void> clearByDataSourceId(@PathVariable Long dataSourceId) {
        try {
            LambdaQueryWrapper<MetadataConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MetadataConfig::getDataSourceId, dataSourceId);
            metadataConfigService.remove(wrapper);
            return Result.ok();
        } catch (Exception e) {
            return Result.error("清理失败：" + e.getMessage());
        }
    }

    /**
     * 解析enum类型的字典
     */
    private List<MetadataConfigDTO.Dictionary> parseEnumDictionary(String enumType) {
        List<MetadataConfigDTO.Dictionary> dictionary = new ArrayList<>();

        try {
            // enum类型格式：enum('value1','value2','value3')
            String values = enumType.substring(5, enumType.length() - 1); // 去掉 "enum(" 和 ")"
            String[] enumValues = values.split(",");

            for (String value : enumValues) {
                String cleanValue = value.trim().replaceAll("'", "");
                if (!cleanValue.isEmpty()) {
                    MetadataConfigDTO.Dictionary dict = new MetadataConfigDTO.Dictionary();
                    dict.setValue(cleanValue);
                    dict.setLabel(cleanValue); // 默认使用值本身作为标签
                    dictionary.add(dict);
                }
            }
        } catch (Exception e) {
            // 解析失败时返回空列表
        }

        return dictionary;
    }

    /**
     * 批量保存元数据配置
     */
    @PostMapping("/batch")
    public Result<List<MetadataConfigDTO>> batchSave(@RequestBody List<MetadataConfigDTO> dtos) {
        List<MetadataConfig> configs = dtos.stream().map(dto -> {
            MetadataConfig config = new MetadataConfig();
            BeanUtils.copyProperties(dto, config, "aliases", "dictionary");
            try {
                if (dto.getAliases() != null && !dto.getAliases().isEmpty()) {
                    config.setAliases(objectMapper.writeValueAsString(dto.getAliases()));
                }
                if (dto.getDictionary() != null) {
                    config.setDictionary(objectMapper.writeValueAsString(dto.getDictionary()));
                }
            } catch (Exception e) {
                // 忽略序列化错误
            }
            return config;
        }).collect(Collectors.toList());

        metadataConfigService.saveBatch(configs);
        List<MetadataConfigDTO> result = configs.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return Result.ok(result);
    }

    private MetadataConfigDTO convertToDTO(MetadataConfig config) {
        MetadataConfigDTO dto = new MetadataConfigDTO();
        BeanUtils.copyProperties(config, dto, "aliases", "dictionary");

        // 反序列化复杂对象
        try {
            if (config.getAliases() != null && !config.getAliases().isEmpty()) {
                dto.setAliases(objectMapper.readValue(config.getAliases(),
                        new TypeReference<List<String>>() {}));
            }
            if (config.getDictionary() != null && !config.getDictionary().isEmpty()) {
                dto.setDictionary(objectMapper.readValue(config.getDictionary(),
                        new TypeReference<List<MetadataConfigDTO.Dictionary>>() {}));
            }
        } catch (Exception e) {
            // 忽略反序列化错误
        }

        return dto;
    }
}