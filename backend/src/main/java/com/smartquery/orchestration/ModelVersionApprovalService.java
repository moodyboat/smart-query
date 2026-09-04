package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.ModelVersionApproval;
import com.smartquery.entity.User;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.ModelVersionApprovalMapper;
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

/** Approval boundary for composed business-model versions (immutable DAG snapshots). */
@Service
@RequiredArgsConstructor
public class ModelVersionApprovalService {
    private static final Set<String> STATUSES = Set.of("SUBMITTED", "APPROVED", "REJECTED");

    private final ModelVersionApprovalMapper approvalMapper;
    private final FlowDefinitionMapper flowMapper;
    private final FlowVersionMapper versionMapper;
    private final UserMapper userMapper;
    private final VersionCatalogService versionCatalogService;
    private final RoleService roleService;
    private final ObjectMapper objectMapper;

    public List<ApprovalView> list(String requestedStatus) {
        String status = normalizedStatus(requestedStatus);
        LambdaQueryWrapper<ModelVersionApproval> query = new LambdaQueryWrapper<ModelVersionApproval>()
            .orderByDesc(ModelVersionApproval::getCreatedAt);
        if (status != null) query.eq(ModelVersionApproval::getStatus, status);
        if (!canReview()) query.eq(ModelVersionApproval::getRequestedByUserId, currentUserId());
        List<ApprovalView> result = new ArrayList<>();
        for (ModelVersionApproval approval : approvalMapper.selectList(query)) {
            FlowVersion version = versionMapper.selectById(approval.getFlowVersionId());
            FlowDefinition flow = flowMapper.selectById(approval.getFlowId());
            if (version == null || flow == null || Integer.valueOf(1).equals(flow.getDeleted())) continue;
            result.add(view(approval, flow, version));
        }
        return List.copyOf(result);
    }

    public boolean currentUserCanReview() { return canReview(); }

    public ApprovalDetail detail(Long approvalId) {
        ModelVersionApproval approval = approvalId == null ? null : approvalMapper.selectById(approvalId);
        if (approval == null) throw new BusinessException(404, "模型版本审批单不存在: " + approvalId);
        if (!canReview() && !currentUserId().equals(approval.getRequestedByUserId())) {
            throw new BusinessException(403, "无权查看该模型版本审批详情");
        }
        FlowVersion version = versionMapper.selectById(approval.getFlowVersionId());
        FlowDefinition flow = flowMapper.selectById(approval.getFlowId());
        if (version == null || flow == null) throw new BusinessException(404, "审批目标模型版本不存在");
        return new ApprovalDetail(view(approval, flow, version), parseList(version.getNodes()),
            parseList(version.getEdges()), parseMap(version.getParameterMappings()),
            parseMap(version.getValidationReport()));
    }

