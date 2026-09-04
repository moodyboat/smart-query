package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.PermissionCodes;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.User;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OperatorDefinitionMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.UserMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.service.ResourceAccessService;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageHotDataViewServiceTest {
    private final OrchestrationRunMapper runs = mock(OrchestrationRunMapper.class);
    private final NodeRunMapper nodeRuns = mock(NodeRunMapper.class);
    private final FlowVersionMapper flowVersions = mock(FlowVersionMapper.class);
    private final FlowDefinitionMapper flows = mock(FlowDefinitionMapper.class);
    private final OperatorVersionMapper operatorVersions = mock(OperatorVersionMapper.class);
    private final OperatorDefinitionMapper operators = mock(OperatorDefinitionMapper.class);
    private final MiningModelMapper models = mock(MiningModelMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ScheduleTaskMapper scheduleTasks = mock(ScheduleTaskMapper.class);
    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final StorageHotDataViewService service = new StorageHotDataViewService(runs, nodeRuns,
        flowVersions, flows, operatorVersions, operators, models, users, scheduleTasks, new ObjectMapper(), access);

    @Test
    void enrichesOutputWithOwnerFlowActualExecutionPathAndModelSnapshot() {
        fixture();
        when(access.currentUserId()).thenReturn("99");
        when(access.isAdmin()).thenReturn(true);
        when(access.hasPermission(PermissionCodes.RUNTIME_MANAGE)).thenReturn(true);

        StorageHotDataViewService.HotOutputView view = service.views(List.of(artifact())).get(0);

        assertEquals("分析图表 · chart-view", view.title());
        assertEquals("客户风险识别", view.flowName());
        assertEquals(5L, view.recordCount());
        assertEquals("系统管理员", view.ownerDisplayName());
        assertEquals("RESOURCE_ACCESS_ALL", view.permissionBasis());
        assertTrue(view.canViewDetails());
        assertTrue(view.canArchive());
        assertEquals(2, view.executionPath().size());
        assertEquals(1, view.executionEdges().size());
        assertEquals("risk-model", view.executionEdges().get(0).source());
        assertEquals("ML", view.executionPath().get(0).operatorType());
        assertEquals("客户风险模型", view.models().get(0).name());
        assertEquals("logistic_regression", view.models().get(0).algorithm());
        assertEquals(3, view.models().get(0).algorithmVersion());
    }

    @Test
    void runtimeManagerWithoutGlobalReadGetsMetadataButNotBusinessRows() {
        fixture();
        when(access.currentUserId()).thenReturn("9");
        when(access.isAdmin()).thenReturn(false);
        when(access.hasPermission(PermissionCodes.RUNTIME_MANAGE)).thenReturn(true);

        StorageHotDataViewService.HotOutputView view = service.views(List.of(artifact())).get(0);

        assertEquals("METADATA_ONLY", view.permissionBasis());
        assertFalse(view.canViewDetails());
        assertTrue(view.canArchive());
    }

    @SuppressWarnings("unchecked")
    private void fixture() {
        OrchestrationRun run = new OrchestrationRun();
        run.setId(2L); run.setFlowVersionId(3L); run.setStatus("SUCCESS");
        when(runs.selectBatchIds(any(Collection.class))).thenReturn(List.of(run));

        NodeRun mlNode = new NodeRun();
        mlNode.setId(19L); mlNode.setRunId(2L); mlNode.setNodeId("risk-model");
        mlNode.setOperatorVersionId(5L); mlNode.setStatus("SUCCESS"); mlNode.setExecutionTimeMs(820L);
        NodeRun outputNode = new NodeRun();
        outputNode.setId(20L); outputNode.setRunId(2L); outputNode.setNodeId("result-exit");
        outputNode.setOperatorVersionId(9L); outputNode.setStatus("SUCCESS"); outputNode.setExecutionTimeMs(12L);
        when(nodeRuns.selectList(any())).thenReturn(List.of(mlNode, outputNode));

        FlowVersion flowVersion = new FlowVersion();
        flowVersion.setId(3L); flowVersion.setFlowId(2L); flowVersion.setVersionNo(1);
        flowVersion.setEdges("[{\"source\":\"risk-model\",\"target\":\"result-exit\",\"mappingMode\":\"MERGE\"}]");
        when(flowVersions.selectBatchIds(any(Collection.class))).thenReturn(List.of(flowVersion));
        FlowDefinition flow = new FlowDefinition();
        flow.setId(2L); flow.setCode("customer-risk"); flow.setName("客户风险识别");
        when(flows.selectBatchIds(any(Collection.class))).thenReturn(List.of(flow));

        OperatorVersion mlVersion = new OperatorVersion();
        mlVersion.setId(5L); mlVersion.setOperatorId(4L); mlVersion.setVersionNo(2);
        mlVersion.setStatus("PUBLISHED"); mlVersion.setImplementationType("MINING_RUNTIME");
        mlVersion.setImplementationPayload("{\"modelId\":6}");
        OperatorVersion outputVersion = new OperatorVersion();
        outputVersion.setId(9L); outputVersion.setOperatorId(10L); outputVersion.setVersionNo(1);
        outputVersion.setStatus("PUBLISHED"); outputVersion.setImplementationType("OUTPUT_RENDERER");
        outputVersion.setImplementationPayload("{}");
        when(operatorVersions.selectBatchIds(any(Collection.class))).thenReturn(List.of(mlVersion, outputVersion));

        OperatorDefinition ml = new OperatorDefinition();
        ml.setId(4L); ml.setCode("risk-model"); ml.setName("风险预测"); ml.setOperatorType("ML");
        OperatorDefinition output = new OperatorDefinition();
        output.setId(10L); output.setCode("result-exit"); output.setName("结果出口"); output.setOperatorType("OUTPUT");
        when(operators.selectBatchIds(any(Collection.class))).thenReturn(List.of(ml, output));

        MiningModel model = new MiningModel();
        model.setId(6L); model.setName("客户风险模型"); model.setAlgorithm("logistic_regression");
        model.setAlgorithmVersion(3); model.setStatus("published"); model.setArtifactSha256("sha256:model");
        when(models.selectBatchIds(any(Collection.class))).thenReturn(List.of(model));
        User user = new User();
        user.setId(1L); user.setUsername("admin"); user.setDisplayName("系统管理员"); user.setRole("admin");
        when(users.selectBatchIds(any(Collection.class))).thenReturn(List.of(user));
    }

    private OutputArtifact artifact() {
        OutputArtifact artifact = new OutputArtifact();
        artifact.setId(7L); artifact.setRunId(2L); artifact.setNodeRunId(20L);
        artifact.setOwnerUserId("1"); artifact.setOutputKind("CHART"); artifact.setStatus("READY");
        artifact.setArchiveStatus("ACTIVE"); artifact.setPayloadBytes(4096L);
        artifact.setContentSpec("{}");
        artifact.setArtifactData("{\"targetId\":\"chart-view\",\"capabilityCode\":\"view.echarts\",\"recordCount\":5}");
        return artifact;
    }
}
