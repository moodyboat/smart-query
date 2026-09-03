package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.OperatorDefinitionMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionCatalogServiceTest {
    private final OperatorDefinitionMapper operatorMapper = mock(OperatorDefinitionMapper.class);
    private final OperatorVersionMapper versionMapper = mock(OperatorVersionMapper.class);
    private final FlowDefinitionMapper flowMapper = mock(FlowDefinitionMapper.class);
    private final FlowVersionMapper flowVersionMapper = mock(FlowVersionMapper.class);
    private final DagValidationService dagValidationService = mock(DagValidationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContentHashService contentHashService = mock(ContentHashService.class);
    private final VersionCatalogService service = new VersionCatalogService(
        operatorMapper, versionMapper, flowMapper, flowVersionMapper,
        mock(RuleCompositionService.class), dagValidationService,
        mock(SchemaCompatibilityService.class), mock(SqlAstPolicyService.class),
        mock(AgentPolicyService.class), contentHashService,
        mock(RuntimeProfileService.class), objectMapper);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void flowVersionWithoutLeadOutputRemainsUnchanged() throws Exception {
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "author", UserRoles.USER));
        FlowDefinition flow = new FlowDefinition();
        flow.setId(4L);
        flow.setOwnerUserId("9");
        when(flowMapper.selectById(4L)).thenReturn(flow);

        OperatorDefinition rule = new OperatorDefinition();
        rule.setId(5L);
        rule.setOperatorType(OperatorTypes.RULE);
        rule.setDeleted(0);
        OperatorVersion ruleVersion = new OperatorVersion();
        ruleVersion.setId(12L);
        ruleVersion.setOperatorId(5L);
        ruleVersion.setStatus(VersionStatus.PUBLISHED);
        ruleVersion.setImplementationType("RULE_DSL");
        when(versionMapper.selectById(12L)).thenReturn(ruleVersion);
        when(operatorMapper.selectById(5L)).thenReturn(rule);
        when(dagValidationService.validate(any(), any())).thenReturn(
            new DagValidationService.DagValidationReport(true, List.of(), List.of(),
                List.of("rule_1"), List.of(List.of("rule_1"))));
        when(contentHashService.sha256(any())).thenReturn("flow-hash");
        when(flowVersionMapper.insert(any(FlowVersion.class))).thenAnswer(invocation -> {
            invocation.<FlowVersion>getArgument(0).setId(20L);
            return 1;
        });

        service.createFlowVersion(4L, Map.of(
            "nodes", List.of(Map.of(
                "id", "rule_1",
                "operatorVersionId", 12L,
                "config", Map.of())),
            "edges", List.of(),
            "parameterMappings", Map.of()));

        ArgumentCaptor<FlowVersion> saved = ArgumentCaptor.forClass(FlowVersion.class);
        verify(flowVersionMapper).insert(saved.capture());
        List<Map<String, Object>> persistedNodes = objectMapper.readValue(
            saved.getValue().getNodes(), new TypeReference<>() {});
        assertEquals(1, persistedNodes.size());
        assertEquals("rule_1", persistedNodes.get(0).get("id"));
        assertFalse(persistedNodes.stream().anyMatch(node -> Boolean.TRUE.equals(node.get("systemManaged"))));
    }
}
