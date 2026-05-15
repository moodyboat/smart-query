package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.ChatMessage;
import com.smartquery.entity.Conversation;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.ConversationMapper;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.mapper.ReportMapper;
import com.smartquery.mapper.DashboardMapper;
import com.smartquery.entity.Chart;
import com.smartquery.entity.Report;
import com.smartquery.entity.Dashboard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChartMapper chartMapper;
    private final ReportMapper reportMapper;
    private final DashboardMapper dashboardMapper;
    private final ConversationEventLogger eventLogger;

    @PostMapping
    public Result<Conversation> create(@RequestBody Conversation conversation) {
        conversationMapper.insert(conversation);
        return Result.ok(conversation);
    }

    @GetMapping
    public Result<List<Conversation>> list() {
        return Result.ok(conversationMapper.selectList(null));
    }

    @GetMapping("/{id}")
    public Result<Conversation> get(@PathVariable Long id) {
        return Result.ok(conversationMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        // Cascade delete related entities
        chatMessageMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, id));
        chartMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chart>()
                .eq(Chart::getConversationId, id));
        reportMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Report>()
                .eq(Report::getConversationId, id));
        dashboardMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Dashboard>()
                .eq(Dashboard::getConversationId, id));
        conversationMapper.deleteById(id);
        return Result.ok(null);
    }

    @PutMapping("/{id}/title")
    public Result<Void> updateTitle(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) return Result.error("标题不能为空");
        if (title.length() > 100) title = title.substring(0, 100);
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null) return Result.error("对话不存在");
        conv.setTitle(title);
        conversationMapper.updateById(conv);
        return Result.ok(null);
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long id) {
        List<ChatMessage> messages = chatMessageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, id)
                .orderByAsc(ChatMessage::getCreatedAt));

        // DB 完全为空时，尝试从 JSONL 恢复
        if (messages.isEmpty()) {
            List<java.util.Map<String, Object>> recovered = eventLogger.recoverConversation(id);
            if (!recovered.isEmpty()) {
                for (java.util.Map<String, Object> msg : recovered) {
                    ChatMessage cm = new ChatMessage();
                    cm.setConversationId(id);
                    cm.setRole((String) msg.get("role"));
                    cm.setContent((String) msg.get("content"));
                    if (msg.get("toolName") != null) {
                        cm.setToolName((String) msg.get("toolName"));
                    }
                    chatMessageMapper.insert(cm);
                }
                messages = chatMessageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .orderByAsc(ChatMessage::getCreatedAt));

                // 记录恢复事件
                eventLogger.logEvent(id, null, "session_recovered",
                    java.util.Map.of("source", "jsonl", "recoveredCount", recovered.size()));
                eventLogger.logEvent(id, null, "session_recovered",
                    java.util.Map.of("source", "jsonl", "persistedCount", messages.size()));
            }
        }

        return Result.ok(messages);
    }
}
