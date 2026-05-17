package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.entity.*;
import com.smartquery.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyService {

    private final OntologyMetricMapper metricMapper;
    private final OntologyDimensionMapper dimensionMapper;
    private final OntologyGlossaryMapper glossaryMapper;
    private final OntologyIndicatorConfigMapper indicatorConfigMapper;

    // ==================== Metrics ====================

    public List<OntologyMetric> listMetrics(Long dataSourceId) {
        return metricMapper.selectList(new LambdaQueryWrapper<OntologyMetric>()
            .eq(OntologyMetric::getDataSourceId, dataSourceId)
            .eq(OntologyMetric::getDeleted, 0)
            .eq(OntologyMetric::getStatus, 1)
            .orderByAsc(OntologyMetric::getSortOrder));
    }

    public OntologyMetric getMetric(Long id) {
        return metricMapper.selectById(id);
    }

    public OntologyMetric createMetric(OntologyMetric metric) {
        metricMapper.insert(metric);
        log.info("[ONTOLOGY] created metric: id={}, name={}", metric.getId(), metric.getName());
        return metric;
    }

    public OntologyMetric updateMetric(Long id, OntologyMetric updates) {
        updates.setId(id);
        metricMapper.updateById(updates);
        log.info("[ONTOLOGY] updated metric: id={}", id);
        return metricMapper.selectById(id);
    }

    public void deleteMetric(Long id) {
        OntologyMetric m = new OntologyMetric();
        m.setId(id);
        m.setDeleted(1);
        metricMapper.updateById(m);
        log.info("[ONTOLOGY] deleted metric: id={}", id);
    }

    // ==================== Dimensions ====================

    public List<OntologyDimension> listDimensions(Long dataSourceId) {
        return dimensionMapper.selectList(new LambdaQueryWrapper<OntologyDimension>()
            .eq(OntologyDimension::getDataSourceId, dataSourceId)
            .eq(OntologyDimension::getDeleted, 0)
            .eq(OntologyDimension::getStatus, 1)
            .orderByAsc(OntologyDimension::getSortOrder));
    }

    public OntologyDimension getDimension(Long id) {
        return dimensionMapper.selectById(id);
    }

    public OntologyDimension createDimension(OntologyDimension dimension) {
        dimensionMapper.insert(dimension);
        log.info("[ONTOLOGY] created dimension: id={}, name={}", dimension.getId(), dimension.getName());
        return dimension;
    }

    public OntologyDimension updateDimension(Long id, OntologyDimension updates) {
        updates.setId(id);
        dimensionMapper.updateById(updates);
        return dimensionMapper.selectById(id);
    }

    public void deleteDimension(Long id) {
        OntologyDimension d = new OntologyDimension();
        d.setId(id);
        d.setDeleted(1);
        dimensionMapper.updateById(d);
    }

    /**
     * 获取维度树 (层次结构)
     */
    public List<Map<String, Object>> getDimensionTree(Long dataSourceId) {
        List<OntologyDimension> dims = listDimensions(dataSourceId);
        Map<Long, List<OntologyDimension>> childrenMap = dims.stream()
            .filter(d -> d.getParentDimensionId() != null)
            .collect(Collectors.groupingBy(OntologyDimension::getParentDimensionId));

        return dims.stream()
            .filter(d -> d.getParentDimensionId() == null || d.getParentDimensionId() == 0)
            .map(d -> buildDimensionNode(d, childrenMap))
            .collect(Collectors.toList());
    }

    private Map<String, Object> buildDimensionNode(OntologyDimension d, Map<Long, List<OntologyDimension>> childrenMap) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", d.getId());
        node.put("name", d.getName());
        node.put("businessName", d.getBusinessName());
        node.put("type", d.getDimensionType());
        node.put("sourceTable", d.getSourceTable());
        node.put("sourceColumn", d.getSourceColumn());

        List<OntologyDimension> children = childrenMap.getOrDefault(d.getId(), List.of());
        if (!children.isEmpty()) {
            node.put("children", children.stream()
                .map(c -> buildDimensionNode(c, childrenMap))
                .collect(Collectors.toList()));
        }
        return node;
    }

    // ==================== Glossary ====================

    public List<OntologyGlossary> listGlossary(Long dataSourceId) {
        return glossaryMapper.selectList(new LambdaQueryWrapper<OntologyGlossary>()
            .eq(OntologyGlossary::getDataSourceId, dataSourceId)
            .eq(OntologyGlossary::getDeleted, 0)
            .orderByAsc(OntologyGlossary::getSortOrder));
    }

    public OntologyGlossary getGlossaryEntry(Long id) {
        return glossaryMapper.selectById(id);
    }

    public OntologyGlossary createGlossaryEntry(OntologyGlossary entry) {
        glossaryMapper.insert(entry);
        log.info("[ONTOLOGY] created glossary entry: id={}, term={}", entry.getId(), entry.getTerm());
        return entry;
    }

    public OntologyGlossary updateGlossaryEntry(Long id, OntologyGlossary updates) {
        updates.setId(id);
        glossaryMapper.updateById(updates);
        return glossaryMapper.selectById(id);
    }

    public void deleteGlossaryEntry(Long id) {
        OntologyGlossary g = new OntologyGlossary();
        g.setId(id);
        g.setDeleted(1);
        glossaryMapper.updateById(g);
    }

    /**
     * 术语解析: 根据用户输入查找匹配的业务术语
     */
    public List<Map<String, Object>> resolveTerm(Long dataSourceId, String query) {
        List<OntologyGlossary> allTerms = listGlossary(dataSourceId);
        String lowerQuery = query.toLowerCase();

        return allTerms.stream()
            .filter(g -> {
                if (g.getTerm().toLowerCase().contains(lowerQuery)) return true;
                if (g.getSynonyms() != null && g.getSynonyms().toLowerCase().contains(lowerQuery)) return true;
                return false;
            })
            .map(g -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("term", g.getTerm());
                result.put("definition", g.getDefinition());
                result.put("mappedMetricId", g.getMappedMetricId());
                result.put("mappedTable", g.getMappedTable());
                result.put("mappedColumn", g.getMappedColumn());
                result.put("mappingRule", g.getMappingRule());
                return result;
            })
            .collect(Collectors.toList());
    }

    // ==================== Indicator Config ====================

    public List<OntologyIndicatorConfig> listIndicatorConfigs(Long dataSourceId) {
        return indicatorConfigMapper.selectList(new LambdaQueryWrapper<OntologyIndicatorConfig>()
            .eq(OntologyIndicatorConfig::getDataSourceId, dataSourceId)
            .eq(OntologyIndicatorConfig::getDeleted, 0)
            .eq(OntologyIndicatorConfig::getStatus, 1));
    }

    public OntologyIndicatorConfig createIndicatorConfig(OntologyIndicatorConfig config) {
        indicatorConfigMapper.insert(config);
        log.info("[ONTOLOGY] created indicator config: id={}, table={}", config.getId(), config.getIndicatorTable());
        return config;
    }

    public OntologyIndicatorConfig updateIndicatorConfig(Long id, OntologyIndicatorConfig updates) {
        updates.setId(id);
        indicatorConfigMapper.updateById(updates);
        return indicatorConfigMapper.selectById(id);
    }

    public void deleteIndicatorConfig(Long id) {
        OntologyIndicatorConfig c = new OntologyIndicatorConfig();
        c.setId(id);
        c.setDeleted(1);
        indicatorConfigMapper.updateById(c);
    }

    // ==================== Resolution ====================

    /**
     * 指标 → SQL 解析: 根据指标名生成查询SQL模板
     */
    public Map<String, Object> resolveMetricToSql(Long metricId) {
        OntologyMetric metric = getMetric(metricId);
        if (metric == null) return Map.of("error", "指标不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", metric.getName());
        result.put("businessName", metric.getBusinessName());
        result.put("type", metric.getMetricType());

        String sql;
        if ("basic".equals(metric.getMetricType())) {
            String agg = metric.getAggregation() != null ? metric.getAggregation().toUpperCase() : "";
            String expr = agg.isEmpty() || "NONE".equals(agg)
                ? metric.getSourceColumn()
                : agg + "(" + metric.getSourceColumn() + ")";
            sql = "SELECT " + expr + " AS " + metric.getName() + " FROM " + metric.getSourceTable();
        } else {
            sql = metric.getFormula();
        }

        if (metric.getFilterCondition() != null && !metric.getFilterCondition().isBlank()) {
            sql += " WHERE " + metric.getFilterCondition();
        }

        result.put("sql", sql);
        result.put("sourceTable", metric.getSourceTable());
        result.put("unit", metric.getUnit());
        return result;
    }

    /**
     * 构建本体上下文 (供 SystemPromptBuilder 注入到 LLM)
     */
    public String buildOntologyContext(Long dataSourceId, int maxTokens) {
        int maxChars = maxTokens * 4; // ~4 chars per token

        List<OntologyMetric> metrics = listMetrics(dataSourceId);
        List<OntologyDimension> dimensions = listDimensions(dataSourceId);
        List<OntologyGlossary> glossary = listGlossary(dataSourceId);

        if (metrics.isEmpty() && dimensions.isEmpty() && glossary.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 业务本体模型\n\n");

        if (!metrics.isEmpty()) {
            sb.append("## 指标定义\n\n");
            sb.append("| 指标名 | 类型 | 来源 | 聚合 | 公式 | 说明 |\n");
            sb.append("| --- | --- | --- | --- | --- | --- |\n");
            for (OntologyMetric m : metrics) {
                sb.append("| ").append(m.getBusinessName()).append(" (").append(m.getName()).append(")");
                sb.append(" | ").append(m.getMetricType());
                sb.append(" | ").append(m.getSourceTable() != null ? m.getSourceTable() + "." + m.getSourceColumn() : "-");
                sb.append(" | ").append(m.getAggregation() != null ? m.getAggregation() : "-");
                sb.append(" | ").append(m.getFormula() != null ? m.getFormula() : "-");
                sb.append(" | ").append(m.getDescription() != null ? m.getDescription() : "").append(" |\n");
            }
            sb.append("\n");
        }

        if (!dimensions.isEmpty()) {
            sb.append("## 维度定义\n\n");
            for (OntologyDimension d : dimensions) {
                sb.append("- **").append(d.getBusinessName()).append("** (").append(d.getName()).append(")");
                sb.append(": ").append(d.getDimensionType());
                if (d.getSourceTable() != null) {
                    sb.append(", 来源: ").append(d.getSourceTable()).append(".").append(d.getSourceColumn());
                }
                if (d.getDescription() != null) {
                    sb.append(" — ").append(d.getDescription());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!glossary.isEmpty()) {
            sb.append("## 业务术语\n\n");
            for (OntologyGlossary g : glossary) {
                sb.append("- **").append(g.getTerm()).append("**");
                if (g.getSynonyms() != null && !g.getSynonyms().isBlank()) {
                    sb.append(" (同义词: ").append(g.getSynonyms()).append(")");
                }
                sb.append(": ").append(g.getDefinition());
                if (g.getMappedMetricId() != null) {
                    sb.append(" → 关联指标 #").append(g.getMappedMetricId());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        String result = sb.toString();
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars) + "\n...(本体模型已截断)";
        }

        return result;
    }
}
