package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OutputDraft;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.OutputDraftMapper;
import com.smartquery.orchestration.execution.OutputOperatorExecutor;
import com.smartquery.service.ResourceAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutputAuthoringServiceTest {
    private final OutputDraftMapper draftMapper = mock(OutputDraftMapper.class);
    private final VersionCatalogService versionCatalog = mock(VersionCatalogService.class);
    private final OperatorApprovalService approvalService = mock(OperatorApprovalService.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final com.smartquery.llm.LlmService llmService = mock(com.smartquery.llm.LlmService.class);
    private OutputAuthoringService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        LeadOutputPolicyService leadPolicy = new LeadOutputPolicyService(objectMapper);
        ContentHashService hashes = new ContentHashService(objectMapper);
        OutputCapabilityRegistryService capabilityRegistry = mock(OutputCapabilityRegistryService.class);
        service = new OutputAuthoringService(
            draftMapper,
            mock(ChatMessageMapper.class),
            mock(ResourceAccessService.class),
            versionCatalog,
            hashes,
            new OutputSpecSandbox(hashes, leadPolicy, capabilityRegistry),
            capabilityRegistry,
            new OutputOperatorExecutor(leadPolicy, capabilityRegistry),
            runtimeProfileService,
            mock(DependencyCenterService.class),
            approvalService,
            llmService,
            objectMapper);
        OperatorDefinition definition = new OperatorDefinition();
        definition.setId(5L);
        definition.setOperatorType(OperatorTypes.OUTPUT);
        when(versionCatalog.requireOperator(5L)).thenReturn(definition);
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "tester", "USER"));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void publishingRequiresSuccessfulPreview() {
        OutputDraft draft = draft("SHAPED");
        when(draftMapper.selectById(8L)).thenReturn(draft);

        assertThrows(BusinessException.class, () -> service.publish(5L, 8L));
    }

    @Test
    void validatedPreviewCreatesImmutableVersionAndSubmitsApproval() {
        OutputDraft draft = draft("PREVIEW_VALIDATED");
        when(draftMapper.selectById(8L)).thenReturn(draft);
        OperatorVersion candidate = new OperatorVersion();
        candidate.setId(12L);
        candidate.setStatus(VersionStatus.CANDIDATE);
        when(versionCatalog.createOperatorVersion(eq(5L), any())).thenReturn(candidate);

        OperatorVersion result = service.publish(5L, 8L);

        assertEquals(VersionStatus.PENDING_APPROVAL, result.getStatus());
        assertEquals("PENDING_APPROVAL", draft.getStatus());
        assertEquals(12L, draft.getCandidateVersionId());
        verify(approvalService).submitFromDraft(5L, 12L, "OUTPUT", 8L,
            "输出草稿已通过声明式整形和可视化预览");
        verify(draftMapper).updateById(draft);
    }

    @Test
    void generatedBuiltinOutputIgnoresLlmDependencyClaims() throws Exception {
        when(llmService.chat(any(), any())).thenReturn("""
            {"outputKind":"EXCEL","contentSpec":{"columns":[{"field":"risk","title":"风险"}]},
             "dependencies":[{"type":"FRONTEND_RENDERER","name":"excel-grid-renderer","version":"1.0.0"}]}
            """);

        service.generate(5L, java.util.Map.of("instruction", "输出风险结果表"));

        ArgumentCaptor<OutputDraft> captor = ArgumentCaptor.forClass(OutputDraft.class);
        verify(draftMapper).insert(captor.capture());
        java.util.Map<String, Object> raw = new ObjectMapper().readValue(
            captor.getValue().getRawSpec(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertEquals(java.util.List.of(), raw.get("dependencies"));
    }

    private OutputDraft draft(String status) {
        OutputDraft draft = new OutputDraft();
        draft.setId(8L);
        draft.setOperatorId(5L);
        draft.setCreatedByUserId("9");
        draft.setStatus(status);
        draft.setShapedSpec("{\"outputKind\":\"TABLE\",\"contentSpec\":{\"columns\":[{\"field\":\"orderId\",\"title\":\"订单\"}]}}");
        draft.setInputSchema("{}");
        draft.setOutputSchema("{}");
        draft.setParameterSchema("{}");
        draft.setShapingReport("{\"valid\":true}");
        draft.setPreviewReport("{\"valid\":true}");
        return draft;
    }
}
