package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OperatorVersionApproval;
import com.smartquery.entity.OutputDraft;
import com.smartquery.entity.PolicyDraft;
import com.smartquery.entity.RuleDraft;
import com.smartquery.entity.User;
import com.smartquery.mapper.OperatorDefinitionMapper;
import com.smartquery.mapper.OperatorVersionApprovalMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import com.smartquery.mapper.OutputDraftMapper;
import com.smartquery.mapper.PolicyDraftMapper;
import com.smartquery.mapper.RuleDraftMapper;
import com.smartquery.mapper.UserMapper;
import com.smartquery.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Separation-of-duties gate between validated immutable versions and the DAG catalog. */
@Service
@RequiredArgsConstructor
public class OperatorApprovalService {
    private static final Set<String> STATUSES = Set.of("SUBMITTED", "APPROVED", "REJECTED");
    private static final Set<String> DRAFT_TYPES = Set.of("RULE", "OUTPUT", "POLICY");

    private final OperatorVersionApprovalMapper approvalMapper;
    private final OperatorDefinitionMapper operatorMapper;
    private final OperatorVersionMapper versionMapper;
    private final RuleDraftMapper ruleDraftMapper;
    private final OutputDraftMapper outputDraftMapper;
    private final PolicyDraftMapper policyDraftMapper;
    private final UserMapper userMapper;
    private final VersionCatalogService versionCatalogService;
    private final RoleService roleService;

    public List<ApprovalView> list(String requestedStatus) {
        String status = normalizedStatus(requestedStatus);
        LambdaQueryWrapper<OperatorVersionApproval> query =
            new LambdaQueryWrapper<OperatorVersionApproval>().orderByDesc(OperatorVersionApproval::getCreatedAt);
        if (status != null) query.eq(OperatorVersionApproval::getStatus, status);
        if (!canReview()) query.eq(OperatorVersionApproval::getRequestedByUserId, currentUserId());
        List<ApprovalView> result = new ArrayList<>();
        for (OperatorVersionApproval approval : approvalMapper.selectList(query)) {
            OperatorVersion version = versionMapper.selectById(approval.getOperatorVersionId());
            OperatorDefinition operator = operatorMapper.selectById(approval.getOperatorId());
            if (version == null || operator == null) continue;
            result.add(new ApprovalView(approval, operator.getCode(), operator.getName(),
                operator.getOperatorType(), version.getVersionNo(), version.getStatus(),
                version.getImplementationType(), version.getContentHash(),
                userLabel(approval.getRequestedByUserId()), userLabel(approval.getReviewerUserId()),
                canReview() && (isAdmin() || !currentUserId().equals(approval.getRequestedByUserId()))
                    && "SUBMITTED".equals(approval.getStatus())));
        }
        return List.copyOf(result);
    }

    public boolean currentUserCanReview() {
        return canReview();
    }

    public ApprovalDetail detail(Long approvalId) {
        OperatorVersionApproval approval = approvalId == null ? null : approvalMapper.selectById(approvalId);
        if (approval == null) throw new BusinessException(404, "版本审批单不存在: " + approvalId);
        if (!canReview() && !currentUserId().equals(approval.getRequestedByUserId())) {
            throw new BusinessException(403, "无权查看该版本审批详情");
        }
        OperatorVersion version = versionMapper.selectById(approval.getOperatorVersionId());
        OperatorDefinition operator = operatorMapper.selectById(approval.getOperatorId());
        if (version == null || operator == null) throw new BusinessException(404, "审批目标版本不存在");
        return new ApprovalDetail(view(approval, operator, version), version.getInputSchema(),
            version.getOutputSchema(), version.getParameterSchema(), version.getImplementationPayload(),
            version.getCapabilityRequirements(), version.getValidationReport());
    }

    @Transactional
    public OperatorVersionApproval submit(Long operatorId, Long versionId, String requestComment) {
        return submitFromDraft(operatorId, versionId, null, null, requestComment);
    }

