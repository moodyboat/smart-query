package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Chart;
import com.smartquery.entity.Dashboard;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.mapper.DashboardMapper;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DashboardGenerateTool implements LlmTool {

    private final DashboardMapper dashboardMapper;
    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "generate_dashboard"; }

    @Override
    public String getDescription() {
        return "将多个图表组合为仪表盘大屏，定义布局和联动筛选。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "title", Map.of("type", "string", "description", "仪表盘标题"),
                "layout", Map.of("type", "string", "description", "布局方式: grid-2col/grid-3col/free",
                    "enum", List.of("grid-2col", "grid-3col", "free")),
                "chart_ids", Map.of("type", "array", "items", Map.of("type", "integer"),
                    "description", "包含的图表 ID 列表"),
                "charts_config", Map.of("type", "array", "description", "图表配置列表",
                    "items", Map.of("type", "object"))
            ),
            "required", List.of("title")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        try {
            String title = (String) input.get("title");
            if (title == null || title.isBlank()) {
                return ToolResult.error(getName(), "title 不能为空", System.currentTimeMillis() - start);
            }
            String layout = (String) input.getOrDefault("layout", "grid-2col");
            List<?> chartIds = (List<?>) input.getOrDefault("chart_ids", List.of());

            // Validate that referenced chart IDs exist
            if (!chartIds.isEmpty()) {
                List<Long> ids = chartIds.stream()
                    .filter(id -> id instanceof Number)
                    .map(id -> ((Number) id).longValue())
                    .collect(Collectors.toList());
                if (!ids.isEmpty()) {
                    List<Long> existingIds = chartMapper.selectBatchIds(ids).stream()
                        .map(Chart::getId).collect(Collectors.toList());
                    List<Long> missing = ids.stream().filter(id -> !existingIds.contains(id)).collect(Collectors.toList());
                    if (!missing.isEmpty()) {
                        return ToolResult.error(getName(),
                            "以下图表 ID 不存在: " + missing + "，请先调用 generate_chart 生成图表",
                            System.currentTimeMillis() - start);
                    }
                }
            }

            // 持久化到数据库
            Dashboard dashboard = new Dashboard();
            dashboard.setConversationId(context.conversationId());
            dashboard.setTitle(title);
            // layout 列是 JSON 类型，需要包装为 JSON 对象
            Map<String, Object> layoutConfig = new LinkedHashMap<>();
            layoutConfig.put("type", layout);
            if (input.get("charts_config") instanceof List<?> chartsConfig) {
                layoutConfig.put("charts", chartsConfig);
            }
            dashboard.setLayout(objectMapper.writeValueAsString(layoutConfig));
            dashboard.setChartIds(objectMapper.writeValueAsString(chartIds));
            dashboard.setDataSourceId(context.dataSourceId());
            dashboardMapper.insert(dashboard);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dashboardId", dashboard.getId());
            result.put("title", title);
            result.put("layout", layout);
            result.put("chartIds", chartIds);
            if (input.get("charts_config") != null) {
                result.put("charts", input.get("charts_config"));
            }

            String output = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(result);

            return ToolResult.ok(getName(), output, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "仪表盘生成错误: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean isConcurrencySafe() { return false; }
}
