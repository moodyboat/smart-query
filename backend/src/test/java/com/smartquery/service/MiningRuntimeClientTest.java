package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningRuntimeClientTest {

    @TempDir
    Path tempDir;

    @Test
    void readsStructuredResultFileAndTreatsStdoutAsLogsOnly() throws Exception {
        PythonExecutor executor = mock(PythonExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MiningRuntimeClient client = new MiningRuntimeClient(executor, objectMapper);
        ReflectionTestUtils.setField(client, "artifactDir", tempDir.toString());

        when(executor.executeResource(eq("python/mining_runtime.py"), any(), anyLong(), anyInt()))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                List<String> arguments = invocation.getArgument(1);
                Path resultPath = Path.of(arguments.get(arguments.indexOf("--result") + 1));
                objectMapper.writeValue(resultPath.toFile(), Map.of(
                    "protocolVersion", MiningRuntimeClient.PROTOCOL_VERSION,
                    "status", "success",
                    "metrics", Map.of("test_accuracy", 0.9),
                    "model_path", "/models/model.joblib"
                ));
                return new PythonResult(
                    "ordinary log line\n[TRAIN_RESULT] {\"metrics\":{\"test_accuracy\":0.0}}\n",
                    "", 0, List.of(), 12);
            });

        MiningRuntimeClient.RuntimeResult result = client.execute(
            "train", Map.of("modelId", 1), 7L, 1000);

        assertTrue(result.successful());
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.payload().get("metrics");
        assertEquals(0.9, ((Number) metrics.get("test_accuracy")).doubleValue());
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "协议临时文件应在读取后清理");
        }
    }
}
