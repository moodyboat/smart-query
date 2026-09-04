package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.PolicyDraft;
import com.smartquery.llm.LlmService;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.PolicyDraftMapper;
import com.smartquery.orchestration.execution.AgentPolicyOperatorExecutor;
import com.smartquery.orchestration.execution.SqlAstOperatorExecutor;
import com.smartquery.service.ResourceAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyAuthoringServiceTest {
    private final PolicyDraftMapper draftMapper = mock(PolicyDraftMapper.class);
    private final VersionCatalogService versionCatalog = mock(VersionCatalogService.class);
    private final DataSourceQueryPolicyService dataSourcePolicy = mock(DataSourceQueryPolicyService.class);
    private final AgentPolicyService agentPolicy = mock(AgentPolicyService.class);
    private final LlmService llm = mock(LlmService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PolicyAuthoringService service;

    @BeforeEach
    void setUp() {
        service = new PolicyAuthoringService(draftMapper, mock(ChatMessageMapper.class),
            mock(ResourceAccessService.class), versionCatalog, new ContentHashService(objectMapper),
            mock(RuntimeProfileService.class), mock(DependencyCenterService.class),
            mock(SqlAstPolicyService.class), agentPolicy,
            dataSourcePolicy, mock(SqlAstOperatorExecutor.class), mock(AgentPolicyOperatorExecutor.class),
            mock(OperatorApprovalService.class), llm, objectMapper);
        ReflectionTestUtils.setField(service, "defaultModel", "chat-default-model");
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "tester", "USER"));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void sqlDialogueCannotReplacePinnedDataSourceOrTableWhitelist() throws Exception {
        operator(OperatorTypes.DATA);
        when(llm.chat(any(), any())).thenReturn("""
            {"sql":"SELECT order_id FROM payments","dataSourceId":999,
             "allowedTables":["secrets"],"sourceRefFields":["order_id"]}
            """);

        service.generate(5L, Map.of("instruction", "查询重复订单", "dataSourceId", 7,
            "allowedTables", List.of("payments")));

        ArgumentCaptor<PolicyDraft> captor = ArgumentCaptor.forClass(PolicyDraft.class);
        verify(draftMapper).insert(captor.capture());
        Map<String, Object> raw = objectMapper.readValue(captor.getValue().getRawSpec(), new TypeReference<>() {});
        assertEquals(7, ((Number) raw.get("dataSourceId")).intValue());
        assertEquals(List.of("payments"), raw.get("allowedTables"));
        assertFalse(String.valueOf(raw).contains("secrets"));
    }

    @Test
    void agentDialogueCannotAddToolsOutsidePinnedReadOnlySelection() throws Exception {
        operator(OperatorTypes.AGENT);
        when(agentPolicy.eligibleTools()).thenReturn(List.of(new AgentPolicyService.AgentToolView(
            "read_orders", "只读订单查询", false, 1_000, Map.of("type", "object"))));
        when(llm.chat(any(), any())).thenReturn("""
            {"instruction":"判断订单风险","allowedTools":["delete_orders"],
             "model":"untrusted-model","responseField":"decision","traceField":"trace"}
            """);

        service.generate(5L, Map.of("instruction", "判断风险", "allowedTools", List.of("read_orders"),
            "runtimeModel", "untrusted-client-model"));

        ArgumentCaptor<PolicyDraft> captor = ArgumentCaptor.forClass(PolicyDraft.class);
        verify(draftMapper).insert(captor.capture());
        Map<String, Object> raw = objectMapper.readValue(captor.getValue().getRawSpec(), new TypeReference<>() {});
        assertEquals(List.of("read_orders"), raw.get("allowedTools"));
        assertEquals("chat-default-model", raw.get("model"));
        assertFalse(String.valueOf(raw).contains("untrusted-client-model"));
        assertFalse(String.valueOf(raw).contains("delete_orders"));
        assertFalse(String.valueOf(raw).contains("untrusted-model"));
        Map<String, Object> output = objectMapper.readValue(
            captor.getValue().getOutputSchema(), new TypeReference<>() {});
        Map<String, Object> properties = cast(output.get("properties"));
        Map<String, Object> records = cast(properties.get("records"));
        Map<String, Object> items = cast(records.get("items"));
        Map<String, Object> itemProperties = cast(items.get("properties"));
        assertEquals("string", cast(itemProperties.get("decision")).get("type"));
        assertEquals("array", cast(itemProperties.get("trace")).get("type"));
        assertEquals(List.of("decision", "trace"), items.get("required"));
    }

    @Test
    void publishingIsBlockedUntilRealPreviewPasses() {
        operator(OperatorTypes.DATA);
        PolicyDraft draft = new PolicyDraft();
        draft.setId(8L);
        draft.setOperatorId(5L);
        draft.setOperatorType(OperatorTypes.DATA);
        draft.setCreatedByUserId("9");
        draft.setStatus("SHAPED");
        when(draftMapper.selectById(8L)).thenReturn(draft);

        assertThrows(BusinessException.class, () -> service.publish(5L, 8L));
    }

    private void operator(String type) {
        OperatorDefinition definition = new OperatorDefinition();
        definition.setId(5L);
        definition.setOperatorType(type);
        when(versionCatalog.requireOperator(5L)).thenReturn(definition);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
