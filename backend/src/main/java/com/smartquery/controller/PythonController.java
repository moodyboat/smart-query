package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.Conversation;
import com.smartquery.entity.PythonExecution;
import com.smartquery.mapper.PythonExecutionMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/python")
@RequiredArgsConstructor
public class PythonController {

    private final PythonExecutionMapper pythonExecutionMapper;
    private final PythonExecutor pythonExecutor;
    private final ResourceAccessService resourceAccess;

    @GetMapping("/history/{conversationId}")
    public Result<List<PythonExecution>> history(@PathVariable Long conversationId) {
        resourceAccess.requireConversation(conversationId);
        return Result.ok(pythonExecutionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PythonExecution>()
                .eq(PythonExecution::getConversationId, conversationId)
                .orderByDesc(PythonExecution::getCreatedAt)));
    }

    @GetMapping("/{id}")
    public Result<PythonExecution> get(@PathVariable Long id) {
        return Result.ok(requireExecution(id));
    }

    /**
     * 直接执行 Python 代码 (不经过 LLM)
     */
    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@RequestBody Map<String, Object> request) {
        String code = (String) request.get("code");
        Long dataSourceId = request.get("data_source_id") != null
            ? ((Number) request.get("data_source_id")).longValue() : null;
        Long conversationId = request.get("conversation_id") != null
            ? ((Number) request.get("conversation_id")).longValue() : null;
        int timeoutMs = request.get("timeout_ms") != null
            ? ((Number) request.get("timeout_ms")).intValue() : 60000;

        if (code == null || code.isBlank()) {
            return Result.error("代码不能为空");
        }
        requireExecutionScope(conversationId, dataSourceId);

        PythonResult result = pythonExecutor.execute(code, dataSourceId, timeoutMs, conversationId);

        // 持久化
        try {
            PythonExecution execution = new PythonExecution();
            execution.setConversationId(conversationId);
            execution.setCode(code);
            execution.setStdout(result.stdout());
            execution.setStderr(result.stderr());
            execution.setExitCode(result.exitCode());
            execution.setExecutionTimeMs(result.executionTimeMs());
            execution.setStatus(result.exitCode() == 0 ? "success" : "error");
            execution.setDataSourceId(dataSourceId);
            pythonExecutionMapper.insert(execution);
        } catch (Exception e) {
            log.warn("[PYTHON] Failed to persist execution record: {}", e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exitCode", result.exitCode());
        response.put("stdout", result.stdout());
        response.put("stderr", result.stderr());
        response.put("executionTimeMs", result.executionTimeMs());
        response.put("artifacts", result.artifacts());

        return Result.ok(response);
    }

    /**
     * 重新执行之前的 Python 代码 (可修改代码)
     */
    @PostMapping("/{id}/rerun")
    public Result<Map<String, Object>> rerun(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> request
    ) {
        PythonExecution previous = requireExecution(id);

        // 使用修改后的代码，或原代码
        String code = previous.getCode();
        if (request != null && request.get("code") != null) {
            code = (String) request.get("code");
        }
        int timeoutMs = request != null && request.get("timeout_ms") != null
            ? ((Number) request.get("timeout_ms")).intValue() : 60000;

        PythonResult result = pythonExecutor.execute(code, previous.getDataSourceId(), timeoutMs, previous.getConversationId());

        // 持久化新执行记录
        try {
            PythonExecution execution = new PythonExecution();
            execution.setConversationId(previous.getConversationId());
            execution.setCode(code);
            execution.setStdout(result.stdout());
            execution.setStderr(result.stderr());
            execution.setExitCode(result.exitCode());
            execution.setExecutionTimeMs(result.executionTimeMs());
            execution.setStatus(result.exitCode() == 0 ? "success" : "error");
            execution.setDataSourceId(previous.getDataSourceId());
            pythonExecutionMapper.insert(execution);
        } catch (Exception e) {
            log.warn("[PYTHON] Failed to persist rerun record: {}", e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("previousExecutionId", id);
        response.put("exitCode", result.exitCode());
        response.put("stdout", result.stdout());
        response.put("stderr", result.stderr());
        response.put("executionTimeMs", result.executionTimeMs());
        response.put("artifacts", result.artifacts());

        return Result.ok(response);
    }

    private PythonExecution requireExecution(Long id) {
        PythonExecution execution = pythonExecutionMapper.selectById(id);
        if (execution == null) throw new BusinessException(404, "执行记录不存在: " + id);
        if (execution.getConversationId() == null) {
            resourceAccess.requireAdmin();
        } else {
            resourceAccess.requireConversation(execution.getConversationId());
        }
        return execution;
    }

    private void requireExecutionScope(Long conversationId, Long dataSourceId) {
        if (conversationId == null) {
            resourceAccess.requireAdmin();
            return;
        }
        Conversation conversation = resourceAccess.requireConversation(conversationId);
        if (conversation.getDataSourceId() != null && dataSourceId != null
                && !conversation.getDataSourceId().equals(dataSourceId)) {
            throw new BusinessException(403, "执行数据源与会话绑定的数据源不一致");
        }
    }
}