    @Transactional
    public ModelVersionApproval submit(Long flowId, Long versionId, String requestComment) {
        FlowDefinition flow = versionCatalogService.requireFlow(flowId);
        FlowVersion version = versionCatalogService.requireFlowVersion(versionId);
        if (!flowId.equals(version.getFlowId())) throw new BusinessException(404, "模型版本不属于当前模型");
        if (!currentUserId().equals(version.getCreatedByUserId()) && !isAdmin()) {
            throw new BusinessException(403, "只有模型版本创建人可以提交审批");
        }
        ModelVersionApproval existing = approvalMapper.selectOne(
            new LambdaQueryWrapper<ModelVersionApproval>()
                .eq(ModelVersionApproval::getFlowVersionId, versionId).last("LIMIT 1"));
        if (existing != null) {
            if ("REJECTED".equals(existing.getStatus())) {
                throw new BusinessException(409, "被驳回的模型版本不能原样重提，请修改编排后生成新版本");
            }
            return existing;
        }
        if (!List.of(VersionStatus.CANDIDATE, VersionStatus.VALIDATED).contains(version.getStatus())) {
            throw new BusinessException(409, "当前模型版本状态不能提交审批: " + version.getStatus());
        }
        Map<String, Object> validation = parseMap(version.getValidationReport());
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new BusinessException(422, "模型版本结构校验未通过，不能提交审批");
        }
        ModelVersionApproval approval = new ModelVersionApproval();
        approval.setFlowId(flowId);
        approval.setFlowVersionId(versionId);
        approval.setStatus("SUBMITTED");
        approval.setRequestComment(limit(requestComment, 2_000));
        approval.setRequestedByUserId(currentUserId());
        approvalMapper.insert(approval);
        version.setStatus(VersionStatus.PENDING_APPROVAL);
        versionMapper.updateById(version);
        return approval;
    }

    @Transactional
    public ApprovalView review(Long approvalId, Map<String, Object> body) {
        requireReviewer();
        ModelVersionApproval approval = approvalId == null ? null : approvalMapper.selectById(approvalId);
        if (approval == null) throw new BusinessException(404, "模型版本审批单不存在: " + approvalId);
        if (!"SUBMITTED".equals(approval.getStatus())) {
            throw new BusinessException(409, "审批单已经处理: " + approval.getStatus());
        }
        if (currentUserId().equals(approval.getRequestedByUserId()) && !isAdmin()) {
            throw new BusinessException(403, "模型版本创建人与审批人必须分离，不能自审");
        }
        FlowVersion version = versionMapper.selectById(approval.getFlowVersionId());
        FlowDefinition flow = flowMapper.selectById(approval.getFlowId());
        if (version == null || flow == null) throw new BusinessException(404, "审批目标模型版本不存在");
        if (!VersionStatus.PENDING_APPROVAL.equals(version.getStatus())) {
            throw new BusinessException(409, "目标模型版本不在待审批状态: " + version.getStatus());
        }
        String decision = required(body, "decision").toUpperCase(Locale.ROOT);
        if (!List.of("APPROVE", "REJECT").contains(decision)) {
            throw new BusinessException(422, "decision仅支持APPROVE或REJECT");
        }
        String comment = limit(text(body == null ? null : body.get("comment")), 4_000);
        if ("REJECT".equals(decision) && comment == null) {
            throw new BusinessException(422, "驳回时必须填写原因");
        }
        boolean approved = "APPROVE".equals(decision);
        LocalDateTime reviewedAt = LocalDateTime.now();
        ModelVersionApproval update = new ModelVersionApproval();
        update.setStatus(approved ? "APPROVED" : "REJECTED");
        update.setReviewerUserId(currentUserId());
        update.setReviewComment(comment);
        update.setReviewedAt(reviewedAt);
        int decided = approvalMapper.update(update, new LambdaUpdateWrapper<ModelVersionApproval>()
            .eq(ModelVersionApproval::getId, approvalId)
            .eq(ModelVersionApproval::getStatus, "SUBMITTED"));
        if (decided != 1) throw new BusinessException(409, "审批单已被其他审批人处理");
        approval.setStatus(update.getStatus());
        approval.setReviewerUserId(update.getReviewerUserId());
        approval.setReviewComment(comment);
        approval.setReviewedAt(reviewedAt);
        version.setStatus(approved ? VersionStatus.PUBLISHED : VersionStatus.REJECTED);
        versionMapper.updateById(version);
        return view(approval, flow, version);
    }

    private ApprovalView view(ModelVersionApproval approval, FlowDefinition flow, FlowVersion version) {
        Map<String, Object> validation = parseMap(version.getValidationReport());
        return new ApprovalView(approval, flow.getCode(), flow.getName(), flow.getDescription(),
            version.getVersionNo(), version.getStatus(), version.getContentHash(),
            parseList(version.getNodes()).size(), parseList(version.getEdges()).size(),
            Boolean.TRUE.equals(validation.get("valid")), userLabel(approval.getRequestedByUserId()),
            userLabel(approval.getReviewerUserId()), canReview()
                && (isAdmin() || !currentUserId().equals(approval.getRequestedByUserId()))
                && "SUBMITTED".equals(approval.getStatus()));
    }

    private List<Map<String, Object>> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private String userLabel(String id) {
        if (id == null) return null;
        try {
            User user = userMapper.selectById(Long.parseLong(id));
            if (user != null) return text(user.getDisplayName()) == null ? user.getUsername() : user.getDisplayName();
        } catch (NumberFormatException ignored) { }
        return id;
    }

    private String normalizedStatus(String raw) {
        String value = text(raw);
        if (value == null) return null;
        value = value.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) throw new BusinessException(422, "不支持的审批状态: " + value);
        return value;
    }

    private void requireReviewer() {
        if (!canReview()) throw new BusinessException(403, "需要模型版本审批权限");
    }

    private boolean canReview() { return roleService.currentUserHas(PermissionCodes.MODEL_REVIEW); }
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
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record ApprovalView(ModelVersionApproval approval, String modelCode, String modelName,
                               String modelDescription, Integer versionNo, String versionStatus,
                               String contentHash, int nodeCount, int edgeCount, boolean structureValid,
                               String requesterName, String reviewerName, boolean reviewable) { }

    public record ApprovalDetail(ApprovalView summary, List<Map<String, Object>> nodes,
                                 List<Map<String, Object>> edges, Map<String, Object> parameterMappings,
                                 Map<String, Object> validationReport) { }
}
