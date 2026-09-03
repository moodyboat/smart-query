package com.smartquery.orchestration;

import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiningOperatorRegistrationServiceTest {
    private final VersionCatalogService catalog = mock(VersionCatalogService.class);
    private final OperatorApprovalService approval = mock(OperatorApprovalService.class);
    private final MiningOperatorRegistrationService service =
        new MiningOperatorRegistrationService(catalog, approval);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedModelCreatesGovernedMachineLearningOperatorVersion() {
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "author", UserRoles.USER));
        MiningModel model = new MiningModel();
        model.setId(42L);
        model.setName("逾期预测");
        model.setVersion(3);
        model.setArtifactSchemaVersion(2);
        model.setArtifactSha256("artifact-hash");

        OperatorDefinition operator = new OperatorDefinition();
        operator.setId(7L);
        operator.setCode("ml_model_42");
        operator.setOwnerUserId("9");
        OperatorVersion version = new OperatorVersion();
        version.setId(11L);
        version.setOperatorId(7L);
        when(catalog.listOperators()).thenReturn(List.of());
        when(catalog.createOperator(any())).thenReturn(operator);
        when(catalog.createOperatorVersion(eq(7L), any())).thenReturn(version);

        service.registerPublishedModel(model);

        ArgumentCaptor<Map<String, Object>> definitionRequest = ArgumentCaptor.forClass(Map.class);
        verify(catalog).createOperator(definitionRequest.capture());
        assertEquals("ml_model_42", definitionRequest.getValue().get("code"));
        assertEquals(OperatorTypes.ML, definitionRequest.getValue().get("operatorType"));

        ArgumentCaptor<Map<String, Object>> versionRequest = ArgumentCaptor.forClass(Map.class);
        verify(catalog).createOperatorVersion(eq(7L), versionRequest.capture());
        assertEquals("MINING_RUNTIME", versionRequest.getValue().get("implementationType"));
        Map<String, Object> payload = (Map<String, Object>) versionRequest.getValue().get("implementationPayload");
        assertEquals(42L, payload.get("modelId"));
        assertEquals("artifact-hash", payload.get("artifactSha256"));
        verify(approval).submit(7L, 11L, "模型发布完成，提交机器学习算子版本审批");
    }
}
