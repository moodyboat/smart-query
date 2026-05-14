package com.smartquery.engine;

import com.smartquery.entity.ChatMessage;
import com.smartquery.entity.Conversation;
import com.smartquery.entity.QueryHistory;
import com.smartquery.llm.LlmService;
import com.smartquery.logging.CostTracker;
import com.smartquery.logging.QueryTracer;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.ConversationMapper;
import com.smartquery.mapper.QueryHistoryMapper;
import com.smartquery.prompt.QueryContextAssembler;
import com.smartquery.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryEngine {

    private final ReActEngine reActEngine;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final QueryHistoryMapper queryHistoryMapper;
    private final QueryTracer queryTracer;
    private final CostTracker costTracker;
    private final LlmService llmService;
    private final QueryContextAssembler contextAssembler;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 向后兼容: 收集所有事件后返回
     */
    public List<ReActEvent> submitMessage(
        Long conversationId,
        String userMessage,
        Long dataSourceId,
        String model
    ) {
        AtomicBoolean abortFlag = new AtomicBoolean(false);
        return submitMessage(conversationId, userMessage, dataSourceId, model, abortFlag::get);
    }

    public List<ReActEvent> submitMessage(
        Long conversationId,
        String userMessage,
        Long dataSourceId,
        String model,
        BooleanSupplier abortChecker
    ) {
        List<ReActEvent> events = new ArrayList<>();
        submitMessageStreaming(conversationId, userMessage, dataSourceId, model, abortChecker, events::add);
        return events;
    }

    /**
     * 流式输出: 每产生一个事件立即回调，用于 SSE 实时推送
     */
    public void submitMessageStreaming(
        Long conversationId,
        String userMessage,
        Long dataSourceId,
        String model,
        BooleanSupplier abortChecker,
        Consumer<ReActEvent> eventConsumer
    ) {
        String traceId = queryTracer.startTrace(conversationId, userMessage);
        long startTime = System.currentTimeMillis();

        log.info("[QUERY] conversation={}, question={}, model={}, traceId={}",
            conversationId, userMessage.substring(0, Math.min(userMessage.length(), 100)),
            model, traceId);

        // 1. 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        chatMessageMapper.insert(userMsg);

        // 自动更新会话标题
        autoUpdateTitle(conversationId, userMessage);

        // 2. 加载历史消息 (排除刚插入的当前消息)
        List<Map<String, Object>> history = loadHistory(conversationId, userMsg.getId());

        // 3. 运行 ReAct 循环 (实时回调)
        StringBuilder assistantContent = new StringBuilder();
        ConcurrentLinkedQueue<Map<String, Object>> toolBlocks = new ConcurrentLinkedQueue<>();
        Consumer<ReActEvent> wrappingConsumer = event -> {
            // 收集 assistant 文本用于持久化
            if (event instanceof ReActEvent.Thinking t) {
                assistantContent.append(t.content());
            }
            // 收集工具块用于 metadata
            collectToolBlock(toolBlocks, event);
            // 转发给调用者
            eventConsumer.accept(event);
        };

        try {
            reActEngine.runReActLoopStreaming(
                conversationId, model, dataSourceId, userMessage, history, abortChecker, wrappingConsumer);
        } catch (Exception e) {
            log.error("[QUERY] ReAct loop failed: {}", e.getMessage(), e);
            eventConsumer.accept(new ReActEvent.Error("处理失败", e.getMessage()));
        }

        // 4. 保存助手回复
        if (!assistantContent.isEmpty() || !toolBlocks.isEmpty()) {
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(assistantContent.toString());
            assistantMsg.setModel(model);
            if (!toolBlocks.isEmpty()) {
                try {
                    List<Map<String, Object>> blocks = List.copyOf(toolBlocks);
                    String json = objectMapper.writeValueAsString(blocks);
                    if (json.length() > 262144) {
                        // Step 1: Trim large SQL data rows
                        List<Map<String, Object>> trimmed = trimMetadataBlocks(blocks);
                        json = objectMapper.writeValueAsString(trimmed);
                    }
                    if (json != null && json.length() > 262144) {
                        // Step 2: Trim report section content and Python stdout
                        List<Map<String, Object>> trimmed = trimLargeTextFields(blocks);
                        json = objectMapper.writeValueAsString(trimmed);
                    }
                    if (json != null && json.length() > 262144) {
                        // Step 3: Strip ECharts options, keep only IDs and titles
                        List<Map<String, Object>> trimmed = stripEchartsOptions(blocks);
                        json = objectMapper.writeValueAsString(trimmed);
                    }
                    if (json != null && json.length() > 262144) {
                        log.warn("[QUERY] metadata still too large after trimming ({} chars), skipping", json.length());
                        json = null;
                    }
                    if (json != null) {
                        assistantMsg.setMetadata(json);
                    }
                } catch (Exception e) {
                    log.warn("[QUERY] Failed to serialize metadata: {}", e.getMessage());
                }
            }
            chatMessageMapper.insert(assistantMsg);
        }

        // 5. 保存查询历史
        QueryHistory historyRecord = new QueryHistory();
        historyRecord.setConversationId(conversationId);
        historyRecord.setMessageId(userMsg.getId());
        historyRecord.setTraceId(traceId);
        historyRecord.setQuestion(userMessage);
        historyRecord.setModel(model);
        long duration = System.currentTimeMillis() - startTime;
        queryHistoryMapper.insert(historyRecord);

        log.info("[QUERY] completed: duration={}ms, traceId={}", duration, traceId);
    }

    private void collectToolBlock(ConcurrentLinkedQueue<Map<String, Object>> toolBlocks, ReActEvent event) {
        // Result events: update the matching running block
        if (event instanceof ReActEvent.Result e) {
            updateLastRunningBlock(toolBlocks, "execute_sql", block -> {
                if (e.error() != null) {
                    block.put("result", Map.of(
                        "error", e.error(),
                        "totalRows", 0,
                        "rows", java.util.Collections.emptyList()
                    ));
                    block.put("status", "error");
                } else {
                    block.put("result", Map.of(
                        "summary", e.summary() != null ? e.summary() : "",
                        "totalRows", e.totalRows(),
                        "rows", e.data() != null ? e.data() : java.util.Collections.emptyList()
                    ));
                    block.put("status", "success");
                }
            });
            return;
        }
        if (event instanceof ReActEvent.PythonResultEvent e) {
            updateLastRunningBlock(toolBlocks, "execute_python", block -> {
                block.put("result", Map.of(
                    "stdout", e.stdout() != null ? e.stdout() : "",
                    "stderr", e.stderr() != null ? e.stderr() : "",
                    "exitCode", e.exitCode(),
                    "artifacts", e.artifacts() != null ? e.artifacts() : java.util.Collections.emptyList()
                ));
                block.put("status", e.exitCode() == 0 ? "success" : "error");
            });
            return;
        }

        // Non-result events: create new running blocks
        Map<String, Object> block = new LinkedHashMap<>();
        if (event instanceof ReActEvent.SqlExecuting e) {
            block.put("type", "tool_use");
            block.put("name", "execute_sql");
            block.put("_id", "sql-" + System.nanoTime());
            block.put("input", Map.of("sql", e.sql()));
            block.put("status", "running");
        } else if (event instanceof ReActEvent.PythonExecuting e) {
            block.put("type", "tool_use");
            block.put("name", "execute_python");
            block.put("_id", "py-" + System.nanoTime());
            block.put("input", Map.of("code", e.code()));
            block.put("status", "running");
        } else if (event instanceof ReActEvent.ChartGenerated e) {
            block.put("type", "tool_use");
            block.put("name", "generate_chart");
            block.put("_id", "chart-" + e.chartId());
            block.put("status", "success");
            block.put("result", Map.of(
                "chartId", e.chartId(),
                "title", e.title() != null ? e.title() : "",
                "chartType", e.chartType() != null ? e.chartType() : "",
                "echartsOption", e.echartsOption() != null ? e.echartsOption() : ""
            ));
        } else if (event instanceof ReActEvent.DashboardGenerated e) {
            block.put("type", "tool_use");
            block.put("name", "generate_dashboard");
            block.put("_id", "dash-" + e.dashboardId());
            block.put("status", "success");
            block.put("result", Map.of(
                "dashboardId", e.dashboardId(),
                "title", e.title() != null ? e.title() : "",
                "layout", e.layout() != null ? e.layout() : "",
                "chartIds", e.chartIds() != null ? e.chartIds() : java.util.Collections.emptyList()
            ));
        } else if (event instanceof ReActEvent.ReportGenerated e) {
            block.put("type", "tool_use");
            block.put("name", "generate_report");
            block.put("_id", "report-" + e.reportId());
            block.put("status", "success");
            java.util.Map<String, Object> reportResult = new java.util.LinkedHashMap<>();
            reportResult.put("reportId", e.reportId());
            reportResult.put("title", e.title() != null ? e.title() : "");
            reportResult.put("sections", e.sections() != null ? e.sections() : java.util.Collections.emptyList());
            reportResult.put("conclusion", e.conclusion() != null ? e.conclusion() : "");
            block.put("result", reportResult);
        } else if (event instanceof ReActEvent.FilterWidgetsGenerated e) {
            block.put("type", "tool_use");
            block.put("name", "generate_filter_widgets");
            block.put("_id", "filter-" + System.nanoTime());
            block.put("status", "success");
            try {
                Map<String, Object> parsed = objectMapper.readValue(e.widgetsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                block.put("result", Map.of(
                    "widgets", parsed.getOrDefault("widgets", java.util.Collections.emptyList()),
                    "bindings", parsed.getOrDefault("bindings", java.util.Collections.emptyList()),
                    "baseSql", parsed.getOrDefault("baseSql", ""),
                    "targetType", parsed.getOrDefault("targetType", ""),
                    "targetId", parsed.getOrDefault("targetId", "")
                ));
            } catch (Exception ex) {
                block.put("result", Map.of("widgets", java.util.Collections.emptyList()));
            }
        } else {
            return;
        }
        if (!block.isEmpty()) {
            toolBlocks.add(block);
        }
    }

    private void updateLastRunningBlock(ConcurrentLinkedQueue<Map<String, Object>> toolBlocks,
                                         String toolName, java.util.function.Consumer<Map<String, Object>> updater) {
        List<Map<String, Object>> copy = List.copyOf(toolBlocks);
        for (int i = copy.size() - 1; i >= 0; i--) {
            Map<String, Object> b = copy.get(i);
            if (toolName.equals(b.get("name")) && "running".equals(b.get("status"))) {
                updater.accept(b);
                return;
            }
        }
    }

    private void autoUpdateTitle(Long conversationId, String userMessage) {
        try {
            Conversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) return;
            if (conv.getTitle() != null && !"新对话".equals(conv.getTitle()) && !conv.getTitle().isBlank()) return;

            String title = extractTitle(userMessage);
            conv.setTitle(title);
            conversationMapper.updateById(conv);
        } catch (Exception e) {
            log.debug("[QUERY] auto-title update skipped: {}", e.getMessage());
        }
    }

    private String extractTitle(String message) {
        String t = message.trim();
        // Strip common filler prefixes
        t = t.replaceAll("^(请)?(帮我|帮我来|帮忙)?(用|使用)?", "");
        // Strip tool mentions
        t = t.replaceAll("^(Python|python|SQL|sql)\\s*(分析|画|写|生成|执行)?\\s*", "");
        t = t.replaceAll("^(帮我|请|麻烦|能不能|可以)\\s*", "");
        // Take first sentence (up to 。！？, or newline)
        int cut = t.indexOf('。');
        if (cut > 0 && cut < t.length() - 1) t = t.substring(0, cut);
        cut = t.indexOf('！');
        if (cut > 0 && cut < t.length() - 1) t = t.substring(0, cut);
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl);
        // Cap at 20 chars
        if (t.length() > 20) t = t.substring(0, 20) + "...";
        return t.isBlank() ? message.substring(0, Math.min(message.length(), 20)) : t;
    }

    private List<Map<String, Object>> loadHistory(Long conversationId, Long excludeAfterId) {
        var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
            .eq(ChatMessage::getConversationId, conversationId)
            .orderByAsc(ChatMessage::getCreatedAt);
        // Exclude the current user message to avoid duplication
        if (excludeAfterId != null) {
            query.lt(ChatMessage::getId, excludeAfterId);
        }
        List<ChatMessage> messages = chatMessageMapper.selectList(query);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", msg.getRole());

            // For assistant messages with metadata, include tool context in content
            if ("assistant".equals(msg.getRole()) && msg.getMetadata() != null && !msg.getMetadata().isBlank()) {
                StringBuilder enriched = new StringBuilder();
                if (msg.getContent() != null && !msg.getContent().isBlank()) {
                    enriched.append(msg.getContent());
                }
                try {
                    java.util.List<Map<String, Object>> blocks = objectMapper.readValue(
                        msg.getMetadata(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() {}
                    );
                    for (Map<String, Object> block : blocks) {
                        String name = (String) block.get("name");
                        String status = (String) block.get("status");
                        if ("execute_sql".equals(name)) {
                            Map<String, Object> input = (Map<String, Object>) block.get("input");
                            if (input != null && input.get("sql") != null) {
                                enriched.append("\n\n[SQL] ").append(input.get("sql"));
                            }
                            Map<String, Object> res = (Map<String, Object>) block.get("result");
                            if (res != null) {
                                enriched.append("\n[结果] ").append(res.getOrDefault("totalRows", 0)).append(" 行");
                                enriched.append(" ").append(res.getOrDefault("summary", ""));
                            }
                        } else if ("execute_python".equals(name)) {
                            Map<String, Object> input = (Map<String, Object>) block.get("input");
                            if (input != null && input.get("code") != null) {
                                enriched.append("\n\n[Python] ").append(input.get("code"));
                            }
                            Map<String, Object> res = (Map<String, Object>) block.get("result");
                            if (res != null) {
                                int exitCode = res.get("exitCode") instanceof Number n ? n.intValue() : 0;
                                if (exitCode != 0) {
                                    enriched.append("\n[错误] ").append(res.getOrDefault("stderr", ""));
                                } else {
                                    enriched.append("\n[输出] ").append(res.getOrDefault("stdout", ""));
                                }
                            }
                        } else if ("generate_chart".equals(name)) {
                            Map<String, Object> res = (Map<String, Object>) block.get("result");
                            if (res != null) {
                                enriched.append("\n[图表] ID=").append(res.get("chartId"))
                                    .append(" ").append(res.getOrDefault("title", ""));
                            }
                        } else if ("generate_report".equals(name)) {
                            Map<String, Object> res = (Map<String, Object>) block.get("result");
                            if (res != null) {
                                enriched.append("\n[报告] ID=").append(res.get("reportId"))
                                    .append(" ").append(res.getOrDefault("title", ""));
                                Object sections = res.get("sections");
                                if (sections instanceof List<?> secList && !secList.isEmpty()) {
                                    enriched.append(" 章节: ");
                                    for (Object s : secList) {
                                        if (s instanceof Map<?, ?> sec) {
                                            Object title = sec.get("section_title");
                                            if (title == null) title = sec.get("title");
                                            enriched.append(title).append(", ");
                                        }
                                    }
                                    enriched.setLength(enriched.length() - 2);
                                }
                            }
                        } else if ("generate_dashboard".equals(name)) {
                            Map<String, Object> res = (Map<String, Object>) block.get("result");
                            if (res != null) {
                                enriched.append("\n[仪表盘] ID=").append(res.get("dashboardId"))
                                    .append(" 标题=").append(res.getOrDefault("title", ""));
                                Object chartIds = res.get("chartIds");
                                if (chartIds instanceof java.util.List<?> ids && !ids.isEmpty()) {
                                    enriched.append(" 包含图表: ").append(ids);
                                }
                            }
                        } else if ("generate_filter_widgets".equals(name)) {
                            Map<String, Object> res = (Map<String, Object>) block.get("result");
                            if (res != null) {
                                enriched.append("\n[筛选控件] 目标=").append(res.getOrDefault("targetType", ""))
                                    .append("#").append(res.getOrDefault("targetId", ""));
                                Object widgets = res.get("widgets");
                                if (widgets instanceof java.util.List<?> ws && !ws.isEmpty()) {
                                    enriched.append(" ").append(ws.size()).append("个控件");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[QUERY] metadata enrichment skipped: {}", e.getMessage());
                }
                m.put("content", enriched.toString());
            } else {
                m.put("content", msg.getContent());
            }
            result.add(m);
        }

        // Keep only recent messages to stay within token budget
        // Rough estimate: each message ~500 tokens, keep last 20 pairs (40 messages)
        if (result.size() > 40) {
            result = result.subList(result.size() - 40, result.size());
        }

        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> trimMetadataBlocks(List<Map<String, Object>> blocks) {
        return blocks.stream().map(block -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(block);
            Map<String, Object> result = (Map<String, Object>) copy.get("result");
            if (result != null && result.get("rows") instanceof List rows && rows.size() > 50) {
                Map<String, Object> trimmedResult = new java.util.LinkedHashMap<>(result);
                trimmedResult.put("rows", rows.subList(0, 50));
                trimmedResult.put("rowsTruncated", true);
                copy.put("result", trimmedResult);
            }
            return copy;
        }).collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> trimLargeTextFields(List<Map<String, Object>> blocks) {
        return blocks.stream().map(block -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(block);
            if (!"generate_report".equals(block.get("name"))) return copy;
            Map<String, Object> result = (Map<String, Object>) copy.get("result");
            if (result == null) return copy;
            List sections = (List) result.get("sections");
            if (sections == null) return copy;
            List trimmedSections = new java.util.ArrayList();
            for (Object s : sections) {
                if (!(s instanceof Map sec)) { trimmedSections.add(s); continue; }
                Map trimmedSec = new java.util.LinkedHashMap<>(sec);
                for (String key : java.util.List.of("section_content", "content")) {
                    Object val = trimmedSec.get(key);
                    if (val instanceof String text && text.length() > 2000) {
                        trimmedSec.put(key, text.substring(0, 2000) + "\n...(已截断)");
                    }
                }
                trimmedSections.add(trimmedSec);
            }
            Map<String, Object> trimmedResult = new java.util.LinkedHashMap<>(result);
            trimmedResult.put("sections", trimmedSections);
            copy.put("result", trimmedResult);
            return copy;
        }).map(block -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(block);
            if (!"execute_python".equals(block.get("name"))) return copy;
            Map<String, Object> result = (Map<String, Object>) copy.get("result");
            if (result == null) return copy;
            Object stdout = result.get("stdout");
            if (stdout instanceof String text && text.length() > 3000) {
                Map<String, Object> trimmedResult = new java.util.LinkedHashMap<>(result);
                trimmedResult.put("stdout", text.substring(0, 3000) + "\n...(已截断)");
                copy.put("result", trimmedResult);
            }
            return copy;
        }).collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> stripEchartsOptions(List<Map<String, Object>> blocks) {
        return blocks.stream().map(block -> {
            if (!"generate_chart".equals(block.get("name"))) return block;
            Map<String, Object> copy = new java.util.LinkedHashMap<>(block);
            Map<String, Object> result = (Map<String, Object>) copy.get("result");
            if (result == null) return copy;
            Map<String, Object> trimmedResult = new java.util.LinkedHashMap<>(result);
            trimmedResult.remove("echartsOption");
            trimmedResult.put("echartsOptionTruncated", true);
            copy.put("result", trimmedResult);
            return copy;
        }).collect(java.util.stream.Collectors.toList());
    }
}