    /** Called by governed authoring services after all automated validation gates pass. */
    @Transactional
    public OperatorVersionApproval submitFromDraft(Long operatorId, Long versionId,
                                                   String rawDraftType, Long draftId,
                                                   String requestComment) {
        OperatorDefinition operator = versionCatalogService.requireOperator(operatorId);
        OperatorVersion version = versionCatalogService.requireOperatorVersionVisible(versionId);
        if (!operatorId.equals(version.getOperatorId())) {
            throw new BusinessException(404, "算子版本不属于当前算子");
        }
        if (!currentUserId().equals(version.getCreatedByUserId()) && !isAdmin()) {
            throw new BusinessException(403, "只有版本创建人可以提交审批");
        }
        String draftType = normalizeDraftType(rawDraftType);
        validateDraftLink(operator, version, draftType, draftId);
        if (VersionStatus.PUBLISHED.equals(version.getStatus())) {
            markDrafts(versionId, VersionStatus.PUBLISHED);
            return latestApproval(versionId);
        }
        if (VersionStatus.REJECTED.equals(version.getStatus())) {
            throw new BusinessException(409, "被驳回的不可变版本不能原样重提，请修改草稿生成新版本");
        }
        if (VersionStatus.PENDING_APPROVAL.equals(version.getStatus())) {
            OperatorVersionApproval existing = pendingApproval(versionId);
            if (existing == null) throw new BusinessException(409, "版本处于待审批状态但审批单缺失");
            markDraftSubmitted(draftType, draftId, versionId);
            return existing;
        }
        if (!List.of(VersionStatus.CANDIDATE, VersionStatus.VALIDATED).contains(version.getStatus())) {
            throw new BusinessException(409, "当前版本状态不能提交审批: " + version.getStatus());
        }

        OperatorVersionApproval approval = new OperatorVersionApproval();
        approval.setOperatorId(operatorId);
        approval.setOperatorVersionId(versionId);
        approval.setDraftType(draftType);
        approval.setDraftId(draftId);
        approval.setStatus("SUBMITTED");
        approval.setRequestComment(limit(requestComment, 2_000));
        approval.setRequestedByUserId(currentUserId());
        approvalMapper.insert(approval);
        version.setStatus(VersionStatus.PENDING_APPROVAL);
        versionMapper.updateById(version);
        markDraftSubmitted(draftType, draftId, versionId);
        return approval;
    }

    @Transactional
    public ApprovalView review(Long approvalId, Map<String, Object> body) {
        requireReviewer();
        OperatorVersionApproval approval = approvalId == null ? null : approvalMapper.selectById(approvalId);
        if (approval == null) throw new BusinessException(404, "版本审批单不存在: " + approvalId);
        if (!"SUBMITTED".equals(approval.getStatus())) {
            throw new BusinessException(409, "审批单已经处理: " + approval.getStatus());
        }
        if (currentUserId().equals(approval.getRequestedByUserId()) && !isAdmin()) {
            throw new BusinessException(403, "版本创建人与审批人必须分离，不能自审");
        }
        OperatorVersion version = versionMapper.selectById(approval.getOperatorVersionId());
        OperatorDefinition operator = operatorMapper.selectById(approval.getOperatorId());
        if (version == null || operator == null) throw new BusinessException(404, "审批目标版本不存在");
        if (!VersionStatus.PENDING_APPROVAL.equals(version.getStatus())) {
            throw new BusinessException(409, "目标版本不在待审批状态: " + version.getStatus());
        }
        String decision = required(body, "decision").toUpperCase(Locale.ROOT);
        if (!List.of("APPROVE", "REJECT").contains(decision)) {
            throw new BusinessException(422, "decision仅支持APPROVE或REJECT");
        }
        String reviewComment = limit(text(body.get("comment")), 4_000);
        if ("REJECT".equals(decision) && reviewComment == null) {
            throw new BusinessException(422, "驳回时必须填写原因");
        }

        boolean approved = "APPROVE".equals(decision);
        String approvalStatus = approved ? "APPROVED" : "REJECTED";
        LocalDateTime reviewedAt = LocalDateTime.now();
        OperatorVersionApproval decisionUpdate = new OperatorVersionApproval();
        decisionUpdate.setStatus(approvalStatus);
        decisionUpdate.setReviewerUserId(currentUserId());
        decisionUpdate.setReviewComment(reviewComment);
        decisionUpdate.setReviewedAt(reviewedAt);
        int decided = approvalMapper.update(decisionUpdate,
            new LambdaUpdateWrapper<OperatorVersionApproval>()
                .eq(OperatorVersionApproval::getId, approvalId)
                .eq(OperatorVersionApproval::getStatus, "SUBMITTED"));
        if (decided != 1) throw new BusinessException(409, "审批单已被其他审批人处理");
        approval.setStatus(approvalStatus);
        approval.setReviewerUserId(currentUserId());
        approval.setReviewComment(reviewComment);
        approval.setReviewedAt(reviewedAt);
        version.setStatus(approved ? VersionStatus.PUBLISHED : VersionStatus.REJECTED);
        versionMapper.updateById(version);
        markDrafts(version.getId(), version.getStatus());
        return view(approval, operator, version);
    }

