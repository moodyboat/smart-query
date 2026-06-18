package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Chart;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.tool.*;
import com.smartquery.service.ChartImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChartGenerateTool implements LlmTool {

    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;
    private final ChartImageService chartImageService;

    @Value("${smart-query.chart.storage.path:./charts}")
    private String chartStoragePath;

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

            // 生成并保存图表图片
            String imagePath = saveChartImage(chart.getId(), optionJson);
            if (imagePath != null) {
                chart.setImagePath(imagePath);
                chartMapper.updateById(chart);
                log.info("[CHART-GENERATE] 图表图片已保存: chartId={}, path={}", chart.getId(), imagePath);
            }

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

    /**
     * 保存图表图片到文件系统
     */
    private String saveChartImage(Long chartId, String echartsOption) {
        try {
            // 生成图片字节数组
            byte[] imageBytes = chartImageService.convertEchartsToImage(echartsOption);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("[CHART-GENERATE] 图片生成失败: chartId={}", chartId);
                return null;
            }

            // 创建存储目录
            Path storageDir = Paths.get(chartStoragePath);
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                log.info("[CHART-GENERATE] 创建图表存储目录: {}", storageDir.toAbsolutePath());
            }

            // 生成文件名（使用时间戳和图表ID）
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = String.format("chart_%d_%s.png", chartId, timestamp);
            Path imagePath = storageDir.resolve(fileName);

            // 保存图片文件
            Files.write(imagePath, imageBytes);
            log.info("[CHART-GENERATE] 图片文件已保存: {}", imagePath.toAbsolutePath());

            // 返回相对路径
            return fileName;

        } catch (Exception e) {
            log.error("[CHART-GENERATE] 保存图片失败: chartId={}", chartId, e);
            return null;
        }
    }

    @Override
    public boolean isConcurrencySafe() { return true; }

    @Override
    public boolean isReadOnly() { return false; }
}
