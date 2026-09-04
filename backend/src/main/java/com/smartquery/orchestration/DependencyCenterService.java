package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.entity.DependencyRequest;
import com.smartquery.entity.DraftDependency;
import com.smartquery.entity.OperatorVersionRuntimeBinding;
import com.smartquery.entity.OutputDraft;
import com.smartquery.entity.PolicyDraft;
import com.smartquery.entity.RuleDraft;
import com.smartquery.entity.RuntimeDependency;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.mapper.DependencyRequestMapper;
import com.smartquery.mapper.DraftDependencyMapper;
import com.smartquery.mapper.OperatorVersionRuntimeBindingMapper;
import com.smartquery.mapper.OutputDraftMapper;
import com.smartquery.mapper.PolicyDraftMapper;
import com.smartquery.mapper.RuleDraftMapper;
import com.smartquery.mapper.RuntimeDependencyMapper;
import com.smartquery.mapper.RuntimeProfileMapper;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** User request and administrator attestation boundary for immutable runtime dependencies. */
@Service
@RequiredArgsConstructor
public class DependencyCenterService {
    private static final Pattern PROFILE_CODE = Pattern.compile("^[a-z][a-z0-9_-]{2,159}$");
    private static final Pattern SHA256 = Pattern.compile("^sha256:[a-fA-F0-9]{64}$");

    private final DependencyRequestMapper requestMapper;
    private final RuntimeProfileMapper profileMapper;
    private final RuntimeDependencyMapper runtimeDependencyMapper;
    private final OperatorVersionRuntimeBindingMapper bindingMapper;
    private final DraftDependencyMapper draftDependencyMapper;
    private final RuleDraftMapper ruleDraftMapper;
    private final OutputDraftMapper outputDraftMapper;
    private final PolicyDraftMapper policyDraftMapper;
    private final ResourceAccessService resourceAccessService;
    private final RuntimeBuildJobService runtimeBuildJobService;
    private final ObjectMapper objectMapper;

    public List<DependencyRequest> listRequests(String status) {
        LambdaQueryWrapper<DependencyRequest> query = new LambdaQueryWrapper<DependencyRequest>()
            .orderByDesc(DependencyRequest::getCreatedAt);
        if (!canManageRuntime()) query.eq(DependencyRequest::getOwnerUserId, currentUserId());
        if (status != null && !status.isBlank()) query.eq(DependencyRequest::getStatus, status);
        return requestMapper.selectList(query);
    }

    @Transactional
    public DependencyRequest submit(Map<String, Object> body) {
        String dependencyType = required(body, "dependencyType").toUpperCase(Locale.ROOT);
        String runtimeType = RuntimeTypes.DEPENDENCY_RUNTIME.get(dependencyType);
        if (runtimeType == null) throw new BusinessException(422, "不支持的依赖类型: " + dependencyType);
        String name = coordinate(required(body, "name"), "name");
        String requestedVersion = coordinate(required(body, "requestedVersion"), "requestedVersion");
        DependencyRequest existing = requestMapper.selectOne(new LambdaQueryWrapper<DependencyRequest>()
            .eq(DependencyRequest::getOwnerUserId, currentUserId())
            .eq(DependencyRequest::getDependencyType, dependencyType)
            .eq(DependencyRequest::getDependencyName, name)
            .eq(DependencyRequest::getRequestedVersion, requestedVersion)
            .notIn(DependencyRequest::getStatus, List.of("REJECTED", "DEPRECATED"))
            .orderByDesc(DependencyRequest::getCreatedAt)
            .last("LIMIT 1"));
        if (existing != null) {
            linkDraft(body, existing);
            return existing;
        }

        DependencyRequest request = new DependencyRequest();
        request.setRequestNo(nextRequestNo());
        request.setDependencyType(dependencyType);
        request.setRuntimeType(runtimeType);
        request.setDependencyName(name);
        request.setRequestedVersion(requestedVersion);
        request.setReason(text(body.get("reason")));
        request.setStatus("SUBMITTED");
        request.setOwnerUserId(currentUserId());
        request.setSourceVerified(0);
        request.setVulnerabilityCritical(0);
        request.setVulnerabilityHigh(0);
        requestMapper.insert(request);
        linkDraft(body, request);
        return request;
    }

