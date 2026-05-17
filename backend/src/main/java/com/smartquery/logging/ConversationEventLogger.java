package com.smartquery.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * JSONL 对话事件持久化 — 适配 Claude Code 会话日志模式
 *
 * <p>设计:
 * <ul>
 *   <li>每条 ReAct 事件实时追加写入 JSONL 文件</li>
 *   <li>按日期滚动: logs/conversations/{date}/{conversationId}.jsonl</li>
 *   <li>异步写入，不阻塞 ReAct 主循环</li>
 *   <li>支持 head/tail 流式读取和回放</li>
 * </ul>
 */
@Slf4j
@Component
public class ConversationEventLogger {

    private static final String BASE_DIR = "logs/conversations";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    /** Sensitive data patterns to redact from logs */
    private static final Pattern PATTERN_PASSWORD = Pattern.compile("(?i)(password|passwd|pwd)\\s*=\\s*[^\\s,;}&]+");
    private static final Pattern PATTERN_TOKEN = Pattern.compile("(?i)(token|access_token|refresh_token|bearer)\\s*=\\s*[^\\s,;}&]+");
    private static final Pattern PATTERN_API_KEY = Pattern.compile("(?i)(api_key|apikey|api[-_]secret)\\s*=\\s*[^\\s,;}&]+");
    private static final Pattern PATTERN_CONN_URL = Pattern.compile("(?i)(mysql|postgres|mongodb|redis|jdbc)://([^:]+):([^@]+)@");

    private final ObjectMapper objectMapper;
    private final ExecutorService writeExecutor;
    private final Map<String, PrintWriter> writerCache = new ConcurrentHashMap<>();

    public ConversationEventLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jsonl-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 记录一个对话事件
     */
    public void logEvent(Long conversationId, String traceId, String eventType, Map<String, Object> payload) {
        if (conversationId == null) return;

        Map<String, Object> sanitizedPayload = sanitizePayload(payload);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", LocalDateTime.now().format(TS_FMT));
        entry.put("traceId", traceId != null ? traceId : "");
        entry.put("conversationId", conversationId);
        entry.put("event", eventType);
        entry.put("payload", sanitizedPayload);

        writeExecutor.submit(() -> {
            try {
                rotateIfOversized(conversationId);
                PrintWriter writer = getWriter(conversationId);
                String json = objectMapper.writeValueAsString(entry);
                writer.println(json);
                writer.flush();
            } catch (Exception e) {
                log.debug("[JSONL] write failed: {}", e.getMessage());
            }
        });
    }

    /**
     * 读取某个对话的 JSONL 事件 (支持 head/tail)
     */
    public List<String> readEvents(Long conversationId, int limit, boolean fromTail) {
        Path path = findLatestFile(conversationId);
        if (path == null) return List.of();

        try {
            List<String> allLines = Files.readAllLines(path);
            if (fromTail) {
                int start = Math.max(0, allLines.size() - limit);
                return allLines.subList(start, allLines.size());
            }
            return allLines.subList(0, Math.min(limit, allLines.size()));
        } catch (IOException e) {
            log.debug("[JSONL] read failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询对话的完整事件列表
     */
    public List<Map<String, Object>> getConversationTrace(Long conversationId) {
        List<String> lines = readEvents(conversationId, Integer.MAX_VALUE, false);
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : lines) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                events.add(entry);
            } catch (Exception ignored) {}
        }
        return events;
    }

