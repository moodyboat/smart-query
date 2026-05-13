package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.mapper.DashboardMapper;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class FilterWidgetTool implements LlmTool {

    private final ChartMapper chartMapper;
    private final DashboardMapper dashboardMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "generate_filter_widgets"; }

    @Override
    public String getDescription() {
        return "为图表或仪表盘自动生成联动筛选控件。必须指定 target_type 和 target_id 关联到具体图表或仪表盘。支持日期选择器、下拉选择、搜索框、级联选择等。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "base_sql", Map.of("type", "string", "description", "基础 SQL 查询，包含 {{filter.字段名}} 占位符"),
                "dimensions", Map.of("type", "array", "items", Map.of("type", "object",
                    "properties", Map.of(
                        "field", Map.of("type", "string", "description", "字段名"),
                        "label", Map.of("type", "string", "description", "显示名称"),
                        "type", Map.of("type", "string", "description", "控件类型: daterange/select/search/cascader",
                            "enum", List.of("daterange", "select", "search", "cascader")),
                        "options", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "下拉选项 (select 类型必填)"),
                        "default_value", Map.of("type", "string", "description", "默认值")
                    )), "description", "筛选维度列表"),
                "target_type", Map.of("type", "string", "description", "目标类型: chart 或 dashboard",
                    "enum", List.of("chart", "dashboard")),
                "target_id", Map.of("type", "integer", "description", "目标图表或仪表盘的 ID")
            ),
            "required", List.of("base_sql", "dimensions", "target_type", "target_id")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        try {
            String baseSql = (String) input.get("base_sql");
            List<Map<String, Object>> dimensions = (List<Map<String, Object>>) input.get("dimensions");
            String targetType = (String) input.get("target_type");
            Long targetId = input.get("target_id") != null ? ((Number) input.get("target_id")).longValue() : null;

            if (targetType == null || targetId == null) {
                return ToolResult.error(getName(),
                    "必须指定 target_type (chart/dashboard) 和 target_id",
                    System.currentTimeMillis() - start);
            }
            if (dimensions == null || dimensions.isEmpty()) {
                return ToolResult.error(getName(),
                    "dimensions 不能为空，至少需要一个筛选维度",
                    System.currentTimeMillis() - start);
            }

            List<Map<String, Object>> widgets = new ArrayList<>();
            List<Map<String, Object>> bindings = new ArrayList<>();

            for (Map<String, Object> dim : dimensions) {
                String field = (String) dim.get("field");
                String type = (String) dim.getOrDefault("type", "select");
                String label = (String) dim.getOrDefault("label", field);

                Map<String, Object> widget = new LinkedHashMap<>();
                widget.put("type", type);
                widget.put("field", field);
                widget.put("label", label);
                if (dim.get("default_value") != null) widget.put("default", dim.get("default_value"));
                if (dim.get("options") != null) widget.put("options", dim.get("options"));
                widgets.add(widget);

                Map<String, Object> binding = new LinkedHashMap<>();
                binding.put("widgetField", field);
                binding.put("sqlPlaceholder", "{{filter." + field + "}}");
                bindings.add(binding);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("widgets", widgets);
            result.put("bindings", bindings);
            result.put("baseSql", baseSql);
            if (targetType != null) result.put("targetType", targetType);
            if (targetId != null) result.put("targetId", targetId);

            String filterJson = objectMapper.writeValueAsString(result);

            // 持久化筛选控件到目标记录
            String persistError = persistFilterBindings(targetType, targetId, filterJson);
            if (persistError != null) {
                return ToolResult.error(getName(), persistError, System.currentTimeMillis() - start);
            }

            String output = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(result);

            return ToolResult.ok(getName(), output, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "筛选控件生成错误: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private String persistFilterBindings(String targetType, Long targetId, String filterJson) {
        try {
            switch (targetType) {
                case "chart" -> {
                    com.smartquery.entity.Chart chart = chartMapper.selectById(targetId);
                    if (chart == null) {
                        return "图表 ID " + targetId + " 不存在，无法绑定筛选控件";
                    }
                    chart.setFilterBindings(filterJson);
                    chartMapper.updateById(chart);
                }
                case "dashboard" -> {
                    com.smartquery.entity.Dashboard dashboard = dashboardMapper.selectById(targetId);
                    if (dashboard == null) {
                        return "仪表盘 ID " + targetId + " 不存在，无法绑定筛选控件";
                    }
                    dashboard.setFilterWidgets(filterJson);
                    dashboardMapper.updateById(dashboard);
                }
                default -> {
                    return "不支持的 target_type: " + targetType + "，必须是 chart 或 dashboard";
                }
            }
        } catch (Exception e) {
            return "筛选控件持久化失败: " + e.getMessage();
        }
        return null;
    }

    @Override
    public boolean isConcurrencySafe() { return true; }

    @Override
    public boolean isReadOnly() { return false; }
}
