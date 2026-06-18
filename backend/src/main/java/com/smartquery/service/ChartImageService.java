package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 图表图片生成服务
 * 将 ECharts 配置转换为图片，用于 Word 报告插入。
 *
 * 渲染优先级：
 * 1. ECharts SSR（Node 进程，SVG → Batik PNG）—— 真实 ECharts 渲染，无浏览器、无截图
 * 2. 外部 ECharts 渲染服务（ECHARTS_SERVICE_URL，可选）
 * 3. 占位符图片（仅在上述均不可用时，明确标注为占位符，绝不使用假数据误导）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartImageService {

    private final ObjectMapper objectMapper;
    private final EChartsSsrRenderer echartsSsrRenderer;
    private final SvgToPngConverter svgToPngConverter;

    @org.springframework.beans.factory.annotation.Value("${smart-query.chart.image-timeout-seconds:30}")
    private int imageTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Value("${smart-query.chart.image-width:800}")
    private int imageWidth;

    @org.springframework.beans.factory.annotation.Value("${smart-query.chart.image-height:600}")
    private int imageHeight;

    /**
     * 将ECharts配置转换为图片字节数组（PNG）
     */
    public byte[] convertEchartsToImage(String echartsOption) {
        try {
            log.debug("[CHART-IMAGE] 开始转换图表，配置长度: {}", echartsOption != null ? echartsOption.length() : 0);

            // 方法1: ECharts SSR (Node) → SVG → PNG（真实 ECharts 渲染）
            byte[] result = renderViaEChartsSsr(echartsOption);
            if (result != null && result.length > 0) {
                log.info("[CHART-IMAGE] ECharts SSR 渲染成功，图片大小: {}KB", result.length / 1024);
                return result;
            }

            // 方法2: 外部 ECharts 渲染服务（若已部署）
            result = tryExternalService(echartsOption);
            if (result != null && result.length > 0) {
                log.info("[CHART-IMAGE] 外部服务生成成功，图片大小: {}KB", result.length / 1024);
                return result;
            }

            // 方法3: 占位符（明确标注，非假数据图）
            log.warn("[CHART-IMAGE] 所有渲染方式不可用，回退到占位符图片");
            return generatePlaceholderImage(echartsOption);

        } catch (Exception e) {
            log.error("[CHART-IMAGE] 转换失败", e);
            return generateErrorPlaceholder();
        }
    }

    /**
     * 通过 ECharts SSR 渲染 SVG，再由 Batik 转为 PNG。
     */
    private byte[] renderViaEChartsSsr(String echartsOption) {
        if (!echartsSsrRenderer.isAvailable()) {
            log.debug("[CHART-IMAGE] ECharts SSR 不可用");
            return null;
        }
        String svg = echartsSsrRenderer.renderSvg(echartsOption, imageWidth, imageHeight);
        if (svg == null || svg.isBlank()) {
            return null;
        }
        byte[] png = svgToPngConverter.convert(svg, imageWidth, imageHeight);
        if (png == null || png.length == 0) {
            log.warn("[CHART-IMAGE] SVG→PNG 转换失败");
            return null;
        }
        return png;
    }

    /**
     * 尝试使用外部服务生成图片
     */
    private byte[] tryExternalService(String echartsOption) {
        try {
            String serviceUrl = System.getenv("ECHARTS_SERVICE_URL");
            if (serviceUrl == null || serviceUrl.isEmpty()) {
                log.debug("[CHART-IMAGE] 未配置外部ECharts服务");
                return null;
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            Map<String, Object> requestBody = Map.of(
                "option", objectMapper.readValue(echartsOption, Map.class),
                "width", imageWidth,
                "height", imageHeight
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(imageTimeoutSeconds))
                .build();

            HttpResponse<byte[]> response = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200 && response.body().length > 0) {
                log.info("[CHART-IMAGE] 外部服务生成成功");
                return response.body();
            }

        } catch (Exception e) {
            log.debug("[CHART-IMAGE] 外部服务不可用: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 解析图表标题
     */
    private String extractChartTitle(String echartsOption) {
        try {
            if (echartsOption == null || echartsOption.isEmpty()) {
                return "数据图表";
            }

            Map<String, Object> option = objectMapper.readValue(echartsOption, Map.class);
            Object titleObj = option.get("title");

            if (titleObj instanceof Map) {
                Map<String, Object> titleMap = (Map<String, Object>) titleObj;
                Object text = titleMap.get("text");
                if (text != null) {
                    return text.toString();
                }
            }

            return "数据图表";
        } catch (Exception e) {
            return "数据图表";
        }
    }

    /**
     * 生成占位符图片（当所有渲染方式不可用时）。
     * 明确标注为占位符，不伪造图表数据。
     */
    private byte[] generatePlaceholderImage(String echartsOption) {
        try {
            BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2d.setColor(new Color(250, 250, 250));
            g2d.fillRect(0, 0, imageWidth, imageHeight);

            g2d.setColor(new Color(200, 200, 200));
            g2d.setStroke(new BasicStroke(2));
            g2d.drawRect(10, 10, imageWidth - 20, imageHeight - 20);

            String title = extractChartTitle(echartsOption);

            g2d.setColor(new Color(51, 51, 51));
            g2d.setFont(new Font("微软雅黑", Font.BOLD, 18));
            FontMetrics fm = g2d.getFontMetrics();
            int titleWidth = fm.stringWidth(title);
            g2d.drawString(title, (imageWidth - titleWidth) / 2, 40);

            g2d.setColor(new Color(102, 102, 102));
            g2d.setFont(new Font("微软雅黑", Font.PLAIN, 14));
            String placeholder = "图表区域（渲染服务不可用）";
            fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(placeholder);
            g2d.drawString(placeholder, (imageWidth - textWidth) / 2, imageHeight / 2);

            String hint = "请配置 ECharts SSR 渲染服务以显示真实图表";
            g2d.setFont(new Font("微软雅黑", Font.PLAIN, 12));
            g2d.setColor(new Color(153, 153, 153));
            fm = g2d.getFontMetrics();
            textWidth = fm.stringWidth(hint);
            g2d.drawString(hint, (imageWidth - textWidth) / 2, imageHeight / 2 + 25);

            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", baos);

            log.info("[CHART-IMAGE] 生成占位符图片成功");
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("[CHART-IMAGE] 生成占位符图片失败", e);
            return generateErrorPlaceholder();
        }
    }

    /**
     * 生成错误占位符图片
     */
    private byte[] generateErrorPlaceholder() {
        try {
            BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();

            g2d.setColor(new Color(255, 240, 240));
            g2d.fillRect(0, 0, 400, 300);

            g2d.setColor(new Color(200, 50, 50));
            g2d.setStroke(new BasicStroke(2));
            g2d.drawRect(10, 10, 380, 280);

            g2d.setColor(new Color(180, 40, 40));
            g2d.setFont(new Font("微软雅黑", Font.BOLD, 16));
            g2d.drawString("图表加载失败", 150, 150);

            g2d.setFont(new Font("微软雅黑", Font.PLAIN, 12));
            g2d.drawString("请检查数据格式", 160, 180);

            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", baos);

            return baos.toByteArray();

        } catch (Exception e) {
            log.error("[CHART-IMAGE] 生成错误占位符失败", e);
            return new byte[0];
        }
    }

    /**
     * 从图表实体生成图片
     */
    public byte[] generateImageFromChart(String echartsOption, String title) {
        if (echartsOption == null || echartsOption.isEmpty()) {
            log.warn("[CHART-IMAGE] ECharts配置为空");
            return generateErrorPlaceholder();
        }

        return convertEchartsToImage(echartsOption);
    }

    /**
     * 获取图片尺寸
     */
    public int[] getImageSize() {
        return new int[]{imageWidth, imageHeight};
    }
}
