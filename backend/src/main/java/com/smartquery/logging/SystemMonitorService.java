package com.smartquery.logging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Provides a small, dependency-free operational snapshot for the admin workbench. */
@Service
public class SystemMonitorService {

    private final JdbcTemplate jdbcTemplate;

    public SystemMonitorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedAt", Instant.now().toString());
        result.put("runtime", runtimeMetrics());
        result.put("services", serviceHealth());
        result.put("business", businessMetrics());
        result.put("trainingStatuses", trainingStatuses());
        List<Map<String, Object>> conversations = conversationHistory();
        List<Map<String, Object>> operations = operationHistory();
        List<Map<String, Object>> executions = executionRecords();
        result.put("conversationHistory", conversations);
        result.put("operationHistory", operations);
        result.put("executionRecords", executions);
        result.put("historyTotals", historyTotals(conversations, operations));
        result.put("trends", historyTrends(operations, executions));
        result.put("traces", traceSummaries(operations));
        result.put("errors", errorAggregation(operations, executions));
        result.put("trainingResources", trainingResources(executions));
        result.put("alerts", alerts(result, executions));
        result.put("permissionScope", "database-rbac");
        return result;
    }

    private Map<String, Object> runtimeMetrics() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        java.lang.management.OperatingSystemMXBean baseOs = ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("startedAt", Instant.ofEpochMilli(runtime.getStartTime()).toString());
        metrics.put("uptimeMs", runtime.getUptime());
        metrics.put("processors", baseOs.getAvailableProcessors());
        metrics.put("systemLoadAverage", round(baseOs.getSystemLoadAverage()));
        if (baseOs instanceof com.sun.management.OperatingSystemMXBean os) {
            metrics.put("processCpuPercent", percent(os.getProcessCpuLoad()));
            metrics.put("systemCpuPercent", percent(os.getCpuLoad()));
            metrics.put("physicalMemoryUsed", os.getTotalMemorySize() - os.getFreeMemorySize());
            metrics.put("physicalMemoryTotal", os.getTotalMemorySize());
        }
        metrics.put("heapUsed", heap.getUsed());
        metrics.put("heapMax", heap.getMax());
        metrics.put("nonHeapUsed", nonHeap.getUsed());
        metrics.put("threadCount", threads.getThreadCount());
        metrics.put("peakThreadCount", threads.getPeakThreadCount());

        File workspace = new File(System.getProperty("user.dir", "."));
        metrics.put("diskUsed", workspace.getTotalSpace() - workspace.getUsableSpace());
        metrics.put("diskTotal", workspace.getTotalSpace());
        return metrics;
    }

    private List<Map<String, Object>> serviceHealth() {
        List<Map<String, Object>> services = new ArrayList<>();
        services.add(health("后端服务", true, "JVM 正常运行"));
        long started = System.nanoTime();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            services.add(health("系统数据库", true,
                "响应 " + Math.max(0, (System.nanoTime() - started) / 1_000_000) + " ms"));
        } catch (Exception e) {
            services.add(health("系统数据库", false, safeMessage(e)));
        }
        return services;
    }

    private Map<String, Object> businessMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("users", safeCount("SELECT COUNT(*) FROM sq_user WHERE deleted = 0"));
        metrics.put("conversations", safeCount("SELECT COUNT(*) FROM sq_conversation WHERE deleted = 0"));
        metrics.put("models", safeCount("SELECT COUNT(*) FROM sq_mining_model WHERE deleted = 0"));
        metrics.put("pipelines", safeCount("SELECT COUNT(*) FROM sq_mining_pipeline WHERE deleted = 0"));
        metrics.put("algorithms", safeCount("SELECT COUNT(*) FROM sq_algorithm WHERE deleted = 0"));
        return metrics;
    }

    private List<Map<String, Object>> trainingStatuses() {
        try {
            return jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS count FROM sq_model_execution GROUP BY status")
                .stream().map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("status", String.valueOf(row.get("status")));
                    Object count = row.get("count");
                    if (count == null) count = row.get("COUNT(*)");
                    item.put("count", count == null ? 0 : count);
                    return item;
                }).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> conversationHistory() {
        return limitedRows("""
            SELECT c.id AS conversation_id, c.title, c.user_id,
                   COUNT(m.id) AS message_count,
                   COALESCE(SUM(m.token_count), 0) AS total_tokens,
                   MAX(m.created_at) AS last_activity
              FROM sq_conversation c
              LEFT JOIN sq_chat_message m ON m.conversation_id = c.id
             WHERE c.deleted = 0
             GROUP BY c.id, c.title, c.user_id
             ORDER BY last_activity DESC
            """, 100);
    }

    private List<Map<String, Object>> operationHistory() {
        List<Map<String, Object>> rows = limitedRows("""
            SELECT q.id, q.conversation_id, c.user_id, q.trace_id, q.question,
                   q.generated_sql, q.status, q.execution_time_ms, q.total_tokens,
                   q.error_message, q.created_at
              FROM sq_query_history q
              LEFT JOIN sq_conversation c ON c.id = q.conversation_id
             ORDER BY q.created_at DESC
            """, 200);
        rows.forEach(row -> {
            row.put("question", truncate(row.get("question"), 240));
            row.put("generated_sql", truncate(row.get("generated_sql"), 500));
            row.put("error_message", truncate(row.get("error_message"), 300));
            row.put("operation_type", "QUERY");
        });
        return rows;
    }

    private List<Map<String, Object>> executionRecords() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> modelRows = limitedRows("""
            SELECT e.id, e.model_id, m.name AS resource_name, e.triggered_by_user_id AS user_id,
                   e.trigger_type, e.status, e.progress_percent, e.current_stage,
                   e.progress_message, e.execution_time_ms, e.started_at, e.finished_at, e.created_at
              FROM sq_model_execution e
              LEFT JOIN sq_mining_model m ON m.id = e.model_id
             ORDER BY e.created_at DESC
            """, 150);
        modelRows.forEach(row -> row.put("execution_type", "MODEL_TRAINING"));
        result.addAll(modelRows);

        List<Map<String, Object>> pythonRows = limitedRows("""
            SELECT p.id, p.conversation_id, c.user_id, p.status, p.execution_time_ms,
                   p.exit_code, p.created_at
              FROM sq_python_execution p
              LEFT JOIN sq_conversation c ON c.id = p.conversation_id
             ORDER BY p.created_at DESC
            """, 100);
        pythonRows.forEach(row -> {
            row.put("execution_type", "PYTHON");
            row.put("resource_name", "Python #" + row.get("id"));
        });
        result.addAll(pythonRows);
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("created_at")), Comparator.reverseOrder()));
        return result.stream().limit(200).toList();
    }

    private Map<String, Object> historyTotals(List<Map<String, Object>> conversations,
                                              List<Map<String, Object>> operations) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("conversations", conversations.size());
        totals.put("messageTokens", conversations.stream().mapToLong(row -> number(row.get("total_tokens"))).sum());
        totals.put("queryTokens", operations.stream().mapToLong(row -> number(row.get("total_tokens"))).sum());
        totals.put("operations", operations.size());
        return totals;
    }

    private List<Map<String, Object>> historyTrends(List<Map<String, Object>> operations,
                                                     List<Map<String, Object>> executions) {
        LocalDate today = LocalDate.now();
        Map<LocalDate, Map<String, Object>> days = new LinkedHashMap<>();
        for (int offset = 13; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day.toString());
            item.put("operations", 0L);
            item.put("tokens", 0L);
            item.put("errors", 0L);
            item.put("trainings", 0L);
            days.put(day, item);
        }
        operations.forEach(row -> {
            Map<String, Object> item = days.get(toLocalDate(row.get("created_at")));
            if (item == null) return;
            increment(item, "operations", 1);
            increment(item, "tokens", number(row.get("total_tokens")));
            if (!"success".equalsIgnoreCase(String.valueOf(row.get("status")))) increment(item, "errors", 1);
        });
        executions.stream()
            .filter(row -> "MODEL_TRAINING".equals(row.get("execution_type")))
            .forEach(row -> {
                Map<String, Object> item = days.get(toLocalDate(row.get("created_at")));
                if (item != null) increment(item, "trainings", 1);
            });
        return new ArrayList<>(days.values());
    }

    private List<Map<String, Object>> traceSummaries(List<Map<String, Object>> operations) {
        Map<String, List<Map<String, Object>>> grouped = operations.stream()
            .filter(row -> row.get("trace_id") != null && !String.valueOf(row.get("trace_id")).isBlank())
            .collect(Collectors.groupingBy(row -> String.valueOf(row.get("trace_id")), LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().limit(50).map(entry -> {
            List<Map<String, Object>> rows = entry.getValue();
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("trace_id", entry.getKey());
            trace.put("conversation_id", rows.get(0).get("conversation_id"));
            trace.put("user_id", rows.get(0).get("user_id"));
            trace.put("event_count", rows.size());
            trace.put("duration_ms", rows.stream().mapToLong(row -> number(row.get("execution_time_ms"))).sum());
            trace.put("status", rows.stream().anyMatch(row -> !"success".equalsIgnoreCase(String.valueOf(row.get("status")))) ? "error" : "success");
            trace.put("created_at", rows.get(0).get("created_at"));
            return trace;
        }).toList();
    }

    private List<Map<String, Object>> errorAggregation(List<Map<String, Object>> operations,
                                                        List<Map<String, Object>> executions) {
        Map<String, Long> errors = new LinkedHashMap<>();
        operations.stream()
            .filter(row -> !"success".equalsIgnoreCase(String.valueOf(row.get("status"))))
            .forEach(row -> errors.merge(errorKey("QUERY", row.get("error_message")), 1L, Long::sum));
        executions.stream()
            .filter(row -> {
                String status = String.valueOf(row.get("status"));
                return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status) || "timeout".equalsIgnoreCase(status);
            })
            .forEach(row -> errors.merge(errorKey(String.valueOf(row.get("execution_type")), row.get("progress_message")), 1L, Long::sum));
        return errors.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(20)
            .map(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("error", entry.getKey());
                item.put("count", entry.getValue());
                return item;
            }).toList();
    }

    private Map<String, Object> trainingResources(List<Map<String, Object>> executions) {
        List<Map<String, Object>> training = executions.stream()
            .filter(row -> "MODEL_TRAINING".equals(row.get("execution_type")))
            .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", training.stream().filter(row -> "running".equalsIgnoreCase(String.valueOf(row.get("status")))).count());
        result.put("queued", training.stream().filter(row -> "queued".equalsIgnoreCase(String.valueOf(row.get("status")))).count());
        result.put("recent", training.stream().limit(30).toList());
        result.put("perTaskCpuMemoryAvailable", false);
        result.put("telemetryNote", "当前已记录任务进度和耗时；单任务 CPU/内存需由独立沙箱 Worker 上报。");
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> alerts(Map<String, Object> snapshot,
                                             List<Map<String, Object>> executions) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        Map<String, Object> runtime = (Map<String, Object>) snapshot.get("runtime");
        addThresholdAlert(alerts, "CPU", numberDouble(runtime.get("systemCpuPercent")), 85);
        addThresholdAlert(alerts, "物理内存", ratioPercent(runtime.get("physicalMemoryUsed"), runtime.get("physicalMemoryTotal")), 85);
        addThresholdAlert(alerts, "磁盘", ratioPercent(runtime.get("diskUsed"), runtime.get("diskTotal")), 85);
        ((List<Map<String, Object>>) snapshot.get("services")).stream()
            .filter(service -> !"UP".equals(service.get("status")))
            .forEach(service -> alerts.add(alert("critical", service.get("name") + " 不可用", String.valueOf(service.get("detail")))));
        long recentFailures = executions.stream().limit(50).filter(row -> {
            String status = String.valueOf(row.get("status"));
            return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status) || "timeout".equalsIgnoreCase(status);
        }).count();
        if (recentFailures > 0) alerts.add(alert("warning", "近期执行失败", "最近 50 条执行中有 " + recentFailures + " 条失败/超时"));
        return alerts;
    }

    private List<Map<String, Object>> limitedRows(String sql, int limit) {
        try {
            return jdbcTemplate.queryForList(sql).stream()
                .map(this::normaliseRow)
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> normaliseRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(), value));
        return result;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        if (value instanceof java.util.Date date) return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        if (value != null) {
            String text = String.valueOf(value);
            if (text.length() >= 10) {
                try { return LocalDate.parse(text.substring(0, 10)); } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private void increment(Map<String, Object> item, String key, long delta) {
        item.put(key, number(item.get(key)) + delta);
    }

    private long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private double numberDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : -1;
    }

    private double ratioPercent(Object used, Object total) {
        double denominator = numberDouble(total);
        return denominator <= 0 ? -1 : numberDouble(used) / denominator * 100;
    }

    private void addThresholdAlert(List<Map<String, Object>> alerts, String name, double value, double threshold) {
        if (value >= threshold) alerts.add(alert("warning", name + " 使用率过高", String.format("%.1f%% ≥ %.0f%%", value, threshold)));
    }

    private Map<String, Object> alert(String severity, String title, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", severity);
        result.put("title", title);
        result.put("detail", detail);
        return result;
    }

    private String errorKey(String type, Object message) {
        String detail = truncate(message, 100);
        return type + ": " + (detail == null || detail.isBlank() ? "未提供错误详情" : detail);
    }

    private String truncate(Object value, int maxLength) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private long safeCount(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<String, Object> health(String name, boolean up, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", up ? "UP" : "DOWN");
        item.put("detail", detail);
        return item;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private double percent(double value) {
        return value < 0 ? -1 : Math.round(value * 10_000.0) / 100.0;
    }

    private double round(double value) {
        return value < 0 ? -1 : Math.round(value * 100.0) / 100.0;
    }
}
