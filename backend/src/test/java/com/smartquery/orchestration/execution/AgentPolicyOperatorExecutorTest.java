package com.smartquery.orchestration.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.llm.LlmChunk;
import com.smartquery.llm.LlmService;
import com.smartquery.orchestration.AgentPolicyService;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.tool.ToolOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPolicyOperatorExecutorTest {
    @Test
    void enrichesEveryRecordWithAuditableDecisionAndPreservesRawInput() {
        AgentPolicyService policy = mock(AgentPolicyService.class);
        LlmService llm = mock(LlmService.class);
        ToolOrchestrator tools = mock(ToolOrchestrator.class);
        AgentPolicyService.AgentPolicySpec spec = new AgentPolicyService.AgentPolicySpec(
            "glm-5.1", "判断风险", List.of(), null, Set.of(),
            2, 0, 10, 1000, "agentDecision", "agentToolTrace", true);
        when(policy.validate(any())).thenReturn(spec);
        when(policy.toolDefinitions(spec)).thenReturn(List.of());
        when(llm.chatWithTools(any(), any(), any())).thenReturn(List.of(
            LlmChunk.text("高风险：金额与历史模式不一致"), LlmChunk.done("stop", 20, 10)));
        AgentPolicyOperatorExecutor executor = new AgentPolicyOperatorExecutor(
            policy, llm, tools, new ObjectMapper(), Runnable::run);
        Map<String, Object> raw = Map.of("orderId", "P-9", "amount", 9000);
        Map<String, Object> input = new java.util.LinkedHashMap<>(raw);
        input.put(LineageSupport.SOURCE_REFS, List.of("run:1:record:1"));
        input.put(LineageSupport.SOURCE_SNAPSHOTS, List.of(raw));
        OperatorVersion version = new OperatorVersion();
        version.setId(41L);
        OperatorExecutionContext context = new OperatorExecutionContext(1L, 2L, "agent",
            OperatorTypes.AGENT, version, Map.of(), Map.of(), Map.of(), Map.of(
                "rule", new OperatorExecutionResult(Map.of("records", List.of(input)), List.of(), "")));

        OperatorExecutionResult result = executor.execute(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = ((List<Map<String, Object>>) result.output().get("records")).get(0);
        assertEquals("高风险：金额与历史模式不一致", output.get("agentDecision"));
        assertEquals(List.of(raw), output.get(LineageSupport.SOURCE_SNAPSHOTS));
        assertEquals("AGENT_DECISION",
            ((Map<?, ?>) ((List<?>) output.get(LineageSupport.EVIDENCE)).get(0)).get("kind"));
        assertEquals(30, result.output().get("tokenCount"));
    }
}