    /**
     * 按 traceId 查询事件链
     */
    public List<Map<String, Object>> getEventsByTraceId(Long conversationId, String traceId) {
        List<Map<String, Object>> all = getConversationTrace(conversationId);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> entry : all) {
            if (traceId.equals(entry.get("traceId"))) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Check if the current log file exceeds MAX_FILE_SIZE_BYTES and rotate it inline.
     * Runs on the write executor thread so no extra synchronization is needed.
     */
    private void rotateIfOversized(Long conversationId) {
        String dateKey = LocalDate.now().format(DATE_FMT);
        Path file = Paths.get(BASE_DIR, dateKey, conversationId + ".jsonl");
        if (!Files.exists(file)) return;

        try {
            if (Files.size(file) > maxFileSizeBytes) {
                // Close existing writer so buffers are flushed before rename
                String cacheKey = dateKey + "/" + conversationId;
                PrintWriter oldWriter = writerCache.remove(cacheKey);
                if (oldWriter != null) {
                    oldWriter.close();
                }

                String timestamp = String.valueOf(System.currentTimeMillis());
                Path rotated = Paths.get(BASE_DIR, dateKey,
                    conversationId + "." + timestamp + ".jsonl");
                Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
                log.info("[JSONL] rotated oversized file {} -> {}", file.getFileName(), rotated.getFileName());
            }
        } catch (IOException e) {
            log.debug("[JSONL] inline rotation check failed: {}", e.getMessage());
        }
    }

    private PrintWriter getWriter(Long conversationId) throws IOException {
        String dateKey = LocalDate.now().format(DATE_FMT);
        String cacheKey = dateKey + "/" + conversationId;

        return writerCache.computeIfAbsent(cacheKey, k -> {
            try {
                Path dir = Paths.get(BASE_DIR, dateKey);
                Files.createDirectories(dir);
                Path file = dir.resolve(conversationId + ".jsonl");
                FileWriter fw = new FileWriter(file.toFile(), true);
                return new PrintWriter(new BufferedWriter(fw), true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private Path findLatestFile(Long conversationId) {
        Path base = Paths.get(BASE_DIR);
        if (!Files.exists(base)) return null;

        try {
            List<Path> dateDirs = Files.list(base)
                .filter(Files::isDirectory)
                .sorted(Comparator.reverseOrder())
                .toList();

            for (Path dateDir : dateDirs) {
                Path file = dateDir.resolve(conversationId + ".jsonl");
                if (Files.exists(file)) return file;
            }
        } catch (IOException e) {
            log.debug("[JSONL] scan failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 JSONL 恢复对话消息 — 当 DB 消息缺失时回退到 JSONL 重建
     *
     * <p>增强策略:
     * <ul>
     *   <li>跨日期目录搜索，合并所有日期的 JSONL 文件</li>
     *   <li>assistant_chunk 事件自动合并为完整消息</li>
     *   <li>处理更多事件类型: thinking, sql_result, python_result, chart, report, dashboard</li>
     *   <li>保留 toolCallId 用于上下文重建</li>
     * </ul>
     */
    public List<Map<String, Object>> recoverConversation(Long conversationId) {
        List<Map<String, Object>> events = getAllEventsAcrossDates(conversationId);
        if (events.isEmpty()) return List.of();

        List<Map<String, Object>> messages = new ArrayList<>();
        StringBuilder assistantBuffer = new StringBuilder();
        String lastAssistantTs = null;

        for (Map<String, Object> event : events) {
            String eventType = (String) event.get("event");
            Map<String, Object> payload = event.get("payload") instanceof Map
                ? (Map<String, Object>) event.get("payload") : Map.of();
            String ts = (String) event.get("ts");

            // 遇到非 assistant 事件时 flush 缓冲
            if (!"assistant_chunk".equals(eventType) && !"thinking".equals(eventType)
                && assistantBuffer.length() > 0) {
                messages.add(Map.of("role", "assistant", "content", assistantBuffer.toString(),
                    "recoveredFrom", "jsonl", "ts", lastAssistantTs != null ? lastAssistantTs : ts));
                assistantBuffer.setLength(0);
                lastAssistantTs = null;
            }

            switch (eventType) {
                case "user_message" -> {
                    String content = (String) payload.get("content");
                    if (content != null) {
                        messages.add(Map.of("role", "user", "content", content, "recoveredFrom", "jsonl", "ts", ts));
                    }
                }
                case "assistant_chunk", "thinking" -> {
                    String content = (String) payload.get("content");
                    if (content != null && !content.isBlank()) {
                        assistantBuffer.append(content);
                        lastAssistantTs = ts;
                    }
                }
                case "tool_result" -> {
                    String toolName = (String) payload.get("tool");
                    String output = (String) payload.get("output");
                    String toolCallId = (String) payload.get("toolCallId");
                    if (toolName != null) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "tool");
                        msg.put("toolName", toolName);
                        msg.put("content", output != null ? output : "");
                        msg.put("recoveredFrom", "jsonl");
                        msg.put("ts", ts);
                        if (toolCallId != null) msg.put("toolCallId", toolCallId);
                        messages.add(msg);
                    }
                }
                case "sql_result" -> {
                    String sql = (String) payload.get("sql");
                    Object rows = payload.get("rows");
                    if (sql != null) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "tool");
                        msg.put("toolName", "execute_sql");
                        msg.put("content", "[SQL] " + sql + "\n结果: " + rows + " 行");
                        msg.put("recoveredFrom", "jsonl");
                        msg.put("ts", ts);
                        messages.add(msg);
                    }
                }
                case "python_result" -> {
                    Object output = payload.get("output");
                    if (output != null) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "tool");
                        msg.put("toolName", "execute_python");
                        msg.put("content", "[Python结果] " + output);
                        msg.put("recoveredFrom", "jsonl");
                        msg.put("ts", ts);
                        messages.add(msg);
                    }
                }
                case "chart_generated" -> {
                    String title = (String) payload.get("title");
                    if (title != null) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "tool");
                        msg.put("toolName", "create_chart");
                        msg.put("content", "[图表] " + title);
                        msg.put("recoveredFrom", "jsonl");
                        msg.put("ts", ts);
                        messages.add(msg);
                    }
                }
                case "report_generated", "dashboard_generated" -> {
                    String title = (String) payload.get("title");
                    if (title != null) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "tool");
                        msg.put("toolName", eventType.replace("_generated", ""));
                        msg.put("content", "[" + eventType.split("_")[0].toUpperCase() + "] " + title);
                        msg.put("recoveredFrom", "jsonl");
                        msg.put("ts", ts);
                        messages.add(msg);
                    }
                }
                default -> {} // skip trace/span/mining events
            }
        }

