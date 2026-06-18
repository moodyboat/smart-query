package com.smartquery.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;

/**
 * SVG 转 PNG 转换器（Apache Batik）。
 * 将 ECharts SSR 输出的 SVG 字符串渲染为 PNG，供 POI 嵌入 Word。
 */
@Slf4j
@Component
public class SvgToPngConverter {

    /** 虚拟 base URI，避免 Batik 解析相对资源引用时 ParsedURL 为 null 抛 NPE。 */
    private static final String BASE_URI = "file:///echarts-chart.svg";

    public byte[] convert(String svg, float targetWidth, float targetHeight) {
        if (svg == null || svg.isBlank()) {
            return null;
        }
        try {
            PNGTranscoder transcoder = new PNGTranscoder();
            if (targetWidth > 0) {
                transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, targetWidth);
            }
            if (targetHeight > 0) {
                transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, targetHeight);
            }
            // 禁止加载外部资源：echarts SVG 是自包含的，避免外部引用触发解析异常
            transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, Boolean.FALSE);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                TranscoderInput input = new TranscoderInput(new StringReader(svg));
                input.setURI(BASE_URI);
                TranscoderOutput output = new TranscoderOutput(out);
                transcoder.transcode(input, output);
                byte[] bytes = out.toByteArray();
                log.debug("[SVG2PNG] 转换成功: svgChars={}, pngBytes={}", svg.length(), bytes.length);
                return bytes;
            }
        } catch (Exception e) {
            log.error("[SVG2PNG] 转换失败: {}", e.getMessage());
            return null;
        }
    }
}
