package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.DraftDependency;
import com.smartquery.entity.OutputDraft;
import com.smartquery.entity.PolicyDraft;
import com.smartquery.entity.RuleDraft;
import com.smartquery.mapper.DraftDependencyMapper;
import com.smartquery.mapper.OutputDraftMapper;
import com.smartquery.mapper.PolicyDraftMapper;
import com.smartquery.mapper.RuleDraftMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Re-runs only the safe shaping/sandbox stage after a new runtime becomes available. */
@Service
@RequiredArgsConstructor
public class DraftRevalidationService {
    private final DraftDependencyMapper linkMapper;
    private final RuleDraftMapper ruleDraftMapper;
    private final OutputDraftMapper outputDraftMapper;
    private final PolicyDraftMapper policyDraftMapper;
    private final RuleAuthoringService ruleAuthoringService;
    private final OutputAuthoringService outputAuthoringService;
    private final PolicyAuthoringService policyAuthoringService;

    public Map<String, Object> revalidate(List<Long> requestIds, Long runtimeProfileId) {
        List<DraftDependency> links = linkMapper.selectList(new LambdaQueryWrapper<DraftDependency>()
            .in(DraftDependency::getRequestId, requestIds));
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> handled = new LinkedHashSet<>();
        for (DraftDependency link : links) {
            String key = link.getDraftType() + ":" + link.getDraftId();
            if (!handled.add(key)) continue;
            Map<String, Object> result = revalidateOne(link, runtimeProfileId);
            items.add(result);
            String outcome = String.valueOf(result.get("outcome"));
            links.stream().filter(item -> key.equals(item.getDraftType() + ":" + item.getDraftId()))
                .forEach(item -> {
                    item.setStatus("REVALIDATED".equals(outcome) ? "REVALIDATED" : "RUNTIME_READY");
                    linkMapper.updateById(item);
                });
        }
        long successful = items.stream().filter(item -> "REVALIDATED".equals(item.get("outcome"))).count();
        return Map.of("runtimeProfileId", runtimeProfileId, "draftCount", items.size(),
            "successful", successful, "items", items);
    }

    private Map<String, Object> revalidateOne(DraftDependency link, Long profileId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftType", link.getDraftType());
        result.put("draftId", link.getDraftId());
        try {
            DraftOwner owner = owner(link);
            if (!"DEPENDENCY_MISSING".equals(owner.status())) {
                result.put("outcome", "SKIPPED");
                result.put("status", owner.status());
                result.put("message", "草稿状态已变化，不自动覆盖");
                return result;
            }
            long ownerId = Long.parseLong(owner.userId());
            try (UserContextHolder.Scope ignored = UserContextHolder.open(
                    new UserContextHolder.UserContext(ownerId, "runtime-revalidator", UserRoles.USER))) {
                String status;
                if ("RULE".equals(link.getDraftType())) {
                    status = ruleAuthoringService.validateDraft(owner.operatorId(), link.getDraftId(), profileId)
                        .getStatus();
                } else if ("OUTPUT".equals(link.getDraftType())) {
                    status = outputAuthoringService.shape(owner.operatorId(), link.getDraftId(), profileId)
                        .getStatus();
                } else {
                    status = policyAuthoringService.shape(owner.operatorId(), link.getDraftId(), profileId)
                        .getStatus();
                }
                result.put("status", status);
                result.put("outcome", revalidated(link.getDraftType(), status)
                    ? "REVALIDATED" : "STILL_BLOCKED");
            }
        } catch (Exception error) {
            result.put("outcome", "FAILED");
            result.put("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        return result;
    }

    private DraftOwner owner(DraftDependency link) {
        if ("RULE".equals(link.getDraftType())) {
            RuleDraft draft = ruleDraftMapper.selectById(link.getDraftId());
            if (draft == null) throw new IllegalStateException("规则草稿不存在");
            return new DraftOwner(draft.getOperatorId(), draft.getCreatedByUserId(), draft.getStatus());
        }
        if ("OUTPUT".equals(link.getDraftType())) {
            OutputDraft draft = outputDraftMapper.selectById(link.getDraftId());
            if (draft == null) throw new IllegalStateException("输出草稿不存在");
            return new DraftOwner(draft.getOperatorId(), draft.getCreatedByUserId(), draft.getStatus());
        }
        if ("POLICY".equals(link.getDraftType())) {
            PolicyDraft draft = policyDraftMapper.selectById(link.getDraftId());
            if (draft == null) throw new IllegalStateException("策略草稿不存在");
            return new DraftOwner(draft.getOperatorId(), draft.getCreatedByUserId(), draft.getStatus());
        }
        throw new IllegalStateException("未知草稿类型: " + link.getDraftType());
    }

    private boolean revalidated(String type, String status) {
        return "RULE".equals(type) ? "VALIDATED".equals(status) : "SHAPED".equals(status);
    }

    private record DraftOwner(Long operatorId, String userId, String status) {}
}
