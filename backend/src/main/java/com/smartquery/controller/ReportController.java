package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.Report;
import com.smartquery.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportMapper reportMapper;

    @GetMapping("/{id}")
    public Result<Report> get(@PathVariable Long id) {
        return Result.ok(reportMapper.selectById(id));
    }

    @GetMapping("/conversation/{conversationId}")
    public Result<List<Report>> listByConversation(@PathVariable Long conversationId) {
        return Result.ok(reportMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Report>()
                .eq(Report::getConversationId, conversationId)));
    }
}