    private void validateDraftLink(OperatorDefinition operator, OperatorVersion version,
                                   String draftType, Long draftId) {
        if (draftType == null && draftId == null) return;
        if (draftType == null || draftId == null) throw new BusinessException(422, "draftType与draftId必须同时提供");
        switch (draftType) {
            case "RULE" -> {
                RuleDraft draft = ruleDraftMapper.selectById(draftId);
                if (draft == null || !version.getOperatorId().equals(draft.getOperatorId())
                        || !OperatorTypes.RULE.equals(operator.getOperatorType())) invalidDraftLink();
            }
            case "OUTPUT" -> {
                OutputDraft draft = outputDraftMapper.selectById(draftId);
                if (draft == null || !version.getOperatorId().equals(draft.getOperatorId())
                        || !OperatorTypes.OUTPUT.equals(operator.getOperatorType())) invalidDraftLink();
            }
            case "POLICY" -> {
                PolicyDraft draft = policyDraftMapper.selectById(draftId);
                if (draft == null || !version.getOperatorId().equals(draft.getOperatorId())
                        || !draft.getOperatorType().equals(operator.getOperatorType())) invalidDraftLink();
            }
            default -> invalidDraftLink();
        }
    }

    private void invalidDraftLink() {
        throw new BusinessException(422, "审批草稿与不可变版本不匹配");
    }

    private void markDraftSubmitted(String draftType, Long draftId, Long versionId) {
        if (draftType == null || draftId == null) return;
        switch (draftType) {
            case "RULE" -> {
                RuleDraft draft = ruleDraftMapper.selectById(draftId);
                draft.setCandidateVersionId(versionId);
                draft.setStatus("PENDING_APPROVAL");
                ruleDraftMapper.updateById(draft);
            }
            case "OUTPUT" -> {
                OutputDraft draft = outputDraftMapper.selectById(draftId);
                draft.setCandidateVersionId(versionId);
                draft.setStatus("PENDING_APPROVAL");
                outputDraftMapper.updateById(draft);
            }
            case "POLICY" -> {
                PolicyDraft draft = policyDraftMapper.selectById(draftId);
                draft.setCandidateVersionId(versionId);
                draft.setStatus("PENDING_APPROVAL");
                policyDraftMapper.updateById(draft);
            }
            default -> { }
        }
    }

