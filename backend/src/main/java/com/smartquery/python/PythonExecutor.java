package com.smartquery.python;

import com.smartquery.datasource.DataSourceContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Python 执行器 — Docker 容器执行 (Phase 1: 本地进程执行)
 *
 * <p>翻译 Claude Code BashTool:
 * <ul>
 *   <li>超时控制: getDefaultTimeoutMs / getMaxTimeoutMs</li>
 *   <li>输出截断: 保留头尾</li>
 *   <li>安全限制: PythonSandbox</li>
 * </ul>
 */
import jakarta.annotation.PostConstruct;

@Slf4j
@Component
public class PythonExecutor {

    private final DataSourceMapper dataSourceMapper;
    private static final int MAX_OUTPUT_BYTES = 65536;
    private static final int MAX_TIMEOUT_MS = 600000;
    private static final String PYTHON_CMD = "python3";
    private static final String ARTIFACT_DIR = "/tmp/smartquery-artifacts";
    private static final String WORKSPACE_BASE = "/tmp/smartquery-workspace";

    @Value("${smart-query.python.max-memory-mb:512}")
    private int maxMemoryMb;

    @Value("${smart-query.python.max-cpus:1}")
    private double maxCpus;

    @Value("${smart-query.python.execution-mode:process}")
    private String executionMode;

