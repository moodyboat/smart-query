package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.entity.RuntimeProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Materializes a verified worker result into an immutable runtime and revalidates blocked drafts. */
@Service
@RequiredArgsConstructor
public class RuntimeBuildWorkerService {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-fA-F0-9]{64}$");
    private final RuntimeBuildJobService jobService;
    private final DependencyCenterService dependencyCenterService;
    private final DraftRevalidationService draftRevalidationService;
    private final ObjectMapper objectMapper;

    public RuntimeBuildJobService.BuildClaim claim(String rawBody) {
        Map<String, Object> body = object(rawBody);
        return jobService.claim(required(body, "workerId"), strings(body.get("runtimeTypes")));
    }

    public RuntimeBuildJob heartbeat(String jobNo, String leaseToken) {
        return jobService.heartbeat(jobNo, leaseToken);
    }

    @Transactional
    public RuntimeProfile registerManual(Map<String, Object> body) {
        List<Long> requestIds = ids(body.get("requestIds"));
        if (requestIds.isEmpty()) throw new BusinessException(422, "至少选择一个已批准依赖申请");
        jobService.ensureManualRegistrationAllowed(requestIds);
        RuntimeProfile profile = dependencyCenterService.registerBuiltRuntime(body);
        Map<String, Object> revalidation = draftRevalidationService.revalidate(requestIds, profile.getId());
        jobService.recordManualSuccess(requestIds, profile.getId(), body, revalidation);
        return profile;
    }

    @Transactional
    public RuntimeBuildJob complete(String jobNo, String leaseToken, String rawBody) {
        RuntimeBuildJob job = jobService.requireLeased(jobNo, leaseToken);
        Map<String, Object> body = object(rawBody);
        String status = required(body, "status").toUpperCase(Locale.ROOT);
        if ("FAILED".equals(status)) {
            return jobService.recordFailure(job, text(body.get("errorCode")),
                text(body.get("errorMessage")), body);
        }
        if (!"SUCCEEDED".equals(status)) {
            throw new BusinessException(422, "status仅支持SUCCEEDED或FAILED");
        }

        Map<String, Object> spec = object(job.getBuildSpec());
        Map<String, Object> sbom = map(body.get("sbom"));
        Map<String, Object> provenance = map(body.get("provenance"));
        Map<String, Object> security = map(body.get("security"));
        String sbomDigest = digest(sbom.get("digest"), "sbom.digest");
        String provenanceDigest = digest(provenance.get("digest"), "provenance.digest");
        String sbomUri = artifactUri(sbom.get("uri"), "sbom.uri");
        String provenanceUri = artifactUri(provenance.get("uri"), "provenance.uri");
        String builder = required(body, "builder");

        Map<String, Object> buildManifest = new LinkedHashMap<>();
        buildManifest.put("schemaVersion", "runtime-build-result/v1");
        buildManifest.put("jobNo", job.getJobNo());
        buildManifest.put("builder", builder);
        buildManifest.put("workerId", job.getWorkerId());
        buildManifest.put("sbomDigest", sbomDigest);
        buildManifest.put("sbomUri", sbomUri);
        buildManifest.put("provenanceDigest", provenanceDigest);
        buildManifest.put("provenanceUri", provenanceUri);
        copy(body, buildManifest, "sourceRevision");
        copy(body, buildManifest, "workflowRunUri");

        Map<String, Object> registration = new LinkedHashMap<>();
        List<Long> requestIds = ids(spec.get("requestIds"));
        if (requestIds.isEmpty() || !requestIds.contains(job.getDependencyRequestId())) {
            throw new BusinessException(500, "构建任务的依赖锁已损坏");
        }
        registration.put("requestIds", requestIds);
        registration.put("code", required(spec, "profileCode"));
        registration.put("name", required(spec, "profileName"));
        registration.put("baseProfileId", job.getBaseProfileId());
        registration.put("imageRef", required(body, "imageRef"));
        registration.put("imageDigest", digest(body.get("imageDigest"), "imageDigest"));
        registration.put("buildManifest", buildManifest);
        registration.put("securityReport", security);
        RuntimeProfile profile = dependencyCenterService.registerBuiltRuntimeFromBuilder(registration,
            "BUILD:" + job.getJobNo(), job.getApprovedByUserId());
        Map<String, Object> revalidation = draftRevalidationService.revalidate(
            requestIds, profile.getId());
        return jobService.recordSuccess(job, profile.getId(), body, revalidation);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        String value = text(source.get(key));
        if (value != null) target.put(key, value);
    }

    private String artifactUri(Object raw, String field) {
        String value = text(raw);
        if (value == null || value.length() > 1000
                || !(value.startsWith("https://") || value.startsWith("oci://"))) {
            throw new BusinessException(422, field + "必须是https或oci制品地址");
        }
        return value;
    }

    private String digest(Object raw, String field) {
        String value = text(raw);
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new BusinessException(422, field + "必须是sha256摘要");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> object(String json) {
        try { return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(422, "构建器JSON格式不正确"); }
    }

    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<Long> ids(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        try { return list.stream().map(value -> Long.parseLong(String.valueOf(value))).toList(); }
        catch (NumberFormatException error) { throw new BusinessException(500, "构建任务的依赖ID格式不正确"); }
    }

    private String required(Map<String, Object> body, String field) {
        String value = text(body.get(field));
        if (value == null) throw new BusinessException(422, field + "不能为空");
        return value;
    }

    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