    @Transactional
    public DependencyRequest review(Long requestId, Map<String, Object> body) {
        requireRuntimeManager();
        DependencyRequest request = requireRequest(requestId);
        if (!List.of("SUBMITTED", "UNDER_REVIEW").contains(request.getStatus())) {
            throw new BusinessException(409, "当前依赖申请不能审批: " + request.getStatus());
        }
        String decision = required(body, "decision").toUpperCase(Locale.ROOT);
        request.setReviewComment(text(body.get("comment")));
        request.setReviewedByUserId(currentUserId());
        request.setReviewedAt(LocalDateTime.now());
        if ("REJECT".equals(decision)) {
            request.setStatus("REJECTED");
            requestMapper.updateById(request);
            updateDraftLinks(request.getId(), "REJECTED");
            return request;
        }
        if (!"APPROVE".equals(decision)) throw new BusinessException(422, "decision仅支持APPROVE或REJECT");

        request.setResolvedVersion(exactVersion(required(body, "resolvedVersion")));
        request.setSourceUri(required(body, "sourceUri"));
        String checksum = required(body, "checksumSha256").toLowerCase(Locale.ROOT);
        if (!checksum.matches("^[a-f0-9]{64}$")) throw new BusinessException(422, "checksumSha256必须为64位十六进制");
        request.setChecksumSha256(checksum);
        request.setLicenseName(required(body, "licenseName"));
        request.setLicenseDecision(required(body, "licenseDecision").toUpperCase(Locale.ROOT));
        request.setSourceVerified(bool(body.get("sourceVerified")) ? 1 : 0);
        request.setVulnerabilityCritical(nonNegativeInt(body.get("vulnerabilityCritical"), "vulnerabilityCritical"));
        request.setVulnerabilityHigh(nonNegativeInt(body.get("vulnerabilityHigh"), "vulnerabilityHigh"));
        if (!Integer.valueOf(1).equals(request.getSourceVerified())) {
            throw new BusinessException(422, "依赖来源未验证，不能批准");
        }
        if (!"APPROVED".equals(request.getLicenseDecision())) {
            throw new BusinessException(422, "许可证扫描未批准，不能进入运行时");
        }
        if (request.getVulnerabilityCritical() > 0 || request.getVulnerabilityHigh() > 0) {
            throw new BusinessException(422, "仍存在高危或严重漏洞，不能批准");
        }
        request.setStatus("APPROVED");
        requestMapper.updateById(request);
        updateDraftLinks(request.getId(), "APPROVED");
        runtimeBuildJobService.enqueue(request);
        return request;
    }

    /** Registers an attested image produced by the restricted build worker. */
    @Transactional
    public RuntimeProfile registerBuiltRuntime(Map<String, Object> body) {
        requireRuntimeManager();
        String actor = currentUserId();
        return registerBuiltRuntime(body, actor, actor);
    }

    /** Trusted entry point used only after worker HMAC and build-job lease validation. */
    @Transactional
    public RuntimeProfile registerBuiltRuntimeFromBuilder(Map<String, Object> body,
                                                          String builderActor,
                                                          String approvedByUserId) {
        if (builderActor == null || builderActor.isBlank()
                || approvedByUserId == null || approvedByUserId.isBlank()) {
            throw new BusinessException(422, "构建产物缺少可信身份");
        }
        return registerBuiltRuntime(body, builderActor, approvedByUserId);
    }

