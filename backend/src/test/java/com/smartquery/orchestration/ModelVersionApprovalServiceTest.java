package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.ModelVersionApproval;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.ModelVersionApprovalMapper;
import com.smartquery.mapper.UserMapper;
import com.smartquery.service.RoleService;
import com.smartquery.support.TestRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelVersionApprovalServiceTest {
    private final ModelVersionApprovalMapper approvalMapper = mock(ModelVersionApprovalMapper.class);
    private final FlowDefinitionMapper flowMapper = mock(FlowDefinitionMapper.class);
    private final FlowVersionMapper versionMapper = mock(FlowVersionMapper.class);
    private final VersionCatalogService versionCatalog = mock(VersionCatalogService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final ModelVersionApprovalService service = new ModelVersionApprovalService(
        approvalMapper, flowMapper, versionMapper, mock(UserMapper.class), versionCatalog,
        roleService, new ObjectMapper());

    @AfterEach
    void tearDown() { UserContextHolder.clear(); }

    @Test
    void validatedComposedModelCanBeSubmitted() {
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "author", TestRoles.USER));
        FlowDefinition flow = flow();
        FlowVersion version = version(VersionStatus.CANDIDATE);
        when(versionCatalog.requireFlow(5L)).thenReturn(flow);
        when(versionCatalog.requireFlowVersion(12L)).thenReturn(version);

        ModelVersionApproval result = service.submit(5L, 12L, "结构校验通过");

        assertEquals("SUBMITTED", result.getStatus());
        assertEquals(VersionStatus.PENDING_APPROVAL, version.getStatus());
        verify(approvalMapper).insert(any(ModelVersionApproval.class));
        verify(versionMapper).updateById(version);
    }

    @Test
    void independentModelReviewerCanApprove() {
        UserContextHolder.set(new UserContextHolder.UserContext(10L, "reviewer", TestRoles.MODEL_REVIEWER));
        when(roleService.currentUserHas(PermissionCodes.MODEL_REVIEW)).thenReturn(true);
        ModelVersionApproval approval = approval();
        FlowVersion version = version(VersionStatus.PENDING_APPROVAL);
        FlowDefinition flow = flow();
        when(approvalMapper.selectById(20L)).thenReturn(approval);
        when(approvalMapper.update(any(ModelVersionApproval.class), any())).thenReturn(1);
        when(versionMapper.selectById(12L)).thenReturn(version);
        when(flowMapper.selectById(5L)).thenReturn(flow);

        ModelVersionApprovalService.ApprovalView result = service.review(20L,
            Map.of("decision", "APPROVE", "comment", "结构与责任边界清晰"));

        assertEquals("APPROVED", result.approval().getStatus());
        assertEquals(VersionStatus.PUBLISHED, version.getStatus());
        verify(versionMapper).updateById(version);
    }

    @Test
    void systemAdministratorCanSelfApproveWithAuditTrail() {
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "admin", TestRoles.ADMIN));
        when(roleService.currentUserHas(PermissionCodes.MODEL_REVIEW)).thenReturn(true);
        when(roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL)).thenReturn(true);
        ModelVersionApproval approval = approval();
        FlowVersion version = version(VersionStatus.PENDING_APPROVAL);
        when(approvalMapper.selectById(20L)).thenReturn(approval);
        when(approvalMapper.update(any(ModelVersionApproval.class), any())).thenReturn(1);
        when(versionMapper.selectById(12L)).thenReturn(version);
        when(flowMapper.selectById(5L)).thenReturn(flow());

        ModelVersionApprovalService.ApprovalView result = service.review(20L,
            Map.of("decision", "APPROVE", "comment", "管理员应急审批"));

        assertEquals("APPROVED", result.approval().getStatus());
        assertEquals("9", result.approval().getReviewerUserId());
        assertEquals(VersionStatus.PUBLISHED, version.getStatus());
    }

    private FlowDefinition flow() {
        FlowDefinition flow = new FlowDefinition();
        flow.setId(5L);
        flow.setCode("risk_model");
        flow.setName("风险识别模型");
        flow.setOwnerUserId("9");
        flow.setDeleted(0);
        return flow;
    }

    private FlowVersion version(String status) {
        FlowVersion version = new FlowVersion();
        version.setId(12L);
        version.setFlowId(5L);
        version.setVersionNo(3);
        version.setStatus(status);
        version.setCreatedByUserId("9");
        version.setContentHash("abc");
        version.setNodes("[{\"id\":\"input\",\"operatorVersionId\":1}]");
        version.setEdges("[]");
        version.setValidationReport("{\"valid\":true}");
        return version;
    }

    private ModelVersionApproval approval() {
        ModelVersionApproval approval = new ModelVersionApproval();
        approval.setId(20L);
        approval.setFlowId(5L);
        approval.setFlowVersionId(12L);
        approval.setStatus("SUBMITTED");
        approval.setRequestedByUserId("9");
        return approval;
    }
}