    private void markDrafts(Long versionId, String versionStatus) {
        boolean published = VersionStatus.PUBLISHED.equals(versionStatus);
        String draftStatus = published ? "PUBLISHED" : "APPROVAL_REJECTED";
        for (RuleDraft draft : ruleDraftMapper.selectList(new LambdaQueryWrapper<RuleDraft>()
                .eq(RuleDraft::getCandidateVersionId, versionId))) {
            draft.setStatus(draftStatus);
            ruleDraftMapper.updateById(draft);
        }
        for (OutputDraft draft : outputDraftMapper.selectList(new LambdaQueryWrapper<OutputDraft>()
                .eq(OutputDraft::getCandidateVersionId, versionId))) {
            draft.setStatus(draftStatus);
            if (published) draft.setPublishedVersionId(versionId);
            outputDraftMapper.updateById(draft);
        }
        for (PolicyDraft draft : policyDraftMapper.selectList(new LambdaQueryWrapper<PolicyDraft>()
                .eq(PolicyDraft::getCandidateVersionId, versionId))) {
            draft.setStatus(draftStatus);
            if (published) draft.setPublishedVersionId(versionId);
            policyDraftMapper.updateById(draft);
        }
    }

    private ApprovalView view(OperatorVersionApproval approval, OperatorDefinition operator,
                              OperatorVersion version) {
        return new ApprovalView(approval, operator.getCode(), operator.getName(), operator.getOperatorType(),
            version.getVersionNo(), version.getStatus(), version.getImplementationType(), version.getContentHash(),
            userLabel(approval.getRequestedByUserId()), userLabel(approval.getReviewerUserId()), false);
    }

    private OperatorVersionApproval pendingApproval(Long versionId) {
        return approvalMapper.selectOne(new LambdaQueryWrapper<OperatorVersionApproval>()
            .eq(OperatorVersionApproval::getOperatorVersionId, versionId)
            .eq(OperatorVersionApproval::getStatus, "SUBMITTED")
            .orderByDesc(OperatorVersionApproval::getCreatedAt).last("LIMIT 1"));
    }

    private OperatorVersionApproval latestApproval(Long versionId) {
        return approvalMapper.selectOne(new LambdaQueryWrapper<OperatorVersionApproval>()
            .eq(OperatorVersionApproval::getOperatorVersionId, versionId)
            .orderByDesc(OperatorVersionApproval::getCreatedAt).last("LIMIT 1"));
    }

    private String normalizeDraftType(String raw) {
        String value = text(raw);
        if (value == null) return null;
        value = value.toUpperCase(Locale.ROOT);
        if (!DRAFT_TYPES.contains(value)) throw new BusinessException(422, "不支持的draftType: " + value);
        return value;
    }

    private String normalizedStatus(String raw) {
        String value = text(raw);
        if (value == null) return null;
        value = value.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) throw new BusinessException(422, "不支持的审批状态: " + value);
        return value;
    }

    private String userLabel(String id) {
        if (id == null) return null;
        try {
            User user = userMapper.selectById(Long.parseLong(id));
            if (user != null) return text(user.getDisplayName()) == null ? user.getUsername() : user.getDisplayName();
        } catch (NumberFormatException ignored) { }
        return id;
    }

    private void requireReviewer() {
        if (!canReview()) throw new BusinessException(403, "需要算子版本审批权限");
    }

    private boolean canReview() {
        return roleService.currentUserHas(PermissionCodes.OPERATOR_REVIEW);
    }

    private boolean isAdmin() { return roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL); }
    private String currentUserId() { return UserContextHolder.require().userId().toString(); }

    private String required(Map<String, Object> body, String field) {
        String value = text(body == null ? null : body.get(field));
        if (value == null) throw new BusinessException(422, field + "不能为空");
        return value;
    }

    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    public record ApprovalView(OperatorVersionApproval approval, String operatorCode,
                               String operatorName, String operatorType, Integer versionNo,
                               String versionStatus, String implementationType, String contentHash,
                               String requesterName, String reviewerName, boolean reviewable) { }

    public record ApprovalDetail(ApprovalView summary, String inputSchema, String outputSchema,
                                 String parameterSchema, String implementationPayload,
                                 String capabilityRequirements, String validationReport) { }
}