    public PythonExecutor(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(Path.of(ARTIFACT_DIR));
            Files.createDirectories(Path.of(WORKSPACE_BASE));
        } catch (IOException ignored) {}
    }

    public PythonResult execute(String code, Long dataSourceId, int timeoutMs) {
        return execute(code, dataSourceId, timeoutMs, null);
    }

    public PythonResult execute(String code, Long dataSourceId, int timeoutMs, Long conversationId) {
        return execute(code, dataSourceId, timeoutMs, conversationId, null);
    }

    public PythonResult execute(String code, Long dataSourceId, int timeoutMs, Long conversationId, java.util.function.BiConsumer<String, Integer> progressCallback) {
        timeoutMs = Math.min(Math.max(timeoutMs, 1000), MAX_TIMEOUT_MS);
        long start = System.currentTimeMillis();

        try {
            // 安全校验 — 阻止危险操作
            PythonSandbox.validate(code);

            Path tempFile = Files.createTempFile("smartquery_", ".py");
            String wrappedCode = wrapCode(code, dataSourceId, conversationId);
            Files.writeString(tempFile, wrappedCode);

            ProcessBuilder pb;
            if ("docker".equals(executionMode)) {
                pb = buildDockerProcess(tempFile, dataSourceId);
            } else {
                pb = new ProcessBuilder(PYTHON_CMD, tempFile.toString());
            }
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            Process process = pb.start();

            // Memory monitoring thread for process mode
            ScheduledExecutorService memoryMonitor = null;
            if (!"docker".equals(executionMode) && maxMemoryMb > 0) {
                memoryMonitor = startMemoryMonitor(process, maxMemoryMb);
            }

            // Stream stdout with progress callbacks
            StringBuilder stdoutBuilder = new StringBuilder();
            CompletableFuture<Void> stdoutReadFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        stdoutBuilder.append(line).append('\n');
                        lineCount++;
                        if (progressCallback != null && lineCount % 5 == 0) {
                            int elapsed = (int) (System.currentTimeMillis() - start);
                            progressCallback.accept(stdoutBuilder.toString(), elapsed);
                        }
                    }
                } catch (IOException ignored) {}
            });
            CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());

            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            int elapsed = (int) (System.currentTimeMillis() - start);

            if (!completed) {
                process.destroyForcibly();
                stdoutReadFuture.cancel(true);
                stderrFuture.cancel(true);
                if (memoryMonitor != null) memoryMonitor.shutdownNow();
                Files.deleteIfExists(tempFile);
                return PythonResult.timeout("执行超时 (" + timeoutMs + "ms)", elapsed);
            }

            if (memoryMonitor != null) memoryMonitor.shutdownNow();

            stdoutReadFuture.get(5, TimeUnit.SECONDS);
            String stdout = truncateOutput(stdoutBuilder.toString());
            String stderr = truncateOutput(stderrFuture.get(5, TimeUnit.SECONDS));
            int exitCode = process.exitValue();

            Files.deleteIfExists(tempFile);

            // 提取 artifact 路径
            List<String> artifacts = extractArtifacts(stdout);

            log.info("[PYTHON] exit={}, time={}ms, stdout={}chars, stderr={}chars, artifacts={}",
                exitCode, elapsed, stdout.length(), stderr.length(), artifacts.size());

            return new PythonResult(stdout, stderr, exitCode, artifacts, elapsed);
        } catch (SecurityException e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            return PythonResult.error("安全检查未通过: " + e.getMessage(), -3, elapsed);
        } catch (Exception e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            return PythonResult.error("执行异常: " + e.getMessage(), -2, elapsed);
        }
    }

    /**
     * 包装用户代码: 注入 DB 连接 + 安全限制
     */
    private String wrapCode(String userCode, Long dataSourceId, Long conversationId) {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        StringBuilder sb = new StringBuilder();

        sb.append("# Auto-generated imports\n");
        sb.append("import pandas as pd\n");
        sb.append("import numpy as np\n");
        sb.append("import json\n");
        sb.append("import sys\n");
        sb.append("import os\n");
        sb.append("\n");

        // Per-conversation workspace
        if (conversationId != null) {
            String workspaceDir = WORKSPACE_BASE + "/" + conversationId;
            sb.append("_workspace = '").append(workspaceDir).append("'\n");
            sb.append("os.makedirs(_workspace, exist_ok=True)\n");
        } else {
            sb.append("_workspace = '/tmp'\n");
        }
        sb.append("\n");

        // Configure matplotlib output directory
        sb.append("import matplotlib\n");
        sb.append("matplotlib.use('Agg')\n");
        sb.append("import matplotlib.pyplot as plt\n");
        sb.append("_artifact_dir = '").append(ARTIFACT_DIR).append("'\n");
        sb.append("os.makedirs(_artifact_dir, exist_ok=True)\n");
        sb.append("_fig_counter = 0\n");
        sb.append("_artifacts = []\n");
        sb.append("\n");

        sb.append("# 自动保存 plt 图表\n");
        sb.append("_original_show = plt.show\n");
        sb.append("def _auto_save_show(*args, **kwargs):\n");
        sb.append("    global _fig_counter\n");
        sb.append("    for _fn in plt.get_fignums():\n");
        sb.append("        fig = plt.figure(_fn)\n");
        sb.append("        _fig_counter += 1\n");
        sb.append("        _path = os.path.join(_artifact_dir, '").append(executionId).append("_' + str(_fig_counter) + '.png')\n");
        sb.append("        fig.savefig(_path, dpi=150, bbox_inches='tight')\n");
        sb.append("plt.show = _auto_save_show\n");
        sb.append("_original_savefig = plt.Figure.savefig\n");
        sb.append("def _tracked_savefig(self, fname, **kwargs):\n");
        sb.append("    _original_savefig(self, fname, **kwargs)\n");
        sb.append("    import shutil\n");
        sb.append("    _src = os.path.abspath(fname)\n");
        sb.append("    _basename = os.path.basename(_src)\n");
        sb.append("    _dest = os.path.join(_artifact_dir, _basename)\n");
        sb.append("    if _src != _dest:\n");
        sb.append("        shutil.copy2(_src, _dest)\n");
        sb.append("    _artifacts.append(_dest)\n");
        sb.append("    print(f'[ARTIFACT] {_dest}')\n");
        sb.append("plt.Figure.savefig = _tracked_savefig\n");
        sb.append("\n");

        if (dataSourceId != null) {
            DataSource ds = dataSourceMapper.selectById(dataSourceId);
            if (ds != null) {
                String sqlalchemyUrl = buildSqlalchemyUrl(ds);
                sb.append("from sqlalchemy import create_engine\n");
                sb.append("engine = create_engine('").append(sqlalchemyUrl).append("')\n");
                sb.append("\n");
            }
        }

        sb.append("# === User Code Start ===\n");
        sb.append(userCode);
        sb.append("\n# === User Code End ===\n");

        return sb.toString();
    }

    private String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        return switch (ds.getType()) {
            case "mysql" -> "mysql+pymysql://%s:%s@%s:%d/%s".formatted(
                user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
            case "postgresql" -> "postgresql+psycopg2://%s:%s@%s:%d/%s".formatted(
                user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
            default -> "mysql+pymysql://%s:%s@%s:%d/%s".formatted(
                user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
        };
    }

    private CompletableFuture<String> readAsync(InputStream is) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Error reading output: " + e.getMessage();
            }
        });
    }

    private String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT_BYTES) return output;
        int half = MAX_OUTPUT_BYTES / 2;
        return output.substring(0, half) + "\n\n... (输出已截断) ...\n\n" +
            output.substring(output.length() - half);
    }

    private List<String> extractArtifacts(String stdout) {
        List<String> artifacts = new ArrayList<>();
        if (stdout == null) return artifacts;
        for (String line : stdout.split("\n")) {
            if (line.contains("[ARTIFACT]")) {
                String path = line.substring(line.indexOf("[ARTIFACT]") + 10).trim();
                if (Files.exists(Path.of(path))) {
                    artifacts.add(path);
                }
            }
        }
        return artifacts;
    }

    private ProcessBuilder buildDockerProcess(Path scriptFile, Long dataSourceId) {
        String memLimit = maxMemoryMb + "m";
        String cpuLimit = String.valueOf(maxCpus);
        return new ProcessBuilder(
            "docker", "run", "--rm",
            "--memory=" + memLimit,
            "--cpus=" + cpuLimit,
            "-v", scriptFile.getParent() + ":/scripts",
            "-v", ARTIFACT_DIR + ":" + ARTIFACT_DIR,
            "python:3.11-slim",
            "python", "/scripts/" + scriptFile.getFileName()
        );
    }

    private ScheduledExecutorService startMemoryMonitor(Process process, int maxMb) {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "python-mem-monitor");
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleAtFixedRate(() -> {
            try {
                if (!process.isAlive()) return;
                long pid = process.pid();
                Path statusFile = Path.of("/proc/" + pid + "/status");
                if (Files.exists(statusFile)) {
                    String status = Files.readString(statusFile);
                    for (String line : status.split("\n")) {
                        if (line.startsWith("VmRSS:")) {
                            long rssKb = Long.parseLong(line.replaceAll("\\D", "").trim());
                            long rssMb = rssKb / 1024;
                            if (rssMb > maxMb) {
                                log.warn("[PYTHON] Process {} exceeded memory limit: {}MB > {}MB, killing",
                                    pid, rssMb, maxMb);
                                process.destroyForcibly();
                            }
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, 2, 5, TimeUnit.SECONDS);
        return monitor;
    }
}
