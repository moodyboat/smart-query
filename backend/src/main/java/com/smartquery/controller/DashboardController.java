package com.smartquery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.Result;
import com.smartquery.entity.Chart;
import com.smartquery.entity.Dashboard;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.mapper.DashboardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardMapper dashboardMapper;
    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public Result<Dashboard> get(@PathVariable Long id) {
        return Result.ok(dashboardMapper.selectById(id));
    }

    @GetMapping("/{id}/charts")
    public Result<Map<String, Object>> getWithCharts(@PathVariable Long id) {
        Dashboard dashboard = dashboardMapper.selectById(id);
        if (dashboard == null) {
            return Result.error("仪表盘不存在: " + id);
        }

        List<Chart> charts = new ArrayList<>();
        if (dashboard.getChartIds() != null) {
            try {
                List<?> ids = objectMapper.readValue(dashboard.getChartIds(), List.class);
                for (Object oid : ids) {
                    Long chartId = ((Number) oid).longValue();
                    Chart c = chartMapper.selectById(chartId);
                    if (c != null) charts.add(c);
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dashboard", dashboard);
        result.put("charts", charts);
        return Result.ok(result);
    }

    @GetMapping("/conversation/{conversationId}")
    public Result<List<Dashboard>> listByConversation(@PathVariable Long conversationId) {
        return Result.ok(dashboardMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Dashboard>()
                .eq(Dashboard::getConversationId, conversationId)));
    }
}
