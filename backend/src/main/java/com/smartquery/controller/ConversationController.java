package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.ChatMessage;
import com.smartquery.entity.Conversation;
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

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long id) {
        List<ChatMessage> messages = chatMessageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, id)
                .orderByAsc(ChatMessage::getCreatedAt));
        return Result.ok(messages);
    }
}
