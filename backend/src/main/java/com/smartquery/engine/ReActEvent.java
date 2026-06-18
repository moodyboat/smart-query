package com.smartquery.engine;

import java.util.List;
import java.util.Map;

/**
 * ReAct 事件 — sealed interface，扩展为 14+ 事件类型
 */
public sealed interface ReActEvent {

    // === 基础事件 ===
    record Thinking(String content) implements ReActEvent {}
    record ThinkingDelta(String content) implements ReActEvent {}
    record SqlGenerated(String sql, String explanation) implements ReActEvent {}
    record SqlExecuting(String sql) implements ReActEvent {}
    record Result(String summary, List<Map<String, Object>> data, int totalRows, String error) implements ReActEvent {}
    record Info(String message) implements ReActEvent {}

    // === Python 数据挖掘 ===
    record PythonGenerating(String code) implements ReActEvent {}
    record PythonExecuting(String code, int executionTimeMs) implements ReActEvent {}
    record PythonProgress(String output, int elapsedMs) implements ReActEvent {}
    record PythonResultEvent(String stdout, String stderr, int exitCode, List<String> artifacts) implements ReActEvent {}

    // === 可视化 ===
    record ChartGenerated(Long chartId, String title, String chartType, String echartsOption) implements ReActEvent {}
    record DashboardGenerated(Long dashboardId, String title, String layout, List<Long> chartIds) implements ReActEvent {}

    // === 报告 ===
    record SectionGenerated(int sectionIndex, String title, String content) implements ReActEvent {}
    record ReportGenerated(Long reportId, String title, int sectionCount,
                           List<Map<String, Object>> sections, String conclusion) implements ReActEvent {}

    // === 筛选控件 ===
    record FilterWidgetsGenerated(String widgetsJson) implements ReActEvent {}

    // === 数据挖掘模型 ===
    record MiningModelEvent(String action, Long modelId, String modelName, String algorithm,
                            boolean success, String message, Map<String, Object> details) implements ReActEvent {}

    // === 终止 ===
    record Done(int totalSteps, int totalTokens, double cost) implements ReActEvent {}
    record Error(String message, String detail) implements ReActEvent {}
}
