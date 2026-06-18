package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.ChartMapper;
import com.smartquery.entity.Chart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Word文档报告生成服务（增强版）
 * 支持LLM智能总结和图表插入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordReportService {

    private final ChatMessageMapper chatMessageMapper;
    private final ReportSummaryService reportSummaryService;
    private final ChartImageService chartImageService;
    private final ChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.chart.storage-path:./charts}")
    private String chartStoragePath;

    /**
     * 从对话记录生成Word报告（增强版）
     * 使用LLM总结对话内容，并提取关键图表生成图文并茂的报告
     */
    public byte[] generateReportFromConversation(Long conversationId, String title) {
        try {
            XWPFDocument document = new XWPFDocument();

            // 1. 使用LLM生成智能总结
            ReportSummaryService.SummaryReport summary = reportSummaryService.generateSummary(conversationId);

            // 2. 添加报告标题
            addTitle(document, title != null ? title : summary.reportTitle);

            // 3. 添加报告元信息
            addMetadata(document, conversationId, summary);

            // 4. 添加执行摘要
            addExecutiveSummary(document, summary);

            // 5. 添加关键发现
            addKeyFindings(document, summary);

            // 6. 添加详细章节（含图表）
            addSectionsWithCharts(document, summary);

            // 7. 添加结论与建议
            addConclusion(document, summary);

            // 8. 添加附录（原始对话）
            addAppendix(document, conversationId);

            // 转换为字节数组
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.write(outputStream);
            document.close();

            log.info("[WORD-REPORT] 生成成功: conversationId={}, size={}KB",
                conversationId, outputStream.size() / 1024);

            return outputStream.toByteArray();

        } catch (Exception e) {
            log.error("[WORD-REPORT] 生成失败: conversationId={}", conversationId, e);
            throw new RuntimeException("生成Word报告失败: " + e.getMessage(), e);
        }
    }

    /**
     * 添加标题页
     */
    private void addTitle(XWPFDocument document, String title) {
        // 主标题
        XWPFParagraph titleParagraph = document.createParagraph();
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        titleParagraph.setSpacingAfter(400);

        XWPFRun titleRun = titleParagraph.createRun();
        titleRun.setText(title != null ? title : "数据分析报告");
        titleRun.setBold(true);
        titleRun.setFontSize(24);
        titleRun.setFontFamily("微软雅黑");
        titleRun.setColor("2E75B5");

        // 副标题
        XWPFParagraph subtitleParagraph = document.createParagraph();
        subtitleParagraph.setAlignment(ParagraphAlignment.CENTER);
        subtitleParagraph.setSpacingAfter(600);

        XWPFRun subtitleRun = subtitleParagraph.createRun();
        subtitleRun.setText("智能问数系统自动生成");
        subtitleRun.setFontSize(12);
        subtitleRun.setFontFamily("宋体");
        subtitleRun.setColor("666666");
        subtitleRun.setItalic(true);

        // 生成时间
        XWPFParagraph dateParagraph = document.createParagraph();
        dateParagraph.setAlignment(ParagraphAlignment.CENTER);
        dateParagraph.setSpacingAfter(400);

        XWPFRun dateRun = dateParagraph.createRun();
        dateRun.setText("生成时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")));
        dateRun.setFontSize(10);
        dateRun.setFontFamily("宋体");
        dateRun.setColor("999999");
    }

    /**
     * 添加报告元信息
     */
    private void addMetadata(XWPFDocument document, Long conversationId, ReportSummaryService.SummaryReport summary) {
        XWPFParagraph metadataParagraph = document.createParagraph();
        metadataParagraph.setSpacingBefore(200);
        metadataParagraph.setSpacingAfter(600);

        XWPFRun metadataRun = metadataParagraph.createRun();
        metadataRun.setText("报告编号: " + conversationId);
        metadataRun.setFontSize(9);
        metadataRun.setFontFamily("宋体");
        metadataRun.setColor("888888");
    }

    /**
     * 添加执行摘要
     */
    private void addExecutiveSummary(XWPFDocument document, ReportSummaryService.SummaryReport summary) {
        // 章节标题
        XWPFParagraph headingParagraph = document.createParagraph();
        headingParagraph.setSpacingBefore(400);
        headingParagraph.setSpacingAfter(200);

        XWPFRun headingRun = headingParagraph.createRun();
        headingRun.setText("执行摘要");
        headingRun.setBold(true);
        headingRun.setFontSize(16);
        headingRun.setFontFamily("微软雅黑");
        headingRun.setColor("000000");

        // 摘要内容
        XWPFParagraph contentParagraph = document.createParagraph();
        contentParagraph.setSpacingAfter(400);
        contentParagraph.setIndentationFirstLine(400);

        XWPFRun contentRun = contentParagraph.createRun();
        contentRun.setText(summary.summary != null ? summary.summary : "本报告基于智能问数系统的对话记录生成，汇总了用户关注的主要问题和系统生成的分析结果。");
        contentRun.setFontSize(11);
        contentRun.setFontFamily("宋体");
        contentRun.setColor("333333");
    }

    /**
     * 添加关键发现
     */
    private void addKeyFindings(XWPFDocument document, ReportSummaryService.SummaryReport summary) {
        if (summary.keyFindings == null || summary.keyFindings.isEmpty()) {
            return;
        }

        // 章节标题
        XWPFParagraph headingParagraph = document.createParagraph();
        headingParagraph.setSpacingBefore(400);
        headingParagraph.setSpacingAfter(200);

        XWPFRun headingRun = headingParagraph.createRun();
        headingRun.setText("关键发现");
        headingRun.setBold(true);
        headingRun.setFontSize(16);
        headingRun.setFontFamily("微软雅黑");
        headingRun.setColor("000000");

        // 关键发现列表
        for (int i = 0; i < summary.keyFindings.size(); i++) {
            XWPFParagraph findingParagraph = document.createParagraph();
            findingParagraph.setSpacingAfter(150);
            findingParagraph.setIndentationFirstLine(400);

            XWPFRun findingRun = findingParagraph.createRun();
            findingRun.setText(String.format("%d. %s", i + 1, summary.keyFindings.get(i)));
            findingRun.setFontSize(11);
            findingRun.setFontFamily("宋体");
            findingRun.setColor("333333");
        }
    }

    /**
     * 添加详细章节（含图表）
     */
    private void addSectionsWithCharts(XWPFDocument document, ReportSummaryService.SummaryReport summary) {
        if (summary.sections == null || summary.sections.isEmpty()) {
            return;
        }

        // 章节标题
        XWPFParagraph mainHeading = document.createParagraph();
        mainHeading.setSpacingBefore(600);
        mainHeading.setSpacingAfter(400);
        mainHeading.setPageBreak(true);  // 新页面开始详细分析

        XWPFRun mainHeadingRun = mainHeading.createRun();
        mainHeadingRun.setText("详细分析");
        mainHeadingRun.setBold(true);
        mainHeadingRun.setFontSize(18);
        mainHeadingRun.setFontFamily("微软雅黑");
        mainHeadingRun.setColor("000000");

        // 各个章节
        for (Map<String, Object> section : summary.sections) {
            addSection(document, section);
        }
    }

    /**
     * 添加单个章节
     */
    private void addSection(XWPFDocument document, Map<String, Object> section) {
        String sectionTitle = (String) section.get("title");
        String sectionContent = (String) section.get("content");
        Object chartId = section.get("chartId");
        ReportSummaryService.ChartInfo chartInfo = (ReportSummaryService.ChartInfo) section.get("chartInfo");

        // 章节标题
        XWPFParagraph titleParagraph = document.createParagraph();
        titleParagraph.setSpacingBefore(400);
        titleParagraph.setSpacingAfter(200);

        XWPFRun titleRun = titleParagraph.createRun();
        titleRun.setText(sectionTitle != null ? sectionTitle : "分析章节");
        titleRun.setBold(true);
        titleRun.setFontSize(14);
        titleRun.setFontFamily("微软雅黑");
        titleRun.setColor("4472C4");

        // 章节内容
        if (sectionContent != null && !sectionContent.isEmpty()) {
            XWPFParagraph contentParagraph = document.createParagraph();
            contentParagraph.setSpacingAfter(200);
            contentParagraph.setIndentationFirstLine(400);

            XWPFRun contentRun = contentParagraph.createRun();
            contentRun.setText(sectionContent);
            contentRun.setFontSize(11);
            contentRun.setFontFamily("宋体");
            contentRun.setColor("333333");
        }

        // 添加图表
        if (chartInfo != null && chartInfo.echartsOption != null) {
            addChartToDocument(document, chartInfo);
        } else if (chartId instanceof Number) {
            // 从数据库查询图表信息
            try {
                long id = ((Number) chartId).longValue();
                Chart chart = chartMapper.selectById(id);
                if (chart != null && chart.getEchartsOption() != null) {
                    ReportSummaryService.ChartInfo info = new ReportSummaryService.ChartInfo();
                    info.id = chart.getId();
                    info.title = chart.getTitle();
                    info.chartType = chart.getChartType();
                    info.echartsOption = chart.getEchartsOption();
                    info.imagePath = chart.getImagePath();
                    addChartToDocument(document, info);
                }
            } catch (Exception e) {
                log.warn("[WORD-REPORT] 查询图表失败: chartId={}", chartId, e);
            }
        }

        // 章节分隔
        XWPFParagraph separatorParagraph = document.createParagraph();
        separatorParagraph.setSpacingBefore(400);
        separatorParagraph.setSpacingAfter(100);

        XWPFRun separatorRun = separatorParagraph.createRun();
        separatorRun.setText("────────────────────────────────────");
        separatorRun.setFontSize(8);
        separatorRun.setColor("CCCCCC");
    }

    /**
     * 添加图表到文档
     */
    private void addChartToDocument(XWPFDocument document, ReportSummaryService.ChartInfo chartInfo) {
        try {
            byte[] chartImage = null;

            // 优先使用已保存的图片文件
            if (chartInfo.imagePath != null && !chartInfo.imagePath.isEmpty()) {
                chartImage = loadSavedChartImage(chartInfo.imagePath);
                if (chartImage != null) {
                    log.info("[WORD-REPORT] 使用已保存的图表图片: chartId={}, path={}", chartInfo.id, chartInfo.imagePath);
                }
            }

            // 如果没有保存的图片，则动态生成
            if (chartImage == null || chartImage.length == 0) {
                chartImage = chartImageService.convertEchartsToImage(chartInfo.echartsOption);
                if (chartImage == null || chartImage.length == 0) {
                    log.warn("[WORD-REPORT] 图表图片生成失败: chartId={}", chartInfo.id);
                    addChartPlaceholder(document, chartInfo.title);
                    return;
                }
                log.info("[WORD-REPORT] 动态生成图表图片: chartId={}", chartInfo.id);
            }

            // 图表标题
            XWPFParagraph chartTitleParagraph = document.createParagraph();
            chartTitleParagraph.setSpacingBefore(300);
            chartTitleParagraph.setSpacingAfter(100);

            XWPFRun chartTitleRun = chartTitleParagraph.createRun();
            chartTitleRun.setText("图表: " + (chartInfo.title != null ? chartInfo.title : "数据可视化"));
            chartTitleRun.setBold(true);
            chartTitleRun.setFontSize(12);
            chartTitleRun.setFontFamily("微软雅黑");
            chartTitleRun.setColor("555555");

            // 插入图片
            XWPFParagraph imageParagraph = document.createParagraph();
            imageParagraph.setAlignment(ParagraphAlignment.CENTER);
            imageParagraph.setSpacingAfter(200);

            XWPFRun imageRun = imageParagraph.createRun();

            // 计算图片尺寸（EMU单位，1英寸 = 914400 EMU）
            // 使用更合理的尺寸，避免图片过大或过小
            int targetWidth = 600;  // 目标宽度600像素
            int targetHeight = 450; // 目标高度450像素

            int widthEmu = targetWidth * 9525;  // 像素转EMU
            int heightEmu = targetHeight * 9525;

            log.debug("[WORD-REPORT] 插入图片尺寸: {}x{} 像素, {}x{} EMU",
                targetWidth, targetHeight, widthEmu, heightEmu);

            imageRun.addPicture(new java.io.ByteArrayInputStream(chartImage),
                XWPFDocument.PICTURE_TYPE_PNG,
                "chart_" + chartInfo.id + ".png",
                widthEmu, heightEmu);

            log.info("[WORD-REPORT] 插入图表成功: chartId={}, title={}", chartInfo.id, chartInfo.title);

        } catch (Exception e) {
            log.error("[WORD-REPORT] 插入图表失败: chartId={}", chartInfo.id, e);
            addChartPlaceholder(document, chartInfo.title);
        }
    }

    /**
     * 从文件系统加载已保存的图表图片
     */
    private byte[] loadSavedChartImage(String imagePath) {
        try {
            Path fullPath = Paths.get(chartStoragePath, imagePath);
            if (Files.exists(fullPath)) {
                log.debug("[WORD-REPORT] 加载已保存的图表图片: {}", fullPath);
                return Files.readAllBytes(fullPath);
            } else {
                log.warn("[WORD-REPORT] 图片文件不存在: {}", fullPath);
                return null;
            }
        } catch (Exception e) {
            log.error("[WORD-REPORT] 加载图片失败: path={}", imagePath, e);
            return null;
        }
    }

    /**
     * 添加图表占位符（当图片加载失败时）
     */
    private void addChartPlaceholder(XWPFDocument document, String title) {
        try {
            // 插入错误提示
            XWPFParagraph errorParagraph = document.createParagraph();
            errorParagraph.setSpacingBefore(200);
            errorParagraph.setSpacingAfter(200);

            XWPFRun errorRun = errorParagraph.createRun();
            errorRun.setText("图表加载失败: " + (title != null ? title : ""));
            errorRun.setItalic(true);
            errorRun.setFontSize(10);
            errorRun.setFontFamily("宋体");
            errorRun.setColor("999999");
        } catch (Exception e) {
            log.error("[WORD-REPORT] 添加占位符失败", e);
        }
    }

    /**
     * 添加结论与建议
     */
    private void addConclusion(XWPFDocument document, ReportSummaryService.SummaryReport summary) {
        // 章节标题
        XWPFParagraph headingParagraph = document.createParagraph();
        headingParagraph.setSpacingBefore(600);
        headingParagraph.setSpacingAfter(300);

        XWPFRun headingRun = headingParagraph.createRun();
        headingRun.setText("结论与建议");
        headingRun.setBold(true);
        headingRun.setFontSize(16);
        headingRun.setFontFamily("微软雅黑");
        headingRun.setColor("000000");

        // 结论内容
        if (summary.conclusion != null && !summary.conclusion.isEmpty()) {
            XWPFParagraph contentParagraph = document.createParagraph();
            contentParagraph.setSpacingAfter(400);
            contentParagraph.setIndentationFirstLine(400);

            XWPFRun contentRun = contentParagraph.createRun();
            contentRun.setText(summary.conclusion);
            contentRun.setFontSize(11);
            contentRun.setFontFamily("宋体");
            contentRun.setColor("333333");
        } else {
            // 默认结论
            XWPFParagraph defaultParagraph = document.createParagraph();
            defaultParagraph.setSpacingAfter(400);
            defaultParagraph.setIndentationFirstLine(400);

            XWPFRun defaultRun = defaultParagraph.createRun();
            defaultRun.setText("本报告基于系统对话记录自动生成。建议结合具体业务场景，对报告中提及的数据趋势和异常情况进行进一步的深入分析。如有任何疑问或需要更详细的分析，请随时联系系统管理员。");
            defaultRun.setFontSize(11);
            defaultRun.setFontFamily("宋体");
            defaultRun.setColor("333333");
        }
    }

    /**
     * 添加附录（原始对话记录）
     */
    private void addAppendix(XWPFDocument document, Long conversationId) {
        try {
            List<Map<String, Object>> messages = chatMessageMapper.selectMessagesByConversation(conversationId);
            if (messages.isEmpty()) {
                return;
            }

            // 章节标题
            XWPFParagraph headingParagraph = document.createParagraph();
            headingParagraph.setSpacingBefore(600);
            headingParagraph.setSpacingAfter(400);
            headingParagraph.setPageBreak(true);  // 新页面

            XWPFRun headingRun = headingParagraph.createRun();
            headingRun.setText("附录：对话记录");
            headingRun.setBold(true);
            headingRun.setFontSize(16);
            headingRun.setFontFamily("微软雅黑");
            headingRun.setColor("000000");

            // 对话内容
            for (Map<String, Object> message : messages) {
                String role = (String) message.get("role");
                String content = (String) message.get("content");

                if (content == null || content.isBlank()) {
                    continue;
                }

                // 角色标签
                XWPFParagraph roleParagraph = document.createParagraph();
                roleParagraph.setSpacingBefore(200);
                roleParagraph.setSpacingAfter(100);

                XWPFRun roleRun = roleParagraph.createRun();
                String roleText = "user".equals(role) ? "用户：" : "系统：";
                roleRun.setText(roleText);
                roleRun.setBold(true);
                roleRun.setFontSize(10);
                roleRun.setFontFamily("微软雅黑");
                roleRun.setColor("555555");

                // 消息内容
                XWPFParagraph contentParagraph = document.createParagraph();
                contentParagraph.setSpacingAfter(150);
                contentParagraph.setIndentationFirstLine(300);

                XWPFRun contentRun = contentParagraph.createRun();
                // 限制内容长度，避免文档过大
                String displayContent = content.length() > 1000 ? content.substring(0, 1000) + "..." : content;
                contentRun.setText(displayContent);
                contentRun.setFontSize(9);
                contentRun.setFontFamily("宋体");
                contentRun.setColor("666666");
            }

        } catch (Exception e) {
            log.error("[WORD-REPORT] 添加附录失败", e);
        }
    }
}
