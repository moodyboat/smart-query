package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.service.WordReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Word报告生成控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/word-report")
@RequiredArgsConstructor
public class WordReportController {

    private final WordReportService wordReportService;

    /**
     * 从对话记录生成Word报告
     */
    @PostMapping("/conversation/{conversationId}")
    public Result<byte[]> generateFromConversation(
            @PathVariable Long conversationId,
            @RequestParam(required = false) String title) {
        try {
            byte[] wordDocument = wordReportService.generateReportFromConversation(
                conversationId,
                title != null ? title : "对话分析报告"
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData(
                "attachment",
                "analysis_report_" + conversationId + ".docx"
            );

            log.info("[WORD-REPORT] 成功生成对话报告: conversationId={}", conversationId);
            return Result.ok(wordDocument);

        } catch (Exception e) {
            log.error("[WORD-REPORT] 生成对话报告失败: conversationId={}", conversationId, e);
            return Result.error("生成Word报告失败: " + e.getMessage());
        }
    }

    /**
     * 下载Word文档（直接返回文件）
     */
    @GetMapping("/download/conversation/{conversationId}")
    public void downloadConversationReport(
            @PathVariable Long conversationId,
            @RequestParam(required = false) String title,
            HttpServletResponse response) throws java.io.IOException {
        try {
            byte[] wordDocument = wordReportService.generateReportFromConversation(
                conversationId,
                title != null ? title : "对话分析报告"
            );

            // 设置响应头强制浏览器下载文件
            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            response.setHeader("Content-Disposition", "attachment; filename=\"report_" + conversationId + ".docx\"");
            response.setContentLength(wordDocument.length);

            // 写入响应流
            response.getOutputStream().write(wordDocument);
            response.getOutputStream().flush();

            log.info("[WORD-REPORT] 成功下载对话报告: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("[WORD-REPORT] 下载对话报告失败: conversationId={}", conversationId, e);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"message\":\"生成Word报告失败: " + e.getMessage() + "\"}");
        }
    }
}