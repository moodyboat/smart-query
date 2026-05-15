package com.smartquery.tool.impl;

import com.smartquery.entity.PythonExecution;
import com.smartquery.engine.ReActEvent;
import com.smartquery.mapper.PythonExecutionMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonExecuteTool implements LlmTool {

    private final PythonExecutor pythonExecutor;
    private final PythonExecutionMapper pythonExecutionMapper;

    @Override
    public String getName() { return "execute_python"; }

    @Override
    public String getDescription() {
        return "执行 Python 代码进行数据分析和挖掘。已预装 pandas, numpy, matplotlib, scipy, scikit-learn, sqlalchemy。数据库连接已自动注入为变量 `engine`。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "code", Map.of("type", "string", "description", "要执行的 Python 代码"),
                "data_source_id", Map.of("type", "integer", "description", "数据源 ID (可选)"),
                "timeout_ms", Map.of("type", "integer", "description", "超时时间(ms), 默认60000, 最大600000")
            ),
            "required", List.of("code")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        String code = (String) input.get("code");
        Long dsId = input.get("data_source_id") != null
            ? ((Number) input.get("data_source_id")).longValue()
            : context.dataSourceId();
        int timeoutMs = input.get("timeout_ms") != null
            ? ((Number) input.get("timeout_ms")).intValue()
            : 60000;

        if (code == null || code.isBlank()) {
            return ToolResult.error(getName(), "代码不能为空", System.currentTimeMillis() - start);
        }

        try {
            PythonResult result = pythonExecutor.execute(code, dsId, timeoutMs, context.conversationId(),
                (output, elapsedMs) -> {
                    context.emitEvent(new ReActEvent.PythonProgress(output, elapsedMs));
                });
            long duration = System.currentTimeMillis() - start;

            // 持久化执行记录
            PythonExecution execution = new PythonExecution();
            execution.setConversationId(context.conversationId());
            execution.setCode(code);
            execution.setStdout(result.stdout());
            execution.setStderr(result.stderr());
            execution.setExitCode(result.exitCode());
            execution.setExecutionTimeMs((int) duration);
            execution.setStatus(result.exitCode() == 0 ? "success" : "error");
            execution.setDataSourceId(dsId);
            pythonExecutionMapper.insert(execution);

            if (result.exitCode() == 0) {
                StringBuilder output = new StringBuilder();
                if (result.stdout() != null && !result.stdout().isBlank()) {
                    output.append("输出:\n").append(result.stdout());
                }
                if (!result.artifacts().isEmpty()) {
                    output.append("\n生成的文件: ").append(String.join(", ", result.artifacts()));
                }
                Map<String, Object> pyData = Map.of(
                    "stdout", result.stdout() != null ? result.stdout() : "",
                    "stderr", "",
                    "exitCode", result.exitCode(),
                    "artifacts", result.artifacts() != null ? result.artifacts() : java.util.Collections.emptyList()
                );
                return new ToolResult(getName(), true, output.toString(), null, duration, List.of(pyData), null);
            } else {
                String error = result.stderr() != null ? result.stderr() : "未知错误";
                Map<String, Object> pyData = Map.of(
                    "stdout", result.stdout() != null ? result.stdout() : "",
                    "stderr", result.stderr() != null ? result.stderr() : "",
                    "exitCode", result.exitCode(),
                    "artifacts", result.artifacts() != null ? result.artifacts() : java.util.Collections.emptyList()
                );
                return new ToolResult(getName(), false, result.stdout() != null ? result.stdout() : "",
                    "执行错误 (exit=" + result.exitCode() + "):\n" + error, duration, List.of(pyData),
                    ToolError.of(ToolError.ErrorCode.PYTHON_ERROR, "Python 执行错误 (exit=" + result.exitCode() + ")"));
            }
        } catch (Exception e) {
            return ToolResult.error(getName(), "Python 执行异常: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean isConcurrencySafe() { return true; }

    @Override
    public long getTimeoutMs() { return 600000; }
}