        // flush 最后的 assistant 缓冲
        if (assistantBuffer.length() > 0) {
            messages.add(Map.of("role", "assistant", "content", assistantBuffer.toString(),
                "recoveredFrom", "jsonl", "ts", lastAssistantTs != null ? lastAssistantTs : ""));
        }

        log.info("[JSONL] recovered {} messages from JSONL for conversation {}", messages.size(), conversationId);
        return messages;
    }

    /**
     * 跨日期目录搜索所有 JSONL 文件，按时间排序合并
     */
    private List<Map<String, Object>> getAllEventsAcrossDates(Long conversationId) {
        Path base = Paths.get(BASE_DIR);
        if (!Files.exists(base)) return List.of();

        List<Map<String, Object>> allEvents = new ArrayList<>();
        try {
            List<Path> dateDirs = Files.list(base)
                .filter(Files::isDirectory)
                .sorted()
                .toList();

            for (Path dateDir : dateDirs) {
                Path file = dateDir.resolve(conversationId + ".jsonl");
                if (!Files.exists(file)) continue;
                for (String line : Files.readAllLines(file)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                        allEvents.add(entry);
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            log.debug("[JSONL] cross-date scan failed: {}", e.getMessage());
        }
        return allEvents;
    }

    /**
     * 递归过滤 payload 中的敏感数据
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return payload != null ? payload : Map.of();

        Map<String, Object> sanitized = new LinkedHashMap<>(payload.size());
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof String s) {
            return sanitizeString(s);
        } else if (value instanceof Map<?, ?> m) {
            return sanitizePayload((Map<String, Object>) m);
        } else if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(sanitizeValue(item));
            }
            return result;
        }
        return value;
    }

    private String sanitizeString(String value) {
        String result = value;
        result = PATTERN_PASSWORD.matcher(result).replaceAll("$1=***");
        result = PATTERN_TOKEN.matcher(result).replaceAll("$1=***");
        result = PATTERN_API_KEY.matcher(result).replaceAll("$1=***");
        result = PATTERN_CONN_URL.matcher(result).replaceAll("$1://$2:***@");
        return result;
    }

    /**
     * 关闭所有 writer + 执行日志维护（压缩旧日志、清理过期日志）
     */
    @PreDestroy
    public void shutdown() {
        log.info("[JSONL] shutting down, flushing {} writers", writerCache.size());
        writeExecutor.shutdown();
        writerCache.values().forEach(PrintWriter::close);
        writerCache.clear();
        performLogMaintenance();
    }

    @org.springframework.beans.factory.annotation.Value("${logging.max-file-size-bytes:52428800}")
    private long maxFileSizeBytes;

    @org.springframework.beans.factory.annotation.Value("${logging.compress-after-days:30}")
    private int compressAfterDays;

    @org.springframework.beans.factory.annotation.Value("${logging.delete-after-days:90}")
    private int deleteAfterDays;

    /**
     * 日志维护: 滚动大文件 + 压缩旧日志 + 清理过期日志
     */
    public Map<String, Object> performLogMaintenance() {
        Map<String, Object> result = new LinkedHashMap<>();
        int rotated = 0, compressed = 0, deleted = 0;

        Path base = Paths.get(BASE_DIR);
        if (!Files.exists(base)) return result;

        try {
            List<Path> dateDirs = Files.list(base)
                .filter(Files::isDirectory)
                .toList();

            LocalDate compressThreshold = LocalDate.now().minusDays(compressAfterDays);
            LocalDate deleteThreshold = LocalDate.now().minusDays(deleteAfterDays);

            for (Path dateDir : dateDirs) {
                String dirName = dateDir.getFileName().toString();
                LocalDate dirDate;
                try {
                    dirDate = LocalDate.parse(dirName, DATE_FMT);
                } catch (Exception e) { continue; }

                // 清理超过 90 天的压缩文件
                if (dirDate.isBefore(deleteThreshold)) {
                    for (Path f : Files.list(dateDir).toList()) {
                        if (f.toString().endsWith(".gz")) {
                            Files.deleteIfExists(f);
                            deleted++;
                        }
                    }
                    continue;
                }

                // 压缩超过 30 天的未压缩文件
                if (dirDate.isBefore(compressThreshold)) {
                    for (Path f : Files.list(dateDir).toList()) {
                        if (f.toString().endsWith(".jsonl") && !f.toString().endsWith(".gz")) {
                            Path gzip = Path.of(f + ".gz");
                            compressFile(f, gzip);
                            Files.deleteIfExists(f);
                            compressed++;
                        }
                    }
                    continue;
                }

                // 滚动大文件
                for (Path f : Files.list(dateDir).toList()) {
                    if (f.toString().endsWith(".jsonl") && Files.size(f) > maxFileSizeBytes) {
                        Path rotatedPath = Path.of(f.toString().replace(".jsonl",
                            "-" + System.currentTimeMillis() + ".jsonl"));
                        Files.move(f, rotatedPath);
                        rotated++;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[JSONL] maintenance failed: {}", e.getMessage());
        }

        result.put("rotated", rotated);
        result.put("compressed", compressed);
        result.put("deleted", deleted);
        if (rotated + compressed + deleted > 0) {
            log.info("[JSONL] maintenance: rotated={}, compressed={}, deleted={}", rotated, compressed, deleted);
        }
        return result;
    }

    /**
     * 获取日志文件列表和大小（供管理 API 使用）
     */
    public List<Map<String, Object>> getLogFiles() {
        Path base = Paths.get(BASE_DIR);
        if (!Files.exists(base)) return List.of();

        List<Map<String, Object>> files = new ArrayList<>();
        try {
            Files.walk(base)
                .filter(Files::isRegularFile)
                .forEach(f -> {
                    try {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("path", base.relativize(f).toString());
                        info.put("sizeBytes", Files.size(f));
                        info.put("sizeMB", String.format("%.1f", Files.size(f) / (1024.0 * 1024.0)));
                        info.put("compressed", f.toString().endsWith(".gz"));
                        files.add(info);
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
        return files;
    }

    private void compressFile(Path source, Path target) {
        try (var fis = Files.newInputStream(source);
             var fos = Files.newOutputStream(target);
             var gzos = new java.util.zip.GZIPOutputStream(fos)) {
            fis.transferTo(gzos);
        } catch (IOException e) {
            log.warn("[JSONL] compression failed for {}: {}", source, e.getMessage());
        }
    }
}