    private RuntimeProfile registerBuiltRuntime(Map<String, Object> body,
                                                String createdByUserId,
                                                String approvedByUserId) {
        List<Long> requestIds = ids(body.get("requestIds"));
        if (requestIds.isEmpty()) throw new BusinessException(422, "至少选择一个已批准依赖申请");
        List<DependencyRequest> requests = requestIds.stream().map(this::requireRequestInternal).toList();
        if (requests.stream().anyMatch(item -> !List.of("APPROVED", "READY").contains(item.getStatus()))) {
            throw new BusinessException(422, "只有APPROVED或READY依赖可以构建运行时");
        }
        String runtimeType = requests.get(0).getRuntimeType();
        if (requests.stream().anyMatch(item -> !runtimeType.equals(item.getRuntimeType()))) {
            throw new BusinessException(422, "一次构建不能混装不同运行时类型的依赖");
        }
        String code = required(body, "code").toLowerCase(Locale.ROOT);
        if (!PROFILE_CODE.matcher(code).matches()) throw new BusinessException(422, "runtimeProfile code格式不正确");
        if (profileMapper.selectCount(new LambdaQueryWrapper<RuntimeProfile>()
                .eq(RuntimeProfile::getCode, code)) > 0) {
            throw new BusinessException(409, "runtimeProfile code已存在: " + code);
        }
        String imageRef = required(body, "imageRef");
        if (imageRef.length() > 500 || !imageRef.matches("^[A-Za-z0-9._/:@-]+$")) {
            throw new BusinessException(422, "imageRef格式不正确");
        }
        String imageDigest = required(body, "imageDigest").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(imageDigest).matches()) throw new BusinessException(422, "imageDigest必须为sha256摘要");
        if (!imageRef.endsWith("@" + imageDigest)) {
            throw new BusinessException(422, "imageRef必须固定到与imageDigest一致的仓库摘要");
        }
        if (profileMapper.selectCount(new LambdaQueryWrapper<RuntimeProfile>()
                .eq(RuntimeProfile::getRuntimeType, runtimeType)
                .eq(RuntimeProfile::getImageDigest, imageDigest)) > 0) {
            throw new BusinessException(409, "相同运行时类型和镜像摘要已登记");
        }
        Map<String, Object> buildManifest = map(body.get("buildManifest"));
        Map<String, Object> securityReport = map(body.get("securityReport"));
        validateAttestations(buildManifest, securityReport);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setCode(code);
        profile.setName(required(body, "name"));
        profile.setRuntimeType(runtimeType);
        profile.setImageRef(imageRef);
        profile.setImageDigest(imageDigest);
        profile.setDependencyLock(json(requests.stream().map(this::lockItem).toList()));
        profile.setBuildManifest(json(buildManifest));
        profile.setSecurityReport(json(securityReport));
        profile.setStatus("ACTIVE");
        profile.setDefaultProfile(0);
        Long baseProfileId = longNumber(body.get("baseProfileId"));
        if (baseProfileId != null) {
            RuntimeProfile base = requireProfile(baseProfileId);
            if (!runtimeType.equals(base.getRuntimeType())) {
                throw new BusinessException(422, "基础运行时与新运行时类型不一致");
            }
            if (!"ACTIVE".equals(base.getStatus())) {
                throw new BusinessException(422, "不能基于已废弃运行时构建新版本");
            }
        }
        profile.setBaseProfileId(baseProfileId);
        profile.setCreatedByUserId(createdByUserId);
        profile.setApprovedByUserId(approvedByUserId);
        profileMapper.insert(profile);

