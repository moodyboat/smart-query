package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import com.smartquery.entity.RuntimeProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** File protocol client for the no-network custom-rule Docker sandbox. */
@Component
@RequiredArgsConstructor
public class RuleRuntimeClient {
    public static final int PROTOCOL_VERSION = 1;
    private static final String RUNTIME_RESOURCE = "python/rule_runtime.py";

    private final PythonExecutor pythonExecutor;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.python.artifact-dir:/tmp/smartquery-artifacts}")
    private String artifactDir;

    @Value("${smart-query.orchestration.rule-sandbox.timeout-ms:10000}")
    private int defaultTimeoutMs;

    @Value("${smart-query.orchestration.trial.max-records:1000}")
    private int maxRecords;

    public RuntimeResult validate(Map<String, Object> artifact) {
        return invoke("validate", artifact, List.of(), Map.of(), defaultTimeoutMs, null, null);
    }

    public RuntimeResult validate(Map<String, Object> artifact, RuntimeProfile profile) {
        return invoke("validate", artifact, List.of(), Map.of(), defaultTimeoutMs, null, profile);
    }

    public RuntimeResult execute(Map<String, Object> artifact, List<Map<String, Object>> records,
                                 Map<String, Object> parameters, Long runId, String nodeId) {
        return execute(artifact, records, parameters, runId, nodeId, null);
    }

    public RuntimeResult execute(Map<String, Object> artifact, List<Map<String, Object>> records,
                                 Map<String, Object> parameters, Long runId, String nodeId,
                                 RuntimeProfile profile) {
        String key = "rule-run-" + runId + "-" + nodeId;
        return invoke("execute", artifact, records, parameters, defaultTimeoutMs, key, profile);
    }

    private RuntimeResult invoke(String action, Map<String, Object> artifact,
                                 List<Map<String, Object>> records,
                                 Map<String, Object> parameters, int timeoutMs,
                                 String executionKey, RuntimeProfile profile) {
        Path sandbox = null;
        try {
            Path root = Path.of(artifactDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            sandbox = Files.createTempDirectory(root, "rule-sandbox-");
            Path request = sandbox.resolve("request.json");
            Path result = sandbox.resolve("result.json");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("protocolVersion", PROTOCOL_VERSION);
            payload.put("sourceCode", artifact.get("sourceCode"));
            payload.put("entrypoint", artifact.get("entrypoint"));
            payload.put("tests", artifact.get("tests"));
            payload.put("records", records == null ? List.of() : records);
            payload.put("parameters", parameters == null ? Map.of() : parameters);
            payload.put("allowedModules", artifact.getOrDefault("allowedModules", List.of()));
            payload.put("maxRecords", maxRecords);
            Files.write(request, objectMapper.writeValueAsBytes(payload),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            restrictOwnerOnly(request);

            List<String> arguments = List.of(action, "--request", request.getFileName().toString(),
                "--result", result.getFileName().toString());
            String key = executionKey == null ? "rule-validation-" + UUID.randomUUID() : executionKey;
            PythonResult process = profile == null
                ? pythonExecutor.executeIsolatedResource(RUNTIME_RESOURCE, arguments, timeoutMs, sandbox, key)
                : pythonExecutor.executeIsolatedResource(RUNTIME_RESOURCE, arguments, timeoutMs, sandbox,
                    key, profile.getImageRef());
            Map<String, Object> response = Files.exists(result)
                ? objectMapper.readValue(result.toFile(), new TypeReference<>() {}) : Map.of();
            return new RuntimeResult(process, response);
        } catch (IOException e) {
            throw new IllegalStateException("规则沙箱协议文件读写失败: " + e.getMessage(), e);
        } finally {
            deleteTree(sandbox);
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

    private void deleteTree(Path directory) {
        if (directory == null) return;
        Path root = Path.of(artifactDir).toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) return;
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
        }
    }

    public record RuntimeResult(PythonResult process, Map<String, Object> payload) {
        public boolean successful() {
            return process.exitCode() == 0 && "success".equals(payload.get("status"));
        }

        public String errorMessage() {
            if (payload.get("error") != null) return String.valueOf(payload.get("error"));
            if (process.stderr() != null && !process.stderr().isBlank()) return process.stderr();
            return process.stdout() == null ? "规则沙箱未返回结果" : process.stdout();
        }
    }
}
