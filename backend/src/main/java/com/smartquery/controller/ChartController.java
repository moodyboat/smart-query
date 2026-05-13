package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.Chart;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.tool.SqlSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/chart")
@RequiredArgsConstructor
public class ChartController {

    private final ChartMapper chartMapper;
    private final DataSourceManager dataSourceManager;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public Result<Chart> get(@PathVariable Long id) {
        return Result.ok(chartMapper.selectById(id));
    }

    @GetMapping("/conversation/{conversationId}")
    public Result<List<Chart>> listByConversation(@PathVariable Long conversationId) {
        return Result.ok(chartMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chart>()
                .eq(Chart::getConversationId, conversationId)));
    }

    /**
     * 使用新筛选值重新执行图表的 SQL 并返回更新后的数据
     */
    @PostMapping("/{id}/rerender")
    public Result<Map<String, Object>> rerender(
        @PathVariable Long id,
        @RequestBody Map<String, Object> filterValues
    ) {
        Chart chart = chartMapper.selectById(id);
        if (chart == null) {
            return Result.error("图表不存在: " + id);
        }
        if (chart.getBaseSql() == null || chart.getBaseSql().isBlank()) {
            return Result.error("图表没有关联的 SQL 查询");
        }

        String sql = chart.getBaseSql();

        // 替换筛选占位符
        if (filterValues != null) {
            for (Map.Entry<String, Object> entry : filterValues.entrySet()) {
                String placeholder = "{{filter." + entry.getKey() + "}}";
                if (!sql.contains(placeholder)) continue;

                Object val = entry.getValue();
                String replacement = resolveFilterValue(entry.getKey(), val);
                if (replacement == null) {
                    return Result.error("筛选值包含非法字符: " + entry.getKey());
                }
                sql = sql.replace(placeholder, replacement);
            }
        }

        // 清除未替换的占位符
        sql = sql.replaceAll("\\{\\{filter\\.[^}]+}}", "1=1");

        // 安全检查
        SqlSafetyValidator.ValidationResult validation = sqlSafetyValidator.validate(sql);
        if (!validation.safe()) {
            return Result.error("SQL 安全检查未通过: " + validation.reason());
        }

        // 自动添加 LIMIT
        if (!sql.toUpperCase().contains(" LIMIT ")) {
            sql += " LIMIT 1000";
        }

        try {
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(chart.getDataSourceId());
            List<Map<String, Object>> rows = jdbc.queryForList(sql);

            // Rebuild the echarts option from the original template + new data
            Map<String, Object> rebuiltOption = rebuildEchartsOption(
                chart.getEchartsOption(), chart.getChartType(), rows);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chartId", id);
            result.put("title", chart.getTitle());
            result.put("chartType", chart.getChartType());
            result.put("rows", rows);
            result.put("totalRows", rows.size());
            result.put("echartsOption", rebuiltOption);
            result.put("filterValues", filterValues);

            return Result.ok(result);
        } catch (Exception e) {
            log.error("[CHART] rerender failed: chartId={}, error={}", id, e.getMessage());
            return Result.error("SQL 执行失败: " + e.getMessage());
        }
    }

    /**
     * Rebuild ECharts option by updating data from new SQL rows.
     * Handles bar, line, pie, scatter, radar, gauge, funnel, heatmap, etc.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> rebuildEchartsOption(String originalJson, String chartType, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return null;
        try {
            Map<String, Object> opt = objectMapper.readValue(originalJson, Map.class);
            List<String> columns = new ArrayList<>(rows.get(0).keySet());

            switch (chartType != null ? chartType : "bar") {
                case "pie" -> rebuildPieOption(opt, rows, columns);
                case "scatter" -> rebuildScatterOption(opt, rows, columns);
                case "radar" -> rebuildRadarOption(opt, rows, columns);
                case "gauge" -> rebuildGaugeOption(opt, rows, columns);
                case "funnel" -> rebuildFunnelOption(opt, rows, columns);
                default -> rebuildCartesianOption(opt, rows, columns);
            }
            return opt;
        } catch (Exception e) {
            log.warn("[CHART] failed to rebuild option, returning raw rows: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildCartesianOption(Map<String, Object> opt, List<Map<String, Object>> rows, List<String> columns) {
        if (columns.size() < 2) return;
        String xCol = columns.get(0);
        List<String> yCols = columns.subList(1, columns.size());

        Object xAxisObj = opt.get("xAxis");
        if (xAxisObj instanceof Map) {
            ((Map<String, Object>) xAxisObj).put("data", rows.stream().map(r -> String.valueOf(r.get(xCol))).toList());
        }
        List<Map<String, Object>> series = (List<Map<String, Object>>) (List) opt.get("series");
        if (series != null) {
            for (int i = 0; i < series.size(); i++) {
                int colIdx = Math.min(i, yCols.size() - 1);
                series.get(i).put("data", rows.stream().map(r -> r.get(yCols.get(colIdx))).toList());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildPieOption(Map<String, Object> opt, List<Map<String, Object>> rows, List<String> columns) {
        if (columns.size() < 2) return;
        String nameCol = columns.get(0);
        String valCol = columns.get(1);
        List series = (List) opt.get("series");
        if (series != null && !series.isEmpty()) {
            List<Map<String, Object>> pieData = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", String.valueOf(r.get(nameCol)));
                item.put("value", r.get(valCol));
                pieData.add(item);
            }
            ((Map<String, Object>) series.get(0)).put("data", pieData);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildScatterOption(Map<String, Object> opt, List<Map<String, Object>> rows, List<String> columns) {
        if (columns.size() < 2) return;
        String xCol = columns.get(0);
        String yCol = columns.get(1);
        List series = (List) opt.get("series");
        if (series != null && !series.isEmpty()) {
            List<List<Object>> scatterData = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                scatterData.add(List.of(r.get(xCol), r.get(yCol)));
            }
            ((Map<String, Object>) series.get(0)).put("data", scatterData);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildRadarOption(Map<String, Object> opt, List<Map<String, Object>> rows, List<String> columns) {
        if (columns.size() < 2) return;
        String valCol = columns.get(1);
        List series = (List) opt.get("series");
        if (series != null && !series.isEmpty()) {
            List<Object> values = rows.stream().map(r -> r.get(valCol)).toList();
            Object s0 = series.get(0);
            if (s0 instanceof Map) {
                Map<String, Object> s0Map = (Map<String, Object>) s0;
                Object dataObj = s0Map.get("data");
                if (dataObj instanceof List dataList && !dataList.isEmpty() && dataList.get(0) instanceof Map) {
                    ((Map<String, Object>) dataList.get(0)).put("value", values);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildGaugeOption(Map<String, Object> opt, List<Map<String, Object>> rows, List<String> columns) {
        if (columns.size() < 2 || rows.isEmpty()) return;
        List series = (List) opt.get("series");
        if (series != null && !series.isEmpty()) {
            Object val = rows.get(0).get(columns.get(1));
            ((Map<String, Object>) series.get(0)).put("data", List.of(Map.of("value", val, "name", columns.get(0))));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildFunnelOption(Map<String, Object> opt, List<Map<String, Object>> rows, List<String> columns) {
        if (columns.size() < 2) return;
        String nameCol = columns.get(0);
        String valCol = columns.get(1);
        List series = (List) opt.get("series");
        if (series != null && !series.isEmpty()) {
            List<Map<String, Object>> funnelData = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", String.valueOf(r.get(nameCol)));
                item.put("value", r.get(valCol));
                funnelData.add(item);
            }
            ((Map<String, Object>) series.get(0)).put("data", funnelData);
        }
    }

    private boolean isValidFilterValue(String value) {
        if (value.length() > 200) return false;
        return !value.matches("(?i).*(;|--|/\\*|\\*/|DROP|DELETE|UPDATE|INSERT|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|UNION|EXEC|EXECUTE).*");
    }

    /**
     * Resolve filter value to SQL fragment. Handles:
     * - String → 'value'
     * - Array of 2 strings (daterange) → AND col BETWEEN 'a' AND 'b'
     * - null/empty → empty string (remove placeholder)
     * Returns null if value is invalid.
     */
    private String resolveFilterValue(String field, Object val) {
        if (val == null) return "";
        if (val instanceof String s) {
            if (s.isEmpty()) return "";
            if (!isValidFilterValue(s)) return null;
            return "AND " + field + " = '" + s.replace("'", "''") + "'";
        }
        if (val instanceof java.util.List<?> list && list.size() == 2) {
            String start = String.valueOf(list.get(0));
            String end = String.valueOf(list.get(1));
            if (!isValidFilterValue(start) || !isValidFilterValue(end)) return null;
            return "AND " + field + " BETWEEN '" + start.replace("'", "''")
                + "' AND '" + end.replace("'", "''") + "'";
        }
        // Fallback: treat as string
        String strVal = String.valueOf(val);
        if (strVal.isEmpty()) return "";
        if (!isValidFilterValue(strVal)) return null;
        return "AND " + field + " = '" + strVal.replace("'", "''") + "'";
    }
}
