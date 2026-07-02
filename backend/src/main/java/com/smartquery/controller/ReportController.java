package com.smartquery.controller;

import com.smartquery.common.BusinessException;
import com.smartquery.common.Ownership;
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
    private final Ownership ownership;

    @GetMapping("/{id}")
    public Result<Report> get(@PathVariable Long id) {
        Report report = reportMapper.selectById(id);
        if (report == null) return Result.ok(null);
        if (!ownership.conversation(report.getConversationId())) {
            throw new BusinessException(403, "无权访问该报告");
        }
        return Result.ok(report);
    }

    @GetMapping("/conversation/{conversationId}")
    public Result<List<Report>> listByConversation(@PathVariable Long conversationId) {
        if (!ownership.conversation(conversationId)) {
            throw new BusinessException(403, "无权访问该会话");
        }
        return Result.ok(reportMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Report>()
                .eq(Report::getConversationId, conversationId)));
    }
}
