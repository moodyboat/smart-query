package com.smartquery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * File-based protocol client for the version-controlled mining Python runtime.
 * Request and response data never share stdout with process logs.
 */
@Component
@RequiredArgsConstructor
public class MiningRuntimeClient {

    public static final int PROTOCOL_VERSION = 1;
    public static final int ARTIFACT_SCHEMA_VERSION = 3;
    private static final String RUNTIME_RESOURCE = "python/mining_runtime.py";

    private final PythonExecutor pythonExecutor;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.python.artifact-dir:/tmp/smartquery-artifacts}")
    private String artifactDir;

    public RuntimeResult execute(String action, Map<String, Object> input,
                                 Long dataSourceId, int timeoutMs) {
        return execute(action, input, dataSourceId, timeoutMs, null, null);
    }

    public RuntimeResult execute(String action, Map<String, Object> input,
                                 Long dataSourceId, int timeoutMs, Long executionId,
                                 Consumer<ProgressUpdate> progressConsumer) {
        return execute(action, input, dataSourceId, timeoutMs, executionId, progressConsumer, null);
    }

    public RuntimeResult execute(String action, Map<String, Object> input,
                                 Long dataSourceId, int timeoutMs, Long executionId,
                                 Consumer<ProgressUpdate> progressConsumer,
                                 Consumer<String> logConsumer) {
        Path requestFile = null;
        Path resultFile = null;
        Path progressFile = null;
        ScheduledExecutorService progressWatcher = null;
        try {
            Path protocolDir = Path.of(artifactDir);
            Files.createDirectories(protocolDir);
            requestFile = Files.createTempFile(protocolDir, "mining-request-", ".json");
            resultFile = protocolDir.resolve(requestFile.getFileName().toString().replace("request", "result"));
            if (executionId != null || progressConsumer != null) {
                progressFile = protocolDir.resolve(requestFile.getFileName().toString().replace("request", "progress"));
            }

            Map<String, Object> request = new LinkedHashMap<>(input);
            request.put("protocolVersion", PROTOCOL_VERSION);
            if (executionId != null) request.put("executionId", executionId);
            byte[] requestBytes = objectMapper.writeValueAsBytes(request);
            Files.write(requestFile, requestBytes, StandardOpenOption.TRUNCATE_EXISTING);
            restrictOwnerOnly(requestFile);

            List<String> arguments = new ArrayList<>(List.of(
                action, "--request", requestFile.toString(), "--result", resultFile.toString()));
            if (progressFile != null) {
                arguments.add("--progress");
                arguments.add(progressFile.toString());
            }
            if (progressFile != null && progressConsumer != null) {
                Path watchedProgressFile = progressFile;
                AtomicReference<String> lastUpdate = new AtomicReference<>();
                progressWatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "mining-progress-" + executionId);
                    thread.setDaemon(true);
                    return thread;
                });
                progressWatcher.scheduleAtFixedRate(
                    () -> publishProgress(watchedProgressFile, lastUpdate, progressConsumer),
                    0, 500, TimeUnit.MILLISECONDS);
            }

            PythonResult process = executionId == null
                ? pythonExecutor.executeResource(RUNTIME_RESOURCE, arguments, dataSourceId, timeoutMs)
                : pythonExecutor.executeResource(RUNTIME_RESOURCE, arguments, dataSourceId, timeoutMs,
                    executionKey(executionId), logConsumer);
            if (progressFile != null && progressConsumer != null) {
                publishProgress(progressFile, new AtomicReference<>(), progressConsumer);
            }

            Map<String, Object> payload = Files.exists(resultFile)
                ? objectMapper.readValue(resultFile.toFile(), new TypeReference<>() {})
                : Map.of();
            if (!payload.isEmpty()) {
                Object responseVersion = payload.get("protocolVersion");
                if (!(responseVersion instanceof Number number)
                        || number.intValue() != PROTOCOL_VERSION) {
                    throw new IllegalStateException("Python 运行协议版本不兼容: " + responseVersion);
                }
            }
            return new RuntimeResult(process, payload);
        } catch (IOException e) {
            throw new IllegalStateException("模型运行协议文件读写失败: " + e.getMessage(), e);
        } finally {
            if (progressWatcher != null) progressWatcher.shutdownNow();
            deleteQuietly(requestFile);
            deleteQuietly(resultFile);
            deleteQuietly(progressFile);
        }
    }

    public boolean cancel(Long executionId) {
        return pythonExecutor.cancelExecution(executionKey(executionId));
    }

    private String executionKey(Long executionId) {
        return executionId == null ? null : "mining-execution-" + executionId;
    }

    private void publishProgress(Path progressFile, AtomicReference<String> lastUpdate,
                                 Consumer<ProgressUpdate> consumer) {
        try {
            if (!Files.exists(progressFile)) return;
            Map<String, Object> payload = objectMapper.readValue(
                progressFile.toFile(), new TypeReference<>() {});
            String marker = String.valueOf(payload.get("updatedAt")) + ":" + payload.get("stage")
                + ":" + payload.get("progress");
            if (marker.equals(lastUpdate.getAndSet(marker))) return;
            Object version = payload.get("protocolVersion");
            if (!(version instanceof Number number) || number.intValue() != PROTOCOL_VERSION) return;
            int progress = payload.get("progress") instanceof Number progressNumber
                ? progressNumber.intValue() : 0;
            consumer.accept(new ProgressUpdate(
                String.valueOf(payload.getOrDefault("stage", "RUNNING")),
                Math.max(0, Math.min(100, progress)),
                String.valueOf(payload.getOrDefault("message", ""))));
        } catch (Exception ignored) {
            // Atomic replacement normally prevents partial JSON. A single failed
            // poll is harmless because the next poll reads the latest snapshot.
        }
    }

    public record ProgressUpdate(String stage, int progressPercent, String message) {}

    public record RuntimeResult(PythonResult process, Map<String, Object> payload) {
        public boolean successful() {
            return process.exitCode() == 0 && "success".equals(payload.get("status"));
        }

        public String errorMessage() {
            Object protocolError = payload.get("error");
            if (protocolError != null) return String.valueOf(protocolError);
            if (process.stderr() != null && !process.stderr().isBlank()) return process.stderr();
            if (process.stdout() != null && !process.stdout().isBlank()) return process.stdout();
            return "Python 运行器未返回结构化结果";
        }
    }

    private void restrictOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some mounted filesystems do not support POSIX permissions.
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
