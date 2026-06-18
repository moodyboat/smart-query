package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ECharts 服务端渲染器（SSR）。
 * 通过 Node 进程以 ECharts SSR SVG 模式渲染真实图表 —— 无浏览器、无 canvas、无截图，
 * 是 ECharts 库自身的纯字符串渲染输出。
 *
 * 输入：ECharts option JSON 字符串。
 * 输出：SVG 字符串（交由 {@link SvgToPngConverter} 转 PNG）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EChartsSsrRenderer {

    private final ObjectMapper objectMapper;

    @Value("${smart-query.echarts.enabled:true}")
    private boolean enabled;

    @Value("${smart-query.echarts.node-command:node}")
    private String nodeCommand;

    @Value("${smart-query.echarts.script-path:tools/echarts-ssr/render.mjs}")
    private String scriptPath;

    @Value("${smart-query.echarts.timeout-ms:30000}")
    private long timeoutMs;

    @Value("${smart-query.echarts.default-width:800}")
    private int defaultWidth;

    @Value("${smart-query.echarts.default-height:600}")
    private int defaultHeight;

    private Path resolvedScriptPath;
    private ExecutorService ioPool;

    @PostConstruct
    void init() {
        this.ioPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "echarts-ssr-io");
            t.setDaemon(true);
            return t;
        });
        this.resolvedScriptPath = resolveScriptPath();
        if (!enabled) {
            log.warn("[ECHARTS-SSR] 已通过配置禁用");
        } else if (resolvedScriptPath == null || !Files.exists(resolvedScriptPath)) {
            log.error("[ECHARTS-SSR] 渲染脚本不存在: {} (scriptPath={})", resolvedScriptPath, scriptPath);
        } else {
            log.info("[ECHARTS-SSR] 初始化完成: node={}, script={}", nodeCommand, resolvedScriptPath);
        }
    }

    @PreDestroy
    void destroy() {
        if (ioPool != null) {
            ioPool.shutdownNow();
        }
    }

    public boolean isAvailable() {
        return enabled && resolvedScriptPath != null && Files.exists(resolvedScriptPath);
    }

    /**
     * 用 ECharts SSR SVG 模式渲染图表，返回 SVG 字符串；失败返回 null。
     */
    public String renderSvg(String echartsOption, Integer width, Integer height) {
        if (!isAvailable()) {
            log.debug("[ECHARTS-SSR] 不可用，跳过");
            return null;
        }
        if (echartsOption == null || echartsOption.isBlank()) {
            return null;
        }
        int w = (width != null && width > 0) ? width : defaultWidth;
        int h = (height != null && height > 0) ? height : defaultHeight;

        long start = System.currentTimeMillis();
        Process process = null;
        try {
            Object optionObj = objectMapper.readValue(echartsOption, Object.class);
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("option", optionObj);
            payloadMap.put("width", w);
            payloadMap.put("height", h);
            byte[] payload = objectMapper.writeValueAsBytes(payloadMap);

            ProcessBuilder pb = new ProcessBuilder(nodeCommand, resolvedScriptPath.toString());
            pb.redirectErrorStream(false);
            process = pb.start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            Future<String> stdoutFuture = readAsync(process.getInputStream());
            Future<String> stderrFuture = readAsync(process.getErrorStream());

            boolean done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                log.warn("[ECHARTS-SSR] 渲染超时 {}ms", timeoutMs);
                return null;
            }

            String svg = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            int exit = process.exitValue();

            if (exit != 0 || svg == null || svg.isBlank()) {
                log.error("[ECHARTS-SSR] 渲染失败 exit={}, stderr={}", exit, stderr);
                return null;
            }
            log.info("[ECHARTS-SSR] 渲染成功: time={}ms, svgChars={}", System.currentTimeMillis() - start, svg.length());
            return svg;
        } catch (Exception e) {
            log.error("[ECHARTS-SSR] 渲染异常: {}", e.getMessage());
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    private Future<String> readAsync(InputStream is) {
        return ioPool.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        });
    }

    private Path resolveScriptPath() {
        Path p = Paths.get(scriptPath);
        if (p.isAbsolute()) {
            return p;
        }
        Path userDir = Paths.get(System.getProperty("user.dir"));
        Path[] candidates = new Path[]{
            userDir.resolve(scriptPath),
            userDir.resolve("backend").resolve(scriptPath),
            userDir.getParent() == null ? null : userDir.getParent().resolve(scriptPath)
        };
        for (Path c : candidates) {
            if (c != null && Files.exists(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        return userDir.resolve(scriptPath).toAbsolutePath().normalize();
    }
}
