package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.llm.LlmService;
import com.smartquery.tool.LlmTool;
import com.smartquery.tool.ToolOrchestrator;
import com.smartquery.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPolicyServiceTest {
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final LlmService llm = mock(LlmService.class);
    private final DataSourceQueryPolicyService dataSourcePolicy = mock(DataSourceQueryPolicyService.class);
    private final AgentPolicyService service = new AgentPolicyService(registry, llm, dataSourcePolicy);

    @Test
    void locksReadOnlyToolsDataSourceAndBudgets() {
        LlmTool tool = tool("execute_sql", true, true);
        when(registry.getTool("execute_sql")).thenReturn(Optional.of(tool));
        when(llm.isAvailable("glm-5.1")).thenReturn(true);

        AgentPolicyService.AgentPolicySpec spec = service.validate(Map.of(
            "model", "glm-5.1", "instruction", "判断订单是否异常",
            "allowedTools", List.of("execute_sql"), "dataSourceId", 6,
            "allowedTables", List.of("payments"), "maxToolCalls", 2));
        List<ToolOrchestrator.ToolCall> calls = service.authorizeCalls(spec, List.of(
            new ToolOrchestrator.ToolCall("c1", "execute_sql", Map.of(
                "sql", "SELECT * FROM payments", "data_source_id", 999,
                "filters", Map.of("unsafe", "value")))));

        assertEquals(6L, calls.get(0).input().get("data_source_id"));
        assertFalse(calls.get(0).input().containsKey("filters"));
        assertEquals(2, spec.maxToolCalls());
    }

    @Test
    void rejectsMutatingOrUnknownToolAndNodeOverride() {
        when(llm.isAvailable("glm-5.1")).thenReturn(true);
        LlmTool writeTool = tool("write_report", false, false);
        when(registry.getTool("write_report")).thenReturn(Optional.of(writeTool));

        assertThrows(BusinessException.class, () -> service.validate(Map.of(
            "model", "glm-5.1", "instruction", "生成报告",
            "allowedTools", List.of("write_report"))));
        assertThrows(BusinessException.class, () -> service.validateNodeConfig(Map.of(
            "allowedTools", List.of("write_report"))));
    }

    private LlmTool tool(String name, boolean readOnly, boolean requireDatabase) {
        LlmTool tool = mock(LlmTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.isReadOnly()).thenReturn(readOnly);
        when(tool.isDestructive()).thenReturn(!readOnly);
        when(tool.requireDatabase()).thenReturn(requireDatabase);
        when(tool.getDescription()).thenReturn(name);
        when(tool.getJsonSchema()).thenReturn(Map.of("type", "object"));
        return tool;
    }
}
