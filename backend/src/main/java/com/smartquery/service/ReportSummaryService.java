package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.llm.LlmService;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.entity.Chart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话内容智能总结服务
 * 使用LLM将对话记录转换为结构化的报告内容
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportSummaryService {

    private final LlmService llmService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.default-model:glm-5.1}")
    private String defaultModel;

    /**
     * 生成对话总结报告
     * @param conversationId 对话ID
     * @return 总结报告数据
     */
    public SummaryReport generateSummary(Long conversationId) {
        List<Map<String, Object>> messages = chatMessageMapper.selectMessagesByConversation(conversationId);

        if (messages.isEmpty()) {
            return createEmptySummary();
        }

        // 提取对话中的关键信息
        ConversationContext context = extractContext(messages, conversationId);

        // 构建总结提示词
        String summaryPrompt = buildSummaryPrompt(context);

        try {
            // 调用LLM生成总结
            String summaryResult = llmService.chat(getDefaultModel(), buildMessages(summaryPrompt));

            // 解析LLM返回的JSON结构
            return parseSummaryResult(summaryResult, context);
        } catch (Exception e) {
            log.error("[SUMMARY] 生成总结失败: conversationId={}", conversationId, e);
            return createFallbackSummary(context);
        }
    }

    /**
     * 提取对话上下文
     */
    private ConversationContext extractContext(List<Map<String, Object>> messages, Long conversationId) {
        ConversationContext context = new ConversationContext();
        context.conversationId = conversationId;

        List<String> userQuestions = new ArrayList<>();
        List<String> aiResponses = new ArrayList<>();
        List<ChartInfo> charts = new ArrayList<>();

        for (Map<String, Object> message : messages) {
            String role = (String) message.get("role");
            String content = (String) message.get("content");

            if ("user".equals(role)) {
                userQuestions.add(content);
            } else if ("assistant".equals(role) && content != null) {
                aiResponses.add(content);

                // 尝试从内容中提取图表信息（如果有）
                extractChartsFromContent(content, conversationId, charts);
            }
        }

        // 从数据库查询该对话的所有图表
        try {
            List<Chart> dbCharts = chartMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chart>()
                    .eq(Chart::getConversationId, conversationId)
                    .orderByDesc(Chart::getCreatedAt)
            );

            for (Chart chart : dbCharts) {
                ChartInfo info = new ChartInfo();
                info.id = chart.getId();
                info.title = chart.getTitle();
                info.chartType = chart.getChartType();
                info.echartsOption = chart.getEchartsOption();
                info.imagePath = chart.getImagePath();
                info.baseSql = chart.getBaseSql();
                charts.add(info);
            }
        } catch (Exception e) {
            log.warn("[SUMMARY] 查询图表失败: conversationId={}", conversationId, e);
        }

        context.userQuestions = userQuestions;
        context.aiResponses = aiResponses;
        context.charts = charts;

        return context;
    }

    /**
     * 构建总结提示词
     */
    private String buildSummaryPrompt(ConversationContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# 数据分析对话总结\n\n");
        prompt.append("你是一位专业的数据分析师，需要将用户的对话记录转换为一份结构化的分析报告。\n\n");

        prompt.append("## 对话背景\n");
        prompt.append("对话ID: ").append(context.conversationId).append("\n");
        prompt.append("用户问题数: ").append(context.userQuestions.size()).append("\n");
        prompt.append("生成图表数: ").append(context.charts.size()).append("\n\n");

        prompt.append("## 用户问题列表\n");
        for (int i = 0; i < context.userQuestions.size(); i++) {
            prompt.append((i + 1)).append(". ").append(context.userQuestions.get(i)).append("\n");
        }
        prompt.append("\n");

        prompt.append("## 图表列表\n");
        if (context.charts.isEmpty()) {
            prompt.append("（无图表）\n");
        } else {
            for (int i = 0; i < context.charts.size(); i++) {
                ChartInfo chart = context.charts.get(i);
                prompt.append((i + 1)).append(". ").append(chart.title)
                     .append(" (").append(chart.chartType).append(")\n");
            }
        }
        prompt.append("\n");

        prompt.append("## 关键对话内容\n");
        // 只显示最近的几轮对话以避免token过多
        int maxDialogs = Math.min(3, context.aiResponses.size());
        for (int i = Math.max(0, context.aiResponses.size() - maxDialogs); i < context.aiResponses.size(); i++) {
            prompt.append("AI回答: ").append(context.aiResponses.get(i).substring(0,
                Math.min(500, context.aiResponses.get(i).length()))).append("...\n\n");
        }

        prompt.append("## 输出要求\n");
        prompt.append("请以JSON格式返回总结报告，包含以下结构：\n");
        prompt.append("{\n");
        prompt.append("  \"reportTitle\": \"报告标题（简洁明了，反映分析主题）\",\n");
        prompt.append("  \"summary\": \"整体总结（2-3句话概括分析结论）\",\n");
        prompt.append("  \"keyFindings\": [\"关键发现1\", \"关键发现2\", ...],\n");
        prompt.append("  \"sections\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"title\": \"章节标题\",\n");
        prompt.append("      \"content\": \"章节内容（详细分析，可包含数据引用）\",\n");
        prompt.append("      \"chartId\": 123  // 如果该章节关联图表，填写图表ID\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"conclusion\": \"结论和建议（基于数据的具体建议）\"\n");
        prompt.append("}\n\n");

        prompt.append("注意事项：\n");
        prompt.append("1. 报告应该基于实际对话内容，不要凭空捏造数据\n");
        prompt.append("2. 每个章节应该聚焦一个分析主题\n");
        prompt.append("3. 如果某个分析有对应的图表，务必关联正确的chartId\n");
        prompt.append("4. 结论应该具体可操作，避免空泛\n");

        return prompt.toString();
    }

    /**
     * 构建LLM消息列表
     */
    private List<Map<String, String>> buildMessages(String prompt) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一位专业的数据分析师，擅长将对话记录转化为结构化的分析报告。请严格按照JSON格式返回结果。");

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        messages.add(systemMsg);
        messages.add(userMsg);

        return messages;
    }

    /**
     * 解析LLM返回的总结结果
     */
    private SummaryReport parseSummaryResult(String result, ConversationContext context) {
        try {
            // 尝试提取JSON部分（LLM可能在JSON前后添加说明文字）
            String jsonPart = extractJson(result);

            Map<String, Object> summaryData = objectMapper.readValue(jsonPart, Map.class);

            SummaryReport report = new SummaryReport();
            report.conversationId = context.conversationId;
            report.reportTitle = (String) summaryData.get("reportTitle");
            report.summary = (String) summaryData.get("summary");
            report.keyFindings = (List<String>) summaryData.getOrDefault("keyFindings", new ArrayList<>());
            report.sections = (List<Map<String, Object>>) summaryData.getOrDefault("sections", new ArrayList<>());
            report.conclusion = (String) summaryData.get("conclusion");

            // 补充图表信息
            for (Map<String, Object> section : report.sections) {
                Object chartId = section.get("chartId");
                if (chartId instanceof Number) {
                    long id = ((Number) chartId).longValue();
                    context.charts.stream()
                        .filter(c -> c.id == id)
                        .findFirst()
                        .ifPresent(chart -> section.put("chartInfo", chart));
                }
            }

            log.info("[SUMMARY] 解析成功: conversationId={}, sections={}",
                context.conversationId, report.sections.size());

            return report;

        } catch (Exception e) {
            log.error("[SUMMARY] 解析失败: {}", result, e);
            return createFallbackSummary(context);
        }
    }

    /**
     * 从文本中提取JSON部分
     */
    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");

        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return text;
    }

    /**
     * 创建空总结
     */
    private SummaryReport createEmptySummary() {
        SummaryReport report = new SummaryReport();
        report.reportTitle = "空白报告";
        report.summary = "暂无对话内容";
        report.keyFindings = new ArrayList<>();
        report.sections = new ArrayList<>();
        report.conclusion = "请开始对话后生成报告";
        return report;
    }

    /**
     * 创建回退总结（当LLM失败时使用基于规则的总结）
     */
    private SummaryReport createFallbackSummary(ConversationContext context) {
        SummaryReport report = new SummaryReport();
        report.conversationId = context.conversationId;
        report.reportTitle = "数据分析报告";
        report.summary = "基于对话记录生成的分析报告";

        // 提取关键发现
        List<String> findings = new ArrayList<>();
        if (!context.userQuestions.isEmpty()) {
            findings.add("用户关注的主要问题: " + String.join("; ", context.userQuestions));
        }
        if (!context.charts.isEmpty()) {
            findings.add("生成了 " + context.charts.size() + " 个数据可视化图表");
        }
        report.keyFindings = findings;

        // 创建章节
        List<Map<String, Object>> sections = new ArrayList<>();

        // 用户问题章节
        if (!context.userQuestions.isEmpty()) {
            Map<String, Object> questionsSection = new HashMap<>();
            questionsSection.put("title", "用户咨询问题");
            questionsSection.put("content", String.join("\n", context.userQuestions));
            sections.add(questionsSection);
        }

        // 图表章节
        if (!context.charts.isEmpty()) {
            for (ChartInfo chart : context.charts) {
                Map<String, Object> chartSection = new HashMap<>();
                chartSection.put("title", chart.title);
                chartSection.put("content", "图表类型: " + chart.chartType);
                chartSection.put("chartId", chart.id);
                chartSection.put("chartInfo", chart);
                sections.add(chartSection);
            }
        }

        report.sections = sections;
        report.conclusion = "本报告基于对话记录自动生成，建议结合具体业务场景进行深入分析";

        return report;
    }

    /**
     * 从AI回复中提取图表信息
     */
    private void extractChartsFromContent(String content, Long conversationId, List<ChartInfo> charts) {
        // 这里可以解析AI回复中的图表引用
        // 例如查找 "图表[chartId]" 这样的模式
        // 简化实现，暂时跳过
    }

    /**
     * 获取默认模型
     */
    private String getDefaultModel() {
        return defaultModel;
    }

    /**
     * 对话上下文数据结构
     */
    private static class ConversationContext {
        Long conversationId;
        List<String> userQuestions = new ArrayList<>();
        List<String> aiResponses = new ArrayList<>();
        List<ChartInfo> charts = new ArrayList<>();
    }

    /**
     * 图表信息数据结构
     */
    public static class ChartInfo {
        Long id;
        String title;
        String chartType;
        String echartsOption;
        String imagePath; // 图表图片文件路径
        String baseSql;
    }

    /**
     * 总结报告数据结构
     */
    public static class SummaryReport {
        Long conversationId;
        String reportTitle;
        String summary;
        List<String> keyFindings = new ArrayList<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        String conclusion;
    }
}
