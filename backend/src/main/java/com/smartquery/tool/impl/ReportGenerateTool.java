package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Chart;
import com.smartquery.entity.Report;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.mapper.ReportMapper;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ReportGenerateTool implements LlmTool {

    private final ReportMapper reportMapper;
    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "generate_report"; }

    @Override
    public String getDescription() {
        return "生成完整的结构化分析报告，包含多个章节、数据表格、图表和分析解读。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "title", Map.of("type", "string", "description", "报告标题"),
                "sections", Map.of("type", "array", "description", "报告章节列表",
                    "items", Map.of("type", "object",
                        "properties", Map.of(
                            "section_title", Map.of("type", "string"),
                            "section_content", Map.of("type", "string"),
                            "sql_used", Map.of("type", "string"),
                            "chart_type", Map.of("type", "string"),
                            "chart_id", Map.of("type", "integer", "description", "该章节引用的图表 ID")
                        ))),
                "conclusion", Map.of("type", "string", "description", "总结和建议")
            ),
            "required", List.of("title", "sections")
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
            List<Map<String, Object>> sections = (List<Map<String, Object>>) input.get("sections");
            if (sections == null || sections.isEmpty()) {
                return ToolResult.error(getName(), "sections 不能为空", System.currentTimeMillis() - start);
            }

            // Validate chart_id references and fetch chart data
            List<Long> chartIds = sections.stream()
                .map(s -> s.get("chart_id"))
                .filter(id -> id instanceof Number)
                .map(id -> ((Number) id).longValue())
                .distinct().toList();

            // Fetch chart data for embedding in report
            Map<Long, Chart> chartDataMap = new java.util.HashMap<>();
            if (!chartIds.isEmpty()) {
                List<Long> existingIds = chartMapper.selectBatchIds(chartIds).stream()
                    .map(Chart::getId).toList();
                List<Long> missing = chartIds.stream().filter(id -> !existingIds.contains(id)).toList();
                if (!missing.isEmpty()) {
                    return ToolResult.error(getName(),
                        "以下图表 ID 不存在: " + missing + "，请先调用 generate_chart 生成",
                        System.currentTimeMillis() - start);
                }

                // Load chart data for embedding
                List<Chart> charts = chartMapper.selectBatchIds(chartIds);
                for (Chart chart : charts) {
                    chartDataMap.put(chart.getId(), chart);
                }

                // Embed chart data into sections
                List<Map<String, Object>> enrichedSections = new java.util.ArrayList<>();
                for (Map<String, Object> section : sections) {
                    Map<String, Object> enrichedSection = new LinkedHashMap<>(section);
                    Object chartIdObj = section.get("chart_id");
                    if (chartIdObj instanceof Number) {
                        Long chartId = ((Number) chartIdObj).longValue();
                        Chart chart = chartDataMap.get(chartId);
                        if (chart != null) {
                            // Embed complete chart data
                            enrichedSection.put("chartOption", chart.getEchartsOption());
                            enrichedSection.put("chartTitle", chart.getTitle());
                            enrichedSection.put("chartType", chart.getChartType());
                        }
                    }
                    enrichedSections.add(enrichedSection);
                }
                sections = enrichedSections;
            }

            // 持久化到数据库
            Report report = new Report();
            report.setConversationId(context.conversationId());
            report.setTitle(title);
            report.setSections(objectMapper.writeValueAsString(sections));
            report.setConclusion((String) input.get("conclusion"));
            report.setStatus("completed");
            report.setDataSourceId(context.dataSourceId());
            reportMapper.insert(report);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportId", report.getId());
            result.put("title", title);
            result.put("sectionCount", sections.size());
            result.put("sections", sections);
            if (input.get("conclusion") != null) {
                result.put("conclusion", input.get("conclusion"));
            }

            String output = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(result);

            return ToolResult.ok(getName(), output, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "报告生成错误: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean isConcurrencySafe() { return false; }
}
