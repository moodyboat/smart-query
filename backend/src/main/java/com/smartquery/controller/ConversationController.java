package com.smartquery.controller;

import com.smartquery.common.BusinessException;
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
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

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
    private final ResourceAccessService resourceAccess;

    @PostMapping
    public Result<Conversation> create(@RequestBody Conversation conversation) {
        // 强制以当前登录用户身份创建，防止伪造 userId 越权
        conversation.setId(null);
        conversation.setUserId(resourceAccess.currentUserId());
        conversationMapper.insert(conversation);
        return Result.ok(conversation);
    }

    @GetMapping
    public Result<List<Conversation>> list() {
        return Result.ok(resourceAccess.listConversations());
    }

    @GetMapping("/{id}")
    public Result<Conversation> get(@PathVariable Long id) {
        return Result.ok(resourceAccess.requireConversation(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resourceAccess.requireConversation(id);
        // Cascade delete related entities
        chatMessageMapper.delete(
            new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, id));
        chartMapper.delete(
            new LambdaQueryWrapper<Chart>().eq(Chart::getConversationId, id));
        reportMapper.delete(
            new LambdaQueryWrapper<Report>().eq(Report::getConversationId, id));
        dashboardMapper.delete(
            new LambdaQueryWrapper<Dashboard>().eq(Dashboard::getConversationId, id));
        conversationMapper.deleteById(id);
        return Result.ok(null);
    }

    @PostMapping("/batch-delete")
    public Result<Void> batchDelete(@RequestBody java.util.Map<String, java.util.List<Long>> body) {
        try {
            java.util.List<Long> ids = body.get("ids");
            if (ids == null || ids.isEmpty()) {
                return Result.error(400, "删除列表不能为空");
            }

            // Filter out null values
            ids = ids.stream().filter(id -> id != null).collect(java.util.stream.Collectors.toList());

            if (ids.isEmpty()) {
                return Result.error(400, "没有有效的对话ID");
            }

            // 校验整个批次归属：发现任何越权 id 立即拒绝，避免部分成功导致状态混乱
            for (Long id : ids) {
                resourceAccess.requireConversation(id);
            }

            java.util.Set<Long> failedIds = new java.util.HashSet<>();
            int deletedCount = 0;

            for (Long id : ids) {
                try {
                    // Cascade delete related entities for each conversation
                    chatMessageMapper.delete(
                        new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, id));
                    chartMapper.delete(
                        new LambdaQueryWrapper<Chart>().eq(Chart::getConversationId, id));
                    reportMapper.delete(
                        new LambdaQueryWrapper<Report>().eq(Report::getConversationId, id));
                    dashboardMapper.delete(
                        new LambdaQueryWrapper<Dashboard>().eq(Dashboard::getConversationId, id));
                    conversationMapper.deleteById(id);
                    deletedCount++;
                } catch (Exception e) {
                    // Log error but continue with other conversations
                    failedIds.add(id);
                    System.err.println("Failed to delete conversation " + id + ": " + e.getMessage());
                }
            }

            if (deletedCount == 0) {
                return Result.error(400, "没有找到可删除的对话");
            }

            if (!failedIds.isEmpty()) {
                return Result.error(400, "部分对话删除失败，请重试");
            }

            return Result.ok(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(500, "批量删除失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/title")
    public Result<Void> updateTitle(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) return Result.error("标题不能为空");
        if (title.length() > 100) title = title.substring(0, 100);
        Conversation conv = resourceAccess.requireConversation(id);
        conv.setTitle(title);
        conversationMapper.updateById(conv);
        return Result.ok(null);
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long id) {
        resourceAccess.requireConversation(id);
        List<ChatMessage> messages = chatMessageMapper.selectList(
            new LambdaQueryWrapper<ChatMessage>()
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
                    new LambdaQueryWrapper<ChatMessage>()
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
