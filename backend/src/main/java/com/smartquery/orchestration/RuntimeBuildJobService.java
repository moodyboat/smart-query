package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.DependencyRequest;
import com.smartquery.entity.DraftDependency;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.mapper.DependencyRequestMapper;
import com.smartquery.mapper.DraftDependencyMapper;
import com.smartquery.mapper.RuntimeBuildJobMapper;
import com.smartquery.mapper.RuntimeProfileMapper;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable queue and lease state machine. It never builds an image in the business process. */
@Service
@RequiredArgsConstructor
public class RuntimeBuildJobService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RuntimeBuildJobMapper jobMapper;
    private final RuntimeProfileMapper profileMapper;
    private final DependencyRequestMapper requestMapper;
    private final DraftDependencyMapper draftDependencyMapper;
    private final ResourceAccessService resourceAccessService;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.runtime-builder.lease-seconds:1800}")
    private long leaseSeconds;

    @Value("${smart-query.runtime-builder.max-attempts:3}")
    private int configuredMaxAttempts;

    public List<RuntimeBuildJob> list(String status) {
        LambdaQueryWrapper<RuntimeBuildJob> query = new LambdaQueryWrapper<RuntimeBuildJob>()
            .orderByDesc(RuntimeBuildJob::getCreatedAt);
        if (!resourceAccessService.isAdmin()) {
            query.eq(RuntimeBuildJob::getRequestedByUserId, resourceAccessService.currentUserId());
        }
        if (status != null && !status.isBlank()) query.eq(RuntimeBuildJob::getStatus, status);
        return jobMapper.selectList(query);
    }

    @Transactional
    public RuntimeBuildJob enqueue(DependencyRequest request) {
        RuntimeBuildJob existing = jobMapper.selectOne(new LambdaQueryWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getDependencyRequestId, request.getId()).last("LIMIT 1"));
        if (existing != null) return existing;

        RuntimeProfile base = baseProfile(request.getRuntimeType());
        String jobNo = "RB" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<DependencyRequest> cohort = dependencyCohort(request);
        List<Map<String, Object>> dependencies = cohort.stream().map(this::dependencySpec).toList();
        List<Long> requestIds = cohort.stream().map(DependencyRequest::getId).toList();
        Map<String, Object> baseImage = new LinkedHashMap<>();
        if (base != null) {
            baseImage.put("runtimeProfileId", base.getId());
            baseImage.put("imageRef", base.getImageRef());
            baseImage.put("imageDigest", base.getImageDigest());
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("schemaVersion", "runtime-build/v1");
        spec.put("jobNo", jobNo);
        spec.put("runtimeType", request.getRuntimeType());
        spec.put("profileCode", profileCode(request, jobNo));
        spec.put("profileName", request.getRuntimeType() + " · " + request.getDependencyName()
            + "@" + request.getResolvedVersion() + (cohort.size() > 1 ? " 等" + cohort.size() + "项" : ""));
        spec.put("baseImage", baseImage);
        spec.put("requestIds", requestIds);
        spec.put("dependencies", dependencies);
        spec.put("policy", Map.of("networkDuringBuild", "ALLOWLIST_ONLY", "mutableTags", false,
            "runtimeInstall", false, "requireSbom", true, "requireProvenance", true));

        RuntimeBuildJob job = new RuntimeBuildJob();
        job.setJobNo(jobNo);
        job.setDependencyRequestId(request.getId());
        job.setRuntimeType(request.getRuntimeType());
        job.setBaseProfileId(base == null ? null : base.getId());
        job.setBuildSpec(json(spec));
        job.setStatus(RuntimeBuildStatus.QUEUED);
        job.setAttemptNo(0);
        job.setMaxAttempts(Math.max(1, configuredMaxAttempts));
        job.setRequestedByUserId(request.getOwnerUserId());
        job.setApprovedByUserId(request.getReviewedByUserId());
        jobMapper.insert(job);
        cancelSuperseded(job, new java.util.LinkedHashSet<>(requestIds));
        return job;
    }

    @Transactional
    public BuildClaim claim(String workerId, List<String> supportedRuntimeTypes) {
        String normalizedWorker = text(workerId);
        if (normalizedWorker == null || !normalizedWorker.matches("^[A-Za-z0-9_.:@/-]{1,160}$")) {
            throw new BusinessException(422, "workerId格式不正确");
        }
        requeueExpired();
        Set<String> supported = supportedRuntimeTypes == null ? Set.of() : supportedRuntimeTypes.stream()
            .filter(item -> item != null && !item.isBlank()).map(item -> item.toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        LambdaQueryWrapper<RuntimeBuildJob> query = new LambdaQueryWrapper<RuntimeBuildJob>()
            .in(RuntimeBuildJob::getStatus, List.of(RuntimeBuildStatus.QUEUED, RuntimeBuildStatus.RETRYABLE))
            .orderByAsc(RuntimeBuildJob::getCreatedAt).last("LIMIT 20");
        if (!supported.isEmpty()) query.in(RuntimeBuildJob::getRuntimeType, supported);
        for (RuntimeBuildJob candidate : jobMapper.selectList(query)) {
            String leaseToken = randomToken();
            LocalDateTime now = LocalDateTime.now();
            int updated = jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
                .eq(RuntimeBuildJob::getId, candidate.getId())
                .eq(RuntimeBuildJob::getStatus, candidate.getStatus())
                .set(RuntimeBuildJob::getStatus, RuntimeBuildStatus.BUILDING)
                .set(RuntimeBuildJob::getAttemptNo, candidate.getAttemptNo() + 1)
                .set(RuntimeBuildJob::getWorkerId, normalizedWorker)
                .set(RuntimeBuildJob::getLeaseTokenHash, sha256(leaseToken))
                .set(RuntimeBuildJob::getLeaseExpiresAt, now.plusSeconds(Math.max(60, leaseSeconds)))
                .set(RuntimeBuildJob::getStartedAt, now)
                .set(RuntimeBuildJob::getErrorCode, null)
                .set(RuntimeBuildJob::getErrorMessage, null));
            if (updated == 1) {
                RuntimeBuildJob claimed = jobMapper.selectById(candidate.getId());
                return new BuildClaim(claimed, object(claimed.getBuildSpec()), leaseToken,
                    "/api/v2/runtime-build-worker/jobs/" + claimed.getJobNo() + "/complete");
            }
        }
        return null;
    }

    public RuntimeBuildJob requireLeased(String jobNo, String leaseToken) {
        RuntimeBuildJob job = requireByNo(jobNo);
        if (!RuntimeBuildStatus.BUILDING.equals(job.getStatus())) {
            throw new BusinessException(409, "构建任务当前不能回传: " + job.getStatus());
        }
        if (job.getLeaseExpiresAt() == null || job.getLeaseExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(409, "构建任务租约已过期");
        }
        String actual = sha256(text(leaseToken) == null ? "" : leaseToken.trim());
        if (job.getLeaseTokenHash() == null || !MessageDigest.isEqual(
                job.getLeaseTokenHash().getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(401, "构建任务租约令牌不正确");
        }
        return job;
    }

    @Transactional
    public RuntimeBuildJob heartbeat(String jobNo, String leaseToken) {
        RuntimeBuildJob job = requireLeased(jobNo, leaseToken);
        job.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(Math.max(60, leaseSeconds)));
        jobMapper.updateById(job);
        return job;
    }

    @Transactional
    public RuntimeBuildJob recordFailure(RuntimeBuildJob job, String code, String message,
                                         Map<String, Object> manifest) {
        String next = job.getAttemptNo() < job.getMaxAttempts()
            ? RuntimeBuildStatus.RETRYABLE : RuntimeBuildStatus.FAILED;
        jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getId, job.getId())
            .eq(RuntimeBuildJob::getStatus, RuntimeBuildStatus.BUILDING)
            .set(RuntimeBuildJob::getStatus, next)
            .set(RuntimeBuildJob::getCompletedAt, LocalDateTime.now())
            .set(RuntimeBuildJob::getResultManifest, json(manifest == null ? Map.of() : manifest))
            .set(RuntimeBuildJob::getErrorCode, limit(text(code), 100))
            .set(RuntimeBuildJob::getErrorMessage, limit(text(message), 4000))
            .set(RuntimeBuildJob::getLeaseExpiresAt, null));
        return jobMapper.selectById(job.getId());
    }

    @Transactional
    public RuntimeBuildJob recordSuccess(RuntimeBuildJob job, Long profileId,
                                         Map<String, Object> manifest,
                                         Map<String, Object> revalidationReport) {
        int updated = jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getId, job.getId())
            .eq(RuntimeBuildJob::getStatus, RuntimeBuildStatus.BUILDING)
            .set(RuntimeBuildJob::getStatus, RuntimeBuildStatus.SUCCEEDED)
            .set(RuntimeBuildJob::getRuntimeProfileId, profileId)
            .set(RuntimeBuildJob::getResultManifest, json(manifest))
            .set(RuntimeBuildJob::getRevalidationReport, json(revalidationReport))
            .set(RuntimeBuildJob::getCompletedAt, LocalDateTime.now())
            .set(RuntimeBuildJob::getLeaseExpiresAt, null)
            .set(RuntimeBuildJob::getErrorCode, null)
            .set(RuntimeBuildJob::getErrorMessage, null));
        if (updated != 1) throw new BusinessException(409, "构建任务状态已被其他回调修改");
        return jobMapper.selectById(job.getId());
    }

    @Transactional
    public RuntimeBuildJob retry(Long jobId) {
        resourceAccessService.requireAdmin();
        RuntimeBuildJob job = require(jobId);
        if (!RuntimeBuildStatus.FAILED.equals(job.getStatus())) {
            throw new BusinessException(409, "只有最终失败的构建任务可以重试");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getId, jobId).eq(RuntimeBuildJob::getStatus, RuntimeBuildStatus.FAILED)
            .set(RuntimeBuildJob::getStatus, RuntimeBuildStatus.QUEUED)
            .set(RuntimeBuildJob::getAttemptNo, 0)
            .set(RuntimeBuildJob::getCompletedAt, null)
            .set(RuntimeBuildJob::getErrorCode, null)
            .set(RuntimeBuildJob::getErrorMessage, null));
        return jobMapper.selectById(jobId);
    }

    @Transactional
    public RuntimeBuildJob cancel(Long jobId) {
        resourceAccessService.requireAdmin();
        RuntimeBuildJob job = require(jobId);
        if (!List.of(RuntimeBuildStatus.QUEUED, RuntimeBuildStatus.RETRYABLE).contains(job.getStatus())) {
            throw new BusinessException(409, "只有等待中的构建任务可以取消");
        }
        job.setStatus(RuntimeBuildStatus.CANCELED);
        job.setCompletedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        return job;
    }

    public void ensureManualRegistrationAllowed(List<Long> requestIds) {
        long active = jobMapper.selectCount(new LambdaQueryWrapper<RuntimeBuildJob>()
            .in(RuntimeBuildJob::getDependencyRequestId, requestIds)
            .eq(RuntimeBuildJob::getStatus, RuntimeBuildStatus.BUILDING));
        if (active > 0) throw new BusinessException(409, "已有外部构建器领取相关任务，不能手工登记");
    }

    @Transactional
    public void recordManualSuccess(List<Long> requestIds, Long profileId, Map<String, Object> manifest,
                                    Map<String, Object> revalidationReport) {
        jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
            .in(RuntimeBuildJob::getDependencyRequestId, requestIds)
            .in(RuntimeBuildJob::getStatus, List.of(RuntimeBuildStatus.QUEUED,
                RuntimeBuildStatus.RETRYABLE, RuntimeBuildStatus.FAILED))
            .set(RuntimeBuildJob::getStatus, RuntimeBuildStatus.SUCCEEDED)
            .set(RuntimeBuildJob::getWorkerId, "MANUAL_ADMIN")
            .set(RuntimeBuildJob::getRuntimeProfileId, profileId)
            .set(RuntimeBuildJob::getResultManifest, json(manifest))
            .set(RuntimeBuildJob::getRevalidationReport, json(revalidationReport))
            .set(RuntimeBuildJob::getCompletedAt, LocalDateTime.now())
            .set(RuntimeBuildJob::getErrorCode, null)
            .set(RuntimeBuildJob::getErrorMessage, null));
    }

    private void requeueExpired() {
        jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getStatus, RuntimeBuildStatus.BUILDING)
            .lt(RuntimeBuildJob::getLeaseExpiresAt, LocalDateTime.now())
            .set(RuntimeBuildJob::getStatus, RuntimeBuildStatus.RETRYABLE)
            .set(RuntimeBuildJob::getLeaseTokenHash, null)
            .set(RuntimeBuildJob::getLeaseExpiresAt, null)
            .set(RuntimeBuildJob::getErrorCode, "LEASE_EXPIRED")
            .set(RuntimeBuildJob::getErrorMessage, "构建器租约过期，任务已重新排队"));
    }

    private RuntimeProfile baseProfile(String runtimeType) {
        RuntimeProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<RuntimeProfile>()
            .eq(RuntimeProfile::getRuntimeType, runtimeType).eq(RuntimeProfile::getStatus, "ACTIVE")
            .eq(RuntimeProfile::getDefaultProfile, 1).orderByDesc(RuntimeProfile::getCreatedAt).last("LIMIT 1"));
        if (profile != null) return profile;
        return profileMapper.selectOne(new LambdaQueryWrapper<RuntimeProfile>()
            .eq(RuntimeProfile::getRuntimeType, runtimeType).eq(RuntimeProfile::getStatus, "ACTIVE")
            .orderByDesc(RuntimeProfile::getCreatedAt).last("LIMIT 1"));
    }

    private List<DependencyRequest> dependencyCohort(DependencyRequest trigger) {
        Map<Long, DependencyRequest> cohort = new LinkedHashMap<>();
        cohort.put(trigger.getId(), trigger);
        List<DraftDependency> triggerLinks = draftDependencyMapper.selectList(
            new LambdaQueryWrapper<DraftDependency>().eq(DraftDependency::getRequestId, trigger.getId()));
        for (DraftDependency triggerLink : triggerLinks) {
            List<DraftDependency> siblings = draftDependencyMapper.selectList(
                new LambdaQueryWrapper<DraftDependency>()
                    .eq(DraftDependency::getDraftType, triggerLink.getDraftType())
                    .eq(DraftDependency::getDraftId, triggerLink.getDraftId()));
            for (DraftDependency sibling : siblings) {
                if (sibling.getRequestId() == null) continue;
                DependencyRequest candidate = requestMapper.selectById(sibling.getRequestId());
                if (candidate != null && trigger.getRuntimeType().equals(candidate.getRuntimeType())
                        && List.of("APPROVED", "READY").contains(candidate.getStatus())) {
                    cohort.put(candidate.getId(), candidate);
                }
            }
        }
        return new ArrayList<>(cohort.values());
    }

    private Map<String, Object> dependencySpec(DependencyRequest request) {
        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("requestId", request.getId());
        dependency.put("type", request.getDependencyType());
        dependency.put("name", request.getDependencyName());
        dependency.put("version", request.getResolvedVersion());
        dependency.put("sourceUri", request.getSourceUri());
        dependency.put("checksumSha256", request.getChecksumSha256());
        dependency.put("license", request.getLicenseName());
        return dependency;
    }

    private void cancelSuperseded(RuntimeBuildJob newJob, Set<Long> newRequestIds) {
        List<RuntimeBuildJob> pending = jobMapper.selectList(new LambdaQueryWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getRuntimeType, newJob.getRuntimeType())
            .in(RuntimeBuildJob::getStatus, List.of(RuntimeBuildStatus.QUEUED, RuntimeBuildStatus.RETRYABLE)));
        for (RuntimeBuildJob old : pending) {
            if (old.getId() == null || old.getId().equals(newJob.getId())) continue;
            Object rawIds = object(old.getBuildSpec()).get("requestIds");
            Set<Long> oldIds = new java.util.LinkedHashSet<>();
            if (rawIds instanceof List<?> list) {
                for (Object value : list) oldIds.add(Long.parseLong(String.valueOf(value)));
            }
            if (!oldIds.isEmpty() && newRequestIds.containsAll(oldIds)) {
                jobMapper.update(null, new LambdaUpdateWrapper<RuntimeBuildJob>()
                    .eq(RuntimeBuildJob::getId, old.getId())
                    .in(RuntimeBuildJob::getStatus,
                        List.of(RuntimeBuildStatus.QUEUED, RuntimeBuildStatus.RETRYABLE))
                    .set(RuntimeBuildJob::getStatus, RuntimeBuildStatus.CANCELED)
                    .set(RuntimeBuildJob::getCompletedAt, LocalDateTime.now())
                    .set(RuntimeBuildJob::getErrorCode, "SUPERSEDED")
                    .set(RuntimeBuildJob::getErrorMessage, "已由包含完整依赖集合的任务 " + newJob.getJobNo() + " 取代"));
            }
        }
    }

    private RuntimeBuildJob require(Long id) {
        RuntimeBuildJob job = id == null ? null : jobMapper.selectById(id);
        if (job == null) throw new BusinessException(404, "构建任务不存在: " + id);
        return job;
    }

    private RuntimeBuildJob requireByNo(String jobNo) {
        RuntimeBuildJob job = jobMapper.selectOne(new LambdaQueryWrapper<RuntimeBuildJob>()
            .eq(RuntimeBuildJob::getJobNo, jobNo).last("LIMIT 1"));
        if (job == null) throw new BusinessException(404, "构建任务不存在: " + jobNo);
        return job;
    }

    private String profileCode(DependencyRequest request, String jobNo) {
        String type = request.getRuntimeType().toLowerCase(Locale.ROOT).replace('_', '-');
        return (type + "-" + jobNo.toLowerCase(Locale.ROOT)).substring(0,
            Math.min(159, type.length() + jobNo.length() + 1));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new BusinessException(500, "租约摘要失败");
        }
    }

    private Map<String, Object> object(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(500, "构建规范损坏"); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new BusinessException(500, "构建任务序列化失败"); }
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    public record BuildClaim(RuntimeBuildJob job, Map<String, Object> buildSpec,
                             String leaseToken, String callbackPath) {}
}