        for (DependencyRequest request : requests) {
            RuntimeDependency dependency = new RuntimeDependency();
            dependency.setRuntimeProfileId(profile.getId());
            dependency.setRequestId(request.getId());
            dependency.setDependencyType(request.getDependencyType());
            dependency.setDependencyName(request.getDependencyName());
            dependency.setDependencyVersion(request.getResolvedVersion());
            dependency.setSourceUri(request.getSourceUri());
            dependency.setChecksumSha256(request.getChecksumSha256());
            dependency.setLicenseName(request.getLicenseName());
            dependency.setStatus("ACTIVE");
            runtimeDependencyMapper.insert(dependency);
            if ("APPROVED".equals(request.getStatus())) {
                request.setRuntimeProfileId(profile.getId());
                request.setStatus("READY");
                requestMapper.updateById(request);
            }
            updateDraftLinks(request.getId(), "RESOLVED");
        }
        return profile;
    }

    @Transactional
    public RuntimeProfile deprecateProfile(Long profileId) {
        requireRuntimeManager();
        RuntimeProfile profile = requireProfile(profileId);
        if ("DEPRECATED".equals(profile.getStatus())) return profile;
        profile.setStatus("DEPRECATED");
        profile.setDefaultProfile(0);
        profileMapper.updateById(profile);
        return profile;
    }

    @Transactional
    public DependencyRequest deprecateRequest(Long requestId) {
        requireRuntimeManager();
        DependencyRequest request = requireRequest(requestId);
        request.setStatus("DEPRECATED");
        requestMapper.updateById(request);
        if (request.getRuntimeProfileId() != null) {
            runtimeDependencyMapper.selectList(new LambdaQueryWrapper<RuntimeDependency>()
                .eq(RuntimeDependency::getRequestId, requestId)).forEach(item -> {
                    item.setStatus("DEPRECATED");
                    runtimeDependencyMapper.updateById(item);
                });
            RuntimeProfile profile = requireProfile(request.getRuntimeProfileId());
            profile.setStatus("DEPRECATED");
            profile.setDefaultProfile(0);
            profileMapper.updateById(profile);
        }
        return request;
    }

    public List<ProfileView> listProfiles(String runtimeType, boolean includeDeprecated) {
        LambdaQueryWrapper<RuntimeProfile> query = new LambdaQueryWrapper<RuntimeProfile>()
            .orderByAsc(RuntimeProfile::getRuntimeType).orderByDesc(RuntimeProfile::getCreatedAt);
        if (runtimeType != null && !runtimeType.isBlank()) query.eq(RuntimeProfile::getRuntimeType, runtimeType);
        if (!includeDeprecated) query.eq(RuntimeProfile::getStatus, "ACTIVE");
        return profileMapper.selectList(query).stream().map(profile -> new ProfileView(
            profile,
            runtimeDependencyMapper.selectList(new LambdaQueryWrapper<RuntimeDependency>()
                .eq(RuntimeDependency::getRuntimeProfileId, profile.getId())),
            bindingMapper.selectCount(new LambdaQueryWrapper<OperatorVersionRuntimeBinding>()
                .eq(OperatorVersionRuntimeBinding::getRuntimeProfileId, profile.getId()))
        )).toList();
    }

    public List<DraftDependency> draftDependencies(String draftType, Long draftId) {
        String normalizedType = required(Map.of("draftType", draftType), "draftType")
            .toUpperCase(Locale.ROOT);
        requireDraftOwner(normalizedType, draftId);
        return draftDependencyMapper.selectList(new LambdaQueryWrapper<DraftDependency>()
            .eq(DraftDependency::getDraftType, normalizedType)
            .eq(DraftDependency::getDraftId, draftId));
    }

    @Transactional
    public void markDraftMissing(String draftType, Long draftId,
                                 List<Map<String, Object>> requirements) {
        String normalizedType = draftType.toUpperCase(Locale.ROOT);
        for (Map<String, Object> requirement : requirements) {
            String type = required(requirement, "type").toUpperCase(Locale.ROOT);
            String name = required(requirement, "name");
            DraftDependency item = draftDependencyMapper.selectOne(new LambdaQueryWrapper<DraftDependency>()
                .eq(DraftDependency::getDraftType, normalizedType)
                .eq(DraftDependency::getDraftId, draftId)
                .eq(DraftDependency::getDependencyType, type)
                .eq(DraftDependency::getDependencyName, name)
                .last("LIMIT 1"));
            if (item == null) {
                item = new DraftDependency();
                item.setDraftType(normalizedType);
                item.setDraftId(draftId);
                item.setDependencyType(type);
                item.setDependencyName(name);
                item.setVersionConstraint(text(requirement.get("version")));
                item.setStatus("MISSING");
                draftDependencyMapper.insert(item);
            } else {
                item.setStatus("MISSING");
                draftDependencyMapper.updateById(item);
            }
        }
    }

    private void linkDraft(Map<String, Object> body, DependencyRequest request) {
        String draftType = text(body.get("draftType"));
        Long draftId = longNumber(body.get("draftId"));
        if (draftType == null && draftId == null) return;
        if (draftType == null || draftId == null) throw new BusinessException(422, "draftType和draftId必须同时提供");
        draftType = draftType.toUpperCase(Locale.ROOT);
        requireDraftOwner(draftType, draftId);
        DraftDependency link = draftDependencyMapper.selectOne(new LambdaQueryWrapper<DraftDependency>()
            .eq(DraftDependency::getDraftType, draftType)
            .eq(DraftDependency::getDraftId, draftId)
            .eq(DraftDependency::getDependencyType, request.getDependencyType())
            .eq(DraftDependency::getDependencyName, request.getDependencyName())
            .last("LIMIT 1"));
        if (link == null) {
            link = new DraftDependency();
            link.setDraftType(draftType);
            link.setDraftId(draftId);
            link.setDependencyType(request.getDependencyType());
            link.setDependencyName(request.getDependencyName());
            link.setVersionConstraint(request.getRequestedVersion());
        }
        link.setRequestId(request.getId());
        link.setStatus(request.getStatus());
        if (link.getId() == null) draftDependencyMapper.insert(link);
        else draftDependencyMapper.updateById(link);
    }

    private void requireDraftOwner(String draftType, Long draftId) {
        if ("RULE".equals(draftType)) {
            RuleDraft draft = ruleDraftMapper.selectById(draftId);
            if (draft == null) throw new BusinessException(404, "规则草稿不存在: " + draftId);
            requireOwner(draft.getCreatedByUserId());
        } else if ("OUTPUT".equals(draftType)) {
            OutputDraft draft = outputDraftMapper.selectById(draftId);
            if (draft == null) throw new BusinessException(404, "输出草稿不存在: " + draftId);
            requireOwner(draft.getCreatedByUserId());
        } else if ("POLICY".equals(draftType)) {
            PolicyDraft draft = policyDraftMapper.selectById(draftId);
            if (draft == null) throw new BusinessException(404, "策略草稿不存在: " + draftId);
            requireOwner(draft.getCreatedByUserId());
        } else throw new BusinessException(422, "draftType仅支持RULE、OUTPUT或POLICY");
    }

    private void updateDraftLinks(Long requestId, String status) {
        draftDependencyMapper.selectList(new LambdaQueryWrapper<DraftDependency>()
            .eq(DraftDependency::getRequestId, requestId)).forEach(item -> {
                item.setStatus(status);
                draftDependencyMapper.updateById(item);
            });
    }

    private void validateAttestations(Map<String, Object> build, Map<String, Object> security) {
        String sbomDigest = text(build.get("sbomDigest"));
        String provenanceDigest = text(build.get("provenanceDigest"));
        if (text(build.get("builder")) == null || sbomDigest == null || provenanceDigest == null) {
            throw new BusinessException(422, "构建证明必须包含builder、sbomDigest和provenanceDigest");
        }
        if (!SHA256.matcher(sbomDigest).matches() || !SHA256.matcher(provenanceDigest).matches()) {
            throw new BusinessException(422, "SBOM与provenance必须使用sha256摘要");
        }
        if (!bool(security.get("sourceVerified"))
                || !"APPROVED".equalsIgnoreCase(text(security.get("licenseDecision")))
                || nonNegativeInt(security.get("critical"), "critical") > 0
                || nonNegativeInt(security.get("high"), "high") > 0) {
            throw new BusinessException(422, "运行时安全证明未通过");
        }
    }

    private Map<String, Object> lockItem(DependencyRequest request) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", request.getDependencyType());
        item.put("name", request.getDependencyName());
        item.put("version", request.getResolvedVersion());
        item.put("source", request.getSourceUri());
        item.put("checksumSha256", request.getChecksumSha256());
        item.put("license", request.getLicenseName());
        return item;
    }

    private DependencyRequest requireRequest(Long id) {
        DependencyRequest request = requireRequestInternal(id);
        if (!canManageRuntime() && !currentUserId().equals(request.getOwnerUserId())) {
            throw new BusinessException(403, "无权访问该依赖申请");
        }
        return request;
    }
    private DependencyRequest requireRequestInternal(Long id) {
        DependencyRequest request = id == null ? null : requestMapper.selectById(id);
        if (request == null) throw new BusinessException(404, "依赖申请不存在: " + id);
        return request;
    }
    private RuntimeProfile requireProfile(Long id) {
        RuntimeProfile profile = id == null ? null : profileMapper.selectById(id);
        if (profile == null) throw new BusinessException(404, "运行时档案不存在: " + id);
        return profile;
    }
    private void requireOwner(String owner) {
        if (!canManageRuntime() && !currentUserId().equals(owner)) {
            throw new BusinessException(403, "无权关联该草稿");
        }
    }
    private List<Long> ids(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        java.util.LinkedHashSet<Long> result = new java.util.LinkedHashSet<>();
        for (Object item : list) result.add(longNumber(item));
        return new ArrayList<>(result);
    }
    private String exactVersion(String value) {
        if (value.matches(".*[\\s*^~<>,=].*") || value.length() > 120) {
            throw new BusinessException(422, "resolvedVersion必须是精确锁定版本");
        }
        return value;
    }
    private String coordinate(String value, String field) {
        if (!value.matches("^[A-Za-z0-9_.@/+:=-]{1,200}$")) {
            throw new BusinessException(422, field + "格式不正确");
        }
        return value;
    }
    private int nonNegativeInt(Object raw, String field) {
        try {
            int value = raw == null ? 0 : Integer.parseInt(String.valueOf(raw));
            if (value >= 0) return value;
        } catch (Exception ignored) {}
        throw new BusinessException(422, field + "必须是非负整数");
    }
    private boolean bool(Object raw) { return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw)); }
    private Long longNumber(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (Exception e) { throw new BusinessException(422, "ID必须是整数"); }
    }
    private String required(Map<String, Object> map, String field) {
        String value = text(map == null ? null : map.get(field));
        if (value == null) throw new BusinessException(422, field + "不能为空");
        return value;
    }
    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("依赖数据序列化失败"); }
    }
    private String nextRequestNo() {
        return "D" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }
    private boolean canManageRuntime() {
        return resourceAccessService.hasPermission(PermissionCodes.RUNTIME_MANAGE);
    }

    private void requireRuntimeManager() {
        resourceAccessService.requirePermission(PermissionCodes.RUNTIME_MANAGE, "需要运行治理权限");
    }

    private String currentUserId() { return resourceAccessService.currentUserId(); }

    public record ProfileView(RuntimeProfile profile, List<RuntimeDependency> dependencies,
                              long versionUsageCount) {}
}
