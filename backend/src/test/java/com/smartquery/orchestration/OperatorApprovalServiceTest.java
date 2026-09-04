package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.support.TestRoles;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OperatorVersionApproval;
import com.smartquery.entity.OutputDraft;
import com.smartquery.mapper.OperatorDefinitionMapper;
import com.smartquery.mapper.OperatorVersionApprovalMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import com.smartquery.mapper.OutputDraftMapper;
import com.smartquery.mapper.PolicyDraftMapper;
import com.smartquery.mapper.RuleDraftMapper;
import com.smartquery.mapper.UserMapper;
import com.smartquery.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatorApprovalServiceTest {
    private final OperatorVersionApprovalMapper approvalMapper = mock(OperatorVersionApprovalMapper.class);
    private final OperatorDefinitionMapper operatorMapper = mock(OperatorDefinitionMapper.class);
    private final OperatorVersionMapper versionMapper = mock(OperatorVersionMapper.class);
    private final RuleDraftMapper ruleDraftMapper = mock(RuleDraftMapper.class);
    private final OutputDraftMapper outputDraftMapper = mock(OutputDraftMapper.class);
    private final PolicyDraftMapper policyDraftMapper = mock(PolicyDraftMapper.class);
    private final VersionCatalogService versionCatalog = mock(VersionCatalogService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final OperatorApprovalService service = new OperatorApprovalService(approvalMapper, operatorMapper,
        versionMapper, ruleDraftMapper, outputDraftMapper, policyDraftMapper, mock(UserMapper.class), versionCatalog,
        roleService);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void validatedDraftSubmissionMovesVersionToPendingApproval() {
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "author", TestRoles.USER));
        OperatorDefinition operator = operator();
        OperatorVersion version = version(VersionStatus.CANDIDATE);
        OutputDraft draft = new OutputDraft();
        draft.setId(8L);
        draft.setOperatorId(5L);
        when(versionCatalog.requireOperator(5L)).thenReturn(operator);
        when(versionCatalog.requireOperatorVersionVisible(12L)).thenReturn(version);
        when(outputDraftMapper.selectById(8L)).thenReturn(draft);

        service.submitFromDraft(5L, 12L, "OUTPUT", 8L, "已通过预览");

        assertEquals(VersionStatus.PENDING_APPROVAL, version.getStatus());
        assertEquals("PENDING_APPROVAL", draft.getStatus());
        assertEquals(12L, draft.getCandidateVersionId());
        verify(approvalMapper).insert(any(OperatorVersionApproval.class));
        verify(versionMapper).updateById(version);
        verify(outputDraftMapper).updateById(draft);
    }

    @Test
    void independentReviewerCanApprovePendingVersion() {
        UserContextHolder.set(new UserContextHolder.UserContext(10L, "reviewer", TestRoles.OPERATOR_REVIEWER));
        when(roleService.currentUserHas(PermissionCodes.OPERATOR_REVIEW)).thenReturn(true);
        OperatorVersionApproval approval = approval();
        OperatorVersion version = version(VersionStatus.PENDING_APPROVAL);
        OperatorDefinition operator = operator();
        when(approvalMapper.selectById(20L)).thenReturn(approval);
        when(approvalMapper.update(any(OperatorVersionApproval.class), any())).thenReturn(1);
        when(versionMapper.selectById(12L)).thenReturn(version);
        when(operatorMapper.selectById(5L)).thenReturn(operator);

        OperatorApprovalService.ApprovalView result = service.review(20L,
            Map.of("decision", "APPROVE", "comment", "验证通过"));

        assertEquals("APPROVED", result.approval().getStatus());
        assertEquals(VersionStatus.PUBLISHED, version.getStatus());
        assertEquals("10", approval.getReviewerUserId());
        verify(approvalMapper).update(any(OperatorVersionApproval.class), any());
        verify(versionMapper).updateById(version);
    }

    @Test
    void systemAdministratorCanSelfApproveWithAuditTrail() {
        UserContextHolder.set(new UserContextHolder.UserContext(9L, "author", TestRoles.ADMIN));
        when(roleService.currentUserHas(PermissionCodes.OPERATOR_REVIEW)).thenReturn(true);
        when(roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL)).thenReturn(true);
        OperatorVersionApproval approval = approval();
        OperatorVersion version = version(VersionStatus.PENDING_APPROVAL);
        when(approvalMapper.selectById(20L)).thenReturn(approval);
        when(approvalMapper.update(any(OperatorVersionApproval.class), any())).thenReturn(1);
        when(versionMapper.selectById(12L)).thenReturn(version);
        when(operatorMapper.selectById(5L)).thenReturn(operator());

        OperatorApprovalService.ApprovalView result = service.review(20L,
            Map.of("decision", "APPROVE", "comment", "管理员应急审批"));

        assertEquals("APPROVED", result.approval().getStatus());
        assertEquals("9", result.approval().getReviewerUserId());
        assertEquals(VersionStatus.PUBLISHED, version.getStatus());
    }

    @Test
    void ordinaryUserCannotReadAnotherAuthorsVersionPayload() {
        UserContextHolder.set(new UserContextHolder.UserContext(11L, "other", TestRoles.USER));
        when(approvalMapper.selectById(20L)).thenReturn(approval());

        assertThrows(BusinessException.class, () -> service.detail(20L));
    }

    private OperatorDefinition operator() {
        OperatorDefinition operator = new OperatorDefinition();
        operator.setId(5L);
        operator.setCode("risk_output");
        operator.setName("风险输出");
        operator.setOperatorType(OperatorTypes.OUTPUT);
        return operator;
    }

    private OperatorVersion version(String status) {
        OperatorVersion version = new OperatorVersion();
        version.setId(12L);
        version.setOperatorId(5L);
        version.setVersionNo(3);
        version.setStatus(status);
        version.setCreatedByUserId("9");
        version.setImplementationType("OUTPUT_RENDERER");
        version.setContentHash("abc");
        return version;
    }

    private OperatorVersionApproval approval() {
        OperatorVersionApproval approval = new OperatorVersionApproval();
        approval.setId(20L);
        approval.setOperatorId(5L);
        approval.setOperatorVersionId(12L);
        approval.setStatus("SUBMITTED");
        approval.setRequestedByUserId("9");
        return approval;
    }
}
