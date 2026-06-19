package com.smartquery.python;

import com.smartquery.datasource.DataSourceContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.util.DbUrlUtil;
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
    private final PythonCircuitBreaker circuitBreaker;

    @Value("${smart-query.python.max-output-bytes:65536}")
    private int maxOutputBytes;

    @Value("${smart-query.python.max-timeout-ms:600000}")
    private int maxTimeoutMs;

    @Value("${smart-query.python.artifact-dir:/tmp/smartquery-artifacts}")
    private String artifactDir;

    @Value("${smart-query.python.workspace-base:/tmp/smartquery-workspace}")
    private String workspaceBase;

    @Value("${smart-query.python.command:python3}")
    private String pythonCmd;

    private String resolvedPythonCmd; // 运行时解析后的 Python 命令路径

    @Value("${smart-query.python.max-memory-mb:512}")
    private int maxMemoryMb;

    @Value("${smart-query.python.max-cpus:1}")
    private double maxCpus;

    @Value("${smart-query.python.execution-mode:process}")
    private String executionMode;

    @Value("${smart-query.python.stream-drain-timeout-seconds:5}")
    private int streamDrainTimeoutSeconds;

    @Value("${smart-query.python.docker-image:python:3.11-slim}")
    private String dockerImage;

    /** DooD 共享卷名（后端经宿主 docker socket 调 python 容器时，按卷名挂载共享 artifact/workspace） */
    @Value("${smart-query.python.docker-shared-volume:}")
    private String dockerSharedVolume;

    @Value("${smart-query.python.docker-workspace-volume:}")
    private String dockerWorkspaceVolume;

    /** DooD 网络：python 容器加入此网络，才能解析 compose 服务名（如 mysql） */
    @Value("${smart-query.python.docker-network:}")
    private String dockerNetwork;

    @Value("${smart-query.python.mem-monitor-initial-delay-seconds:2}")
    private int memMonitorInitialDelaySec;

    @Value("${smart-query.python.mem-monitor-period-seconds:5}")
    private int memMonitorPeriodSec;

    @Value("${smart-query.python.progress-report-interval:5}")
    private int progressReportInterval;

    public PythonExecutor(DataSourceMapper dataSourceMapper, PythonCircuitBreaker circuitBreaker) {
        this.dataSourceMapper = dataSourceMapper;
        this.circuitBreaker = circuitBreaker;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(Path.of(artifactDir));
            Files.createDirectories(Path.of(workspaceBase));

            // 转换 Unix 风格路径为 Windows 原生路径（Git Bash 兼容）
            resolvedPythonCmd = pythonCmd;
            if (pythonCmd.matches("^/[a-zA-Z]/.*")) {
                // Git Bash 路径格式: /c/Python311/python.exe -> C:\Python311\python.exe
                String driveLetter = pythonCmd.substring(1, 2).toUpperCase();
                resolvedPythonCmd = driveLetter + ":" + pythonCmd.substring(2).replace("/", "\\");
                log.info("[PYTHON] 转换 Git Bash 路径: {} -> {}", pythonCmd, resolvedPythonCmd);
            }

            log.info("[PYTHON] Python执行器初始化完成: command={}, mode={}, artifactDir={}, workspaceBase={}",
                resolvedPythonCmd, executionMode, artifactDir, workspaceBase);
        } catch (IOException e) {
            log.error("[PYTHON] Failed to create directories: artifactDir={}, workspaceBase={}: {}",
                artifactDir, workspaceBase, e.getMessage());
            throw new RuntimeException("Python执行器目录创建失败: " + e.getMessage(), e);
        }
    }

    public PythonResult execute(String code, Long dataSourceId, int timeoutMs) {
        return execute(code, dataSourceId, timeoutMs, null);
    }

    public PythonResult execute(String code, Long dataSourceId, int timeoutMs, Long conversationId) {
        return execute(code, dataSourceId, timeoutMs, conversationId, null);
    }

    public PythonResult execute(String code, Long dataSourceId, int timeoutMs, Long conversationId, java.util.function.BiConsumer<String, Integer> progressCallback) {
        timeoutMs = Math.min(Math.max(timeoutMs, 1000), maxTimeoutMs);
        long start = System.currentTimeMillis();

        if (!circuitBreaker.allowExecution()) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            return PythonResult.error("Python 执行暂时不可用（熔断保护中，连续失败 " +
                circuitBreaker.getConsecutiveFailures() + " 次），请稍后重试", -10, elapsed);
        }

        ScheduledExecutorService memoryMonitor = null;
        Path tempFile = null;
        try {
            // 安全校验 — 阻止危险操作
            PythonSandbox.validate(code);

            // 临时脚本写入 artifact-dir（而非系统 /tmp）：docker 模式下后端经宿主 docker socket
            // 调起的 python 容器，需经共享卷才能读到脚本
            Path scriptDir = Path.of(artifactDir);
            Files.createDirectories(scriptDir);
            tempFile = Files.createTempFile(scriptDir, "smartquery_", ".py");
            // Restrict temp file to owner-only to prevent credential leakage (skip on Windows)
            try {
                Files.setPosixFilePermissions(tempFile, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | java.io.IOException e) {
                // Windows or filesystem doesn't support POSIX permissions, skip
                log.debug("[PYTHON] Cannot set POSIX permissions (non-POSIX filesystem): {}", e.getMessage());
            }
            String[] wrapped = wrapCode(code, dataSourceId, conversationId);
            Files.writeString(tempFile, wrapped[0]);

            ProcessBuilder pb;
            if ("docker".equals(executionMode)) {
                pb = buildDockerProcess(tempFile, dataSourceId, wrapped[1]);
            } else {
                pb = new ProcessBuilder(resolvedPythonCmd, tempFile.toString());
            }
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            if (wrapped[1] != null) {
                pb.environment().put("_SMARTQUERY_DB_URL", wrapped[1]);
            }

            log.debug("[PYTHON] Starting process: command={}, script={}", resolvedPythonCmd, tempFile);
            Process process = pb.start();
            log.debug("[PYTHON] Process started: pid={}", process.pid());

            // Memory monitoring thread for process mode
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
                        if (progressCallback != null && lineCount % progressReportInterval == 0) {
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
                process.waitFor(5, TimeUnit.SECONDS);
                stdoutReadFuture.cancel(true);
                stderrFuture.cancel(true);
                if (memoryMonitor != null) memoryMonitor.shutdownNow();
                Files.deleteIfExists(tempFile);
                return PythonResult.timeout("执行超时 (" + timeoutMs + "ms)", elapsed);
            }

            if (memoryMonitor != null) memoryMonitor.shutdownNow();

            stdoutReadFuture.get(streamDrainTimeoutSeconds, TimeUnit.SECONDS);
            String stdout = truncateOutput(stdoutBuilder.toString());
            String stderr = truncateOutput(stderrFuture.get(streamDrainTimeoutSeconds, TimeUnit.SECONDS));
            int exitCode = process.exitValue();

            Files.deleteIfExists(tempFile);

            // 提取 artifact 路径
            List<String> artifacts = extractArtifacts(stdout);

            log.info("[PYTHON] exit={}, time={}ms, stdout={}chars, stderr={}chars, artifacts={}",
                exitCode, elapsed, stdout.length(), stderr.length(), artifacts.size());

            if (exitCode == 0) {
                circuitBreaker.recordSuccess();
            } else {
                circuitBreaker.recordFailure();
            }

            return new PythonResult(stdout, stderr, exitCode, artifacts, elapsed);
        } catch (SecurityException e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            circuitBreaker.recordFailure();
            if (memoryMonitor != null) memoryMonitor.shutdownNow();
            log.error("[PYTHON] Security exception: {}", e.getMessage(), e);
            return PythonResult.error("安全检查未通过: " + e.getMessage(), -3, elapsed);
        } catch (Exception e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            circuitBreaker.recordFailure();
            if (memoryMonitor != null) memoryMonitor.shutdownNow();
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            log.error("[PYTHON] Execution exception: command={}, error={}", resolvedPythonCmd, e.getMessage(), e);
            return PythonResult.error("执行异常: " + e.getClass().getSimpleName() + " - " + e.getMessage(), -2, elapsed);
        }
    }

    /**
     * 包装用户代码: 注入 DB 连接 + 安全限制
     * @return [wrappedCode, dbUrl or null]
     */
    private String[] wrapCode(String userCode, Long dataSourceId, Long conversationId) {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        String[] dbUrlHolder = new String[1];
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
            String workspaceDir = workspaceBase + "/" + conversationId;
            sb.append("_workspace = r'").append(workspaceDir).append("'\n");
            sb.append("os.makedirs(_workspace, exist_ok=True)\n");
        } else {
            sb.append("_workspace = '/tmp'\n");
        }
        sb.append("\n");

        // Configure matplotlib output directory
        sb.append("import matplotlib\n");
        sb.append("matplotlib.use('Agg')\n");
        sb.append("import matplotlib.pyplot as plt\n");
        sb.append("_artifact_dir = r'").append(artifactDir).append("'\n");
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
                String sqlalchemyUrl = DbUrlUtil.buildSqlalchemyUrl(ds);
                // Pass DB URL via environment variable instead of embedding in script
                sb.append("import os\n");
                sb.append("_db_url = os.environ.get('_SMARTQUERY_DB_URL', '')\n");
                sb.append("if _db_url:\n");
                sb.append("    from sqlalchemy import create_engine\n");
                sb.append("    engine = create_engine(_db_url)\n");
                sb.append("\n");
                dbUrlHolder[0] = sqlalchemyUrl;
            }
        }

        sb.append("# === User Code Start ===\n");
        sb.append(userCode);
        sb.append("\n# === User Code End ===\n");

        return new String[] { sb.toString(), dbUrlHolder[0] };
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
        if (output.length() <= maxOutputBytes) return output;
        int half = maxOutputBytes / 2;
        return output.substring(0, half) + "\n\n... (输出已截断) ...\n\n" +
            output.substring(output.length() - half);
    }

    private List<String> extractArtifacts(String stdout) {
        List<String> artifacts = new ArrayList<>();
        if (stdout == null) return artifacts;
        Path artifactRoot = Path.of(artifactDir).toAbsolutePath();
        for (String line : stdout.split("\n")) {
            if (line.contains("[ARTIFACT]")) {
                String path = line.substring(line.indexOf("[ARTIFACT]") + 10).trim();
                if (Files.exists(Path.of(path))) {
                    Path resolved = Path.of(path).toAbsolutePath();
                    if (resolved.startsWith(artifactRoot)) {
                        artifacts.add(path);
                    } else {
                        log.warn("[PYTHON] Artifact path traversal blocked: {}", path);
                    }
                }
            }
        }
        return artifacts;
    }

    private ProcessBuilder buildDockerProcess(Path scriptFile, Long dataSourceId, String dbUrl) {
        String memLimit = maxMemoryMb + "m";
        String cpuLimit = String.valueOf(maxCpus);
        boolean dood = dockerSharedVolume != null && !dockerSharedVolume.isBlank();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("docker"); cmd.add("run"); cmd.add("--rm");
        cmd.add("--memory=" + memLimit);
        cmd.add("--cpus=" + cpuLimit);
        if (dood) {
            // DooD：后端容器经宿主 docker 调起 python 容器，须按"卷名"挂载（路径挂载会被宿主解析失败）。
            // 卷挂到与后端相同的路径，使脚本/产物在 python 容器内同路径可见。
            cmd.add("-v"); cmd.add(dockerSharedVolume + ":" + artifactDir);
            if (dockerWorkspaceVolume != null && !dockerWorkspaceVolume.isBlank()) {
                cmd.add("-v"); cmd.add(dockerWorkspaceVolume + ":" + workspaceBase);
            }
        } else {
            // 同主机（非 DooD）：按路径挂载
            cmd.add("-v"); cmd.add(scriptFile.getParent() + ":/scripts");
            cmd.add("-v"); cmd.add(artifactDir + ":" + artifactDir);
        }
        // docker run 不继承父进程 env，须显式 -e 转发给容器
        cmd.add("-e"); cmd.add("PYTHONIOENCODING=utf-8");
        if (dbUrl != null && !dbUrl.isBlank()) {
            cmd.add("-e"); cmd.add("_SMARTQUERY_DB_URL=" + dbUrl);
        }
        // python 容器加入 compose 网络，才能解析服务名（如 mysql）
        if (dockerNetwork != null && !dockerNetwork.isBlank()) {
            cmd.add("--network"); cmd.add(dockerNetwork);
        }
        cmd.add(dockerImage);
        cmd.add("python");
        // 脚本路径：DooD 下脚本在共享卷的 artifactDir 内（python 容器同路径可见）；否则走 /scripts
        cmd.add(dood ? scriptFile.toString() : "/scripts/" + scriptFile.getFileName());
        return new ProcessBuilder(cmd);
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
                long rssMb = getProcessRssMb(pid);
                if (rssMb > 0 && rssMb > maxMb) {
                    log.warn("[PYTHON] Process {} exceeded memory limit: {}MB > {}MB, killing",
                        pid, rssMb, maxMb);
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                    log.debug("[PYTHON] Memory monitor check failed: {}", e.getMessage());
                }
        }, memMonitorInitialDelaySec, memMonitorPeriodSec, TimeUnit.SECONDS);
        return monitor;
    }

    private long getProcessRssMb(long pid) {
        try {
            Path statusFile = Path.of("/proc/" + pid + "/status");
            if (Files.exists(statusFile)) {
                String status = Files.readString(statusFile);
                for (String line : status.split("\n")) {
                    if (line.startsWith("VmRSS:")) {
                        long rssKb = Long.parseLong(line.replaceAll("\\D", "").trim());
                        return rssKb / 1024;
                    }
                }
            }
            // macOS fallback: use ps command
            Process psProc = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                .redirectErrorStream(true).start();
            String output = new String(psProc.getInputStream().readAllBytes()).trim();
            psProc.waitFor(5, TimeUnit.SECONDS);
            if (!output.isEmpty()) {
                long rssKb = Long.parseLong(output.split("\\s+")[0]);
                return rssKb / 1024;
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
