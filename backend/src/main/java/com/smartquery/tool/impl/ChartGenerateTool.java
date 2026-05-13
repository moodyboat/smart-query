package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Chart;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ChartGenerateTool implements LlmTool {

    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "generate_chart"; }

    @Override
    public String getDescription() {
        return "根据数据生成 ECharts 图表配置。支持柱状图、折线图、饼图、散点图、热力图等。必须包含 base_sql 字段用于筛选控件联动。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "chart_type", Map.of("type", "string",
                    "description", "图表类型: bar/line/pie/scatter/heatmap/radar/gauge/funnel/map",
                    "enum", List.of("bar", "line", "pie", "scatter", "heatmap", "radar", "gauge", "funnel", "map")),
                "title", Map.of("type", "string", "description", "图表标题"),
                "data_description", Map.of("type", "string", "description", "数据说明"),
                "echarts_option", Map.of("type", "object", "description", "完整的 ECharts option JSON 对象"),
                "base_sql", Map.of("type", "string", "description", "图表数据来源的 SQL 查询，使用 {{filter.字段名}} 占位符支持筛选联动。示例: SELECT region, SUM(amount) FROM orders WHERE 1=1 {{filter.region}} GROUP BY region")
            ),
            "required", List.of("chart_type", "title", "echarts_option", "base_sql")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> echartsOption = (Map<String, Object>) input.get("echarts_option");
            String chartType = (String) input.get("chart_type");
            String title = (String) input.get("title");

            if (echartsOption == null || echartsOption.isEmpty()) {
                return ToolResult.error(getName(), "echarts_option 不能为空", System.currentTimeMillis() - start);
            }
            if (title == null || title.isBlank()) {
                return ToolResult.error(getName(), "title 不能为空", System.currentTimeMillis() - start);
            }
            if (input.get("base_sql") == null || ((String) input.get("base_sql")).isBlank()) {
                return ToolResult.error(getName(), "base_sql 不能为空，图表需要基础查询语句支持筛选联动", System.currentTimeMillis() - start);
            }

            String optionJson = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(echartsOption);

            // 持久化到数据库
            Chart chart = new Chart();
            chart.setConversationId(context.conversationId());
            chart.setTitle(title);
            chart.setChartType(chartType);
            chart.setEchartsOption(optionJson);
            chart.setDataSourceId(context.dataSourceId());
            if (input.get("base_sql") != null) {
                chart.setBaseSql((String) input.get("base_sql"));
            }
            chartMapper.insert(chart);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chartId", chart.getId());
            result.put("chartType", chartType);
            result.put("title", title);
            result.put("echartsOption", optionJson);

            String output = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(result);

            return ToolResult.ok(getName(), output, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "图表生成错误: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean isConcurrencySafe() { return true; }

    @Override
    public boolean isReadOnly() { return false; }
}
