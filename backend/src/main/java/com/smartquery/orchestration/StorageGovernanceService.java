package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.ArchiveChunk;
import com.smartquery.entity.ArchiveRecord;
import com.smartquery.entity.NodeReplay;
import com.smartquery.entity.NodeReplayChunk;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.OutputArtifactCell;
import com.smartquery.entity.OutputArtifactRow;
import com.smartquery.entity.StoragePolicy;
import com.smartquery.entity.StorageUsage;
import com.smartquery.mapper.ArchiveChunkMapper;
import com.smartquery.mapper.ArchiveRecordMapper;
import com.smartquery.mapper.NodeReplayChunkMapper;
import com.smartquery.mapper.NodeReplayMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactCellMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import com.smartquery.mapper.StoragePolicyMapper;
import com.smartquery.mapper.StorageUsageMapper;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retention, recoverable archive, quota accounting and orchestration monitoring. */
@Service
@RequiredArgsConstructor
public class StorageGovernanceService {
    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";
    public static final String OUTPUT = "OUTPUT_ARTIFACT";
    public static final String REPLAY = "NODE_REPLAY";
    private static final String ARCHIVE_READY = "READY";
    private static final long MIN_QUOTA = 1_048_576L;

    private final StoragePolicyMapper policyMapper;
    private final StorageUsageMapper usageMapper;
    private final ArchiveRecordMapper archiveMapper;
    private final ArchiveChunkMapper archiveChunkMapper;
    private final OutputArtifactMapper artifactMapper;
    private final OutputArtifactRowMapper artifactRowMapper;
    private final OutputArtifactCellMapper artifactCellMapper;
    private final NodeReplayMapper replayMapper;
    private final NodeReplayChunkMapper replayChunkMapper;
    private final OrchestrationRunMapper runMapper;
    private final ArchivePayloadCodec archiveCodec;
    private final ObjectMapper objectMapper;
    private final ResourceAccessService resourceAccess;

    public StoragePolicy policy() {
        StoragePolicy policy = policyMapper.selectById(1L);
        if (policy != null) return policy;
        StoragePolicy created = defaults();
        try { policyMapper.insert(created); }
        catch (DuplicateKeyException ignored) { /* Another node initialized it. */ }
        StoragePolicy stored = policyMapper.selectById(1L);
        return stored == null ? created : stored;
    }

    @Transactional
    public StoragePolicy updatePolicy(Map<String, Object> request) {
        resourceAccess.requireAdmin();
        StoragePolicy current = policy();
        current.setOutputRetentionDays(integer(request, "outputRetentionDays",
            current.getOutputRetentionDays(), 1, 3650));
        current.setReplayRetentionDays(integer(request, "replayRetentionDays",
            current.getReplayRetentionDays(), 1, 3650));
        current.setHotQuotaBytesPerUser(longValue(request, "hotQuotaBytesPerUser",
            current.getHotQuotaBytesPerUser(), MIN_QUOTA, Long.MAX_VALUE / 4));
        current.setArchiveQuotaBytesPerUser(longValue(request, "archiveQuotaBytesPerUser",
            current.getArchiveQuotaBytesPerUser(), MIN_QUOTA, Long.MAX_VALUE / 4));
        current.setWarningPercent(integer(request, "warningPercent",
            current.getWarningPercent(), 50, 100));
        current.setAutoArchiveEnabled(booleanValue(request.get("autoArchiveEnabled"),
            Integer.valueOf(1).equals(current.getAutoArchiveEnabled())) ? 1 : 0);
        current.setUpdatedByUserId(resourceAccess.currentUserId());
        policyMapper.updateById(current);
        return policyMapper.selectById(1L);
    }

    public LocalDateTime retentionUntil(String targetType) {
        StoragePolicy policy = policy();
        int days = OUTPUT.equals(targetType)
            ? policy.getOutputRetentionDays() : policy.getReplayRetentionDays();
        return LocalDateTime.now().plusDays(Math.max(1, days));
    }

    public long estimateOutputBytes(String contentSpec, String artifactData,
                                    List<Map<String, Object>> records) {
        long bytes = utf8(contentSpec) + utf8(artifactData);
        if (records != null) {
            for (Map<String, Object> record : records) {
                long recordBytes = utf8(json(record));
                bytes = safeAdd(bytes, safeMultiply(recordBytes, 2));
            }
        }
        return Math.max(1, bytes);
    }

    /** Must join the caller's commit transaction so quota and business output cannot diverge. */
    @Transactional
    public void reserveHot(String ownerUserId, String targetType, long bytes) {
        if (bytes <= 0) return;
        StorageUsage usage = lockUsage(ownerUserId);
        StoragePolicy policy = policy();
        long next = safeAdd(value(usage.getHotBytes()), bytes);
        if (next > policy.getHotQuotaBytesPerUser()) {
            throw new BusinessException(413, "用户热存储容量不足：已使用" + value(usage.getHotBytes())
                + "字节，本次需要" + bytes + "字节，配额" + policy.getHotQuotaBytesPerUser() + "字节");
        }
        usage.setHotBytes(next);
        if (OUTPUT.equals(targetType)) {
            usage.setOutputHotBytes(safeAdd(value(usage.getOutputHotBytes()), bytes));
        } else if (REPLAY.equals(targetType)) {
            usage.setReplayHotBytes(safeAdd(value(usage.getReplayHotBytes()), bytes));
        } else {
            throw new BusinessException(422, "未知存储对象类型: " + targetType);
        }
        saveUsage(usage);
    }

    @Transactional
    public ArchiveRecord archiveOutput(Long artifactId, String reason) {
        resourceAccess.requireAdmin();
        return archiveOutputLocked(artifactId, boundedReason(reason), resourceAccess.currentUserId());
    }

    @Transactional
    public ArchiveRecord archiveOutputSystem(Long artifactId, String reason) {
        return archiveOutputLocked(artifactId, boundedReason(reason), "SYSTEM_RETENTION");
    }

    @Transactional
    public ArchiveRecord archiveReplay(Long replayId, String reason) {
        resourceAccess.requireAdmin();
        return archiveReplayLocked(replayId, boundedReason(reason), resourceAccess.currentUserId());
    }

    @Transactional
    public ArchiveRecord archiveReplaySystem(Long replayId, String reason) {
        return archiveReplayLocked(replayId, boundedReason(reason), "SYSTEM_RETENTION");
    }

    @Transactional
    public ArchiveRecord restore(Long archiveId) {
        resourceAccess.requireAdmin();
        ArchiveRecord archive = archiveMapper.selectForUpdate(archiveId);
        if (archive == null) throw new BusinessException(404, "归档记录不存在: " + archiveId);
        if (!ARCHIVE_READY.equals(archive.getState())) {
            throw new BusinessException(409, "归档记录当前不可恢复: " + archive.getState());
        }
        List<String> chunks = archiveChunkMapper.selectList(new LambdaQueryWrapper<ArchiveChunk>()
            .eq(ArchiveChunk::getArchiveId, archiveId).orderByAsc(ArchiveChunk::getChunkIndex))
            .stream().map(ArchiveChunk::getPayloadText).toList();
        if (chunks.size() != archive.getChunkCount()) throw new BusinessException(409, "归档分块数量不一致");
        JsonNode payload = archiveCodec.decode(archive.getPayloadFormat(), archive.getOriginalBytes(),
            archive.getStoredBytes(), archive.getChecksum(), chunks);
        if (OUTPUT.equals(archive.getTargetType())) restoreOutput(archive, payload);
        else if (REPLAY.equals(archive.getTargetType())) restoreReplay(archive, payload);
        else throw new BusinessException(409, "归档对象类型不受支持: " + archive.getTargetType());
        archiveChunkMapper.delete(new LambdaQueryWrapper<ArchiveChunk>()
            .eq(ArchiveChunk::getArchiveId, archiveId));
        archive.setState("RESTORED");
        archive.setRestoredByUserId(resourceAccess.currentUserId());
        archive.setRestoredAt(LocalDateTime.now());
        archiveMapper.updateById(archive);
        return archiveMapper.selectById(archiveId);
    }

    public List<Long> dueOutputIds(int limit) {
        return artifactMapper.selectList(new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getArchiveStatus, ACTIVE)
            .eq(OutputArtifact::getStatus, "READY")
            .isNotNull(OutputArtifact::getRetentionUntil)
            .le(OutputArtifact::getRetentionUntil, LocalDateTime.now())
            .orderByAsc(OutputArtifact::getRetentionUntil)
            .last("LIMIT " + boundedLimit(limit)))
            .stream().map(OutputArtifact::getId).toList();
    }

    public List<Long> dueReplayIds(int limit) {
        return replayMapper.selectList(new LambdaQueryWrapper<NodeReplay>()
            .eq(NodeReplay::getArchiveStatus, ACTIVE)
            .eq(NodeReplay::getStatus, RunStatus.SUCCESS)
            .isNotNull(NodeReplay::getRetentionUntil)
            .le(NodeReplay::getRetentionUntil, LocalDateTime.now())
            .orderByAsc(NodeReplay::getRetentionUntil)
            .last("LIMIT " + boundedLimit(limit)))
            .stream().map(NodeReplay::getId).toList();
    }

    @Transactional
    public int backfillHistoricalBatch(int limit) {
        int safeLimit = boundedLimit(limit);
        int changed = 0;
        List<OutputArtifact> artifacts = artifactMapper.selectList(new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getArchiveStatus, ACTIVE)
            .eq(OutputArtifact::getUsageAccounted, 0)
            .orderByAsc(OutputArtifact::getId).last("LIMIT " + safeLimit));
        for (OutputArtifact candidate : artifacts) {
            OutputArtifact artifact = artifactMapper.selectForUpdate(candidate.getId());
            if (artifact == null || !ACTIVE.equals(artifact.getArchiveStatus())
                    || Integer.valueOf(1).equals(artifact.getUsageAccounted())) continue;
            long bytes = storedOutputBytes(artifact);
            accountHistorical(artifact.getOwnerUserId(), OUTPUT, bytes);
            artifact.setPayloadBytes(bytes);
            artifact.setUsageAccounted(1);
            if (artifact.getRetentionUntil() == null) {
                artifact.setRetentionUntil(baseTime(artifact.getCreatedAt()).plusDays(policy().getOutputRetentionDays()));
            }
            artifactMapper.updateById(artifact);
            changed++;
        }
        List<NodeReplay> replays = replayMapper.selectList(new LambdaQueryWrapper<NodeReplay>()
            .eq(NodeReplay::getArchiveStatus, ACTIVE)
            .eq(NodeReplay::getStatus, RunStatus.SUCCESS)
            .eq(NodeReplay::getUsageAccounted, 0)
            .orderByAsc(NodeReplay::getId).last("LIMIT " + safeLimit));
        for (NodeReplay candidate : replays) {
            NodeReplay replay = replayMapper.selectForUpdate(candidate.getId());
            if (replay == null || !ACTIVE.equals(replay.getArchiveStatus())
                    || !RunStatus.SUCCESS.equals(replay.getStatus())
                    || Integer.valueOf(1).equals(replay.getUsageAccounted())) continue;
            long bytes = storedReplayBytes(replay);
            accountHistorical(replay.getOwnerUserId(), REPLAY, bytes);
            replay.setPayloadBytes(bytes);
            replay.setUsageAccounted(1);
            if (replay.getRetentionUntil() == null) {
                replay.setRetentionUntil(baseTime(replay.getCreatedAt()).plusDays(policy().getReplayRetentionDays()));
            }
            replayMapper.updateById(replay);
            changed++;
        }
        return changed;
    }

    public GovernanceDashboard dashboard() {
        resourceAccess.requireAdmin();
        StoragePolicy policy = policy();
        List<StorageUsage> allUsages = usageMapper.selectList(new LambdaQueryWrapper<StorageUsage>()
            .orderByDesc(StorageUsage::getHotBytes));
        List<StorageUsage> usages = allUsages.stream().limit(200).toList();
        List<ArchiveRecord> archives = archiveMapper.selectList(new LambdaQueryWrapper<ArchiveRecord>()
            .orderByDesc(ArchiveRecord::getArchivedAt).last("LIMIT 100"));
        List<OutputArtifact> outputs = artifactMapper.selectList(new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getArchiveStatus, ACTIVE).eq(OutputArtifact::getStatus, "READY")
            .orderByDesc(OutputArtifact::getCreatedAt).last("LIMIT 50"));
        List<NodeReplay> replays = replayMapper.selectList(new LambdaQueryWrapper<NodeReplay>()
            .eq(NodeReplay::getArchiveStatus, ACTIVE).eq(NodeReplay::getStatus, RunStatus.SUCCESS)
            .orderByDesc(NodeReplay::getCreatedAt).last("LIMIT 50"));
        Map<String, Object> summary = summary(allUsages);
        Map<String, Object> runs = runMonitor();
        return new GovernanceDashboard(policy, summary, usages, outputs, replays, archives,
            runs, alerts(policy, allUsages, runs));
    }

    private ArchiveRecord archiveOutputLocked(Long artifactId, String reason, String actor) {
        OutputArtifact artifact = artifactMapper.selectForUpdate(artifactId);
        if (artifact == null) throw new BusinessException(404, "输出结果不存在: " + artifactId);
        if (ARCHIVED.equals(artifact.getArchiveStatus())) return activeArchive(OUTPUT, artifactId);
        if (!ACTIVE.equals(artifact.getArchiveStatus()) || !"READY".equals(artifact.getStatus())) {
            throw new BusinessException(409, "输出结果当前不可归档");
        }
        List<OutputArtifactRow> rows = artifactRowMapper.selectList(
            new LambdaQueryWrapper<OutputArtifactRow>().eq(OutputArtifactRow::getArtifactId, artifactId)
                .orderByAsc(OutputArtifactRow::getRowIndex));
        List<OutputArtifactCell> cells = artifactCellMapper.selectList(
            new LambdaQueryWrapper<OutputArtifactCell>().eq(OutputArtifactCell::getArtifactId, artifactId)
                .orderByAsc(OutputArtifactCell::getRowIndex).orderByAsc(OutputArtifactCell::getFieldPath));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2);
        payload.put("contentSpec", artifact.getContentSpec());
        payload.put("artifactData", artifact.getArtifactData());
        payload.put("rows", rows);
        payload.put("cells", cells);
        ArchivePayloadCodec.EncodedPayload encoded = archiveCodec.encode(payload);
        long hotBytes = positive(artifact.getPayloadBytes(), encoded.originalBytes());
        ArchiveRecord archive = writeArchive(OUTPUT, artifactId, artifact.getOwnerUserId(),
            reason, actor, encoded);
        moveHotToArchive(artifact.getOwnerUserId(), OUTPUT, hotBytes, encoded.storedBytes(),
            Integer.valueOf(1).equals(artifact.getUsageAccounted()));
        artifactRowMapper.delete(new LambdaQueryWrapper<OutputArtifactRow>()
            .eq(OutputArtifactRow::getArtifactId, artifactId));
        artifactCellMapper.delete(new LambdaQueryWrapper<OutputArtifactCell>()
            .eq(OutputArtifactCell::getArtifactId, artifactId));
        // content_spec is NOT NULL; keep only a minimal tombstone while the render payload is cold.
        artifact.setContentSpec("{}");
        artifact.setArtifactData(null);
        artifact.setArchiveStatus(ARCHIVED);
        artifact.setArchivedAt(LocalDateTime.now());
        artifact.setPayloadBytes(hotBytes);
        artifact.setUsageAccounted(1);
        artifactMapper.updateById(artifact);
        return archive;
    }

    private ArchiveRecord archiveReplayLocked(Long replayId, String reason, String actor) {
        NodeReplay replay = replayMapper.selectForUpdate(replayId);
        if (replay == null) throw new BusinessException(404, "节点回放不存在: " + replayId);
        if (ARCHIVED.equals(replay.getArchiveStatus())) return activeArchive(REPLAY, replayId);
        if (!ACTIVE.equals(replay.getArchiveStatus()) || !RunStatus.SUCCESS.equals(replay.getStatus())) {
            throw new BusinessException(409, "只有成功且未归档的节点回放可以归档");
        }
        List<NodeReplayChunk> chunks = replayChunkMapper.selectList(new LambdaQueryWrapper<NodeReplayChunk>()
            .eq(NodeReplayChunk::getReplayId, replayId).orderByAsc(NodeReplayChunk::getAttemptNo)
            .orderByAsc(NodeReplayChunk::getPayloadKind).orderByAsc(NodeReplayChunk::getChunkIndex));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2);
        payload.put("outputSummary", replay.getOutputSummary());
        payload.put("diffSummary", replay.getDiffSummary());
        payload.put("executionLog", replay.getExecutionLog());
        payload.put("chunks", chunks);
        ArchivePayloadCodec.EncodedPayload encoded = archiveCodec.encode(payload);
        long hotBytes = positive(replay.getPayloadBytes(), encoded.originalBytes());
        ArchiveRecord archive = writeArchive(REPLAY, replayId, replay.getOwnerUserId(),
            reason, actor, encoded);
        moveHotToArchive(replay.getOwnerUserId(), REPLAY, hotBytes, encoded.storedBytes(),
            Integer.valueOf(1).equals(replay.getUsageAccounted()));
        replayChunkMapper.delete(new LambdaQueryWrapper<NodeReplayChunk>()
            .eq(NodeReplayChunk::getReplayId, replayId));
        replay.setOutputSummary(null);
        replay.setDiffSummary(null);
        replay.setExecutionLog(null);
        replay.setArchiveStatus(ARCHIVED);
        replay.setArchivedAt(LocalDateTime.now());
        replay.setPayloadBytes(hotBytes);
        replay.setUsageAccounted(1);
        replayMapper.updateById(replay);
        return archive;
    }

    private void restoreOutput(ArchiveRecord archive, JsonNode payload) {
        int payloadVersion = validatePayload(payload, "rows", "cells");
        OutputArtifact artifact = artifactMapper.selectForUpdate(archive.getTargetId());
        if (artifact == null || !ARCHIVED.equals(artifact.getArchiveStatus())
                || !archive.getOwnerUserId().equals(artifact.getOwnerUserId())) {
            throw new BusinessException(409, "归档记录与输出结果状态不一致");
        }
        if (artifactRowMapper.selectCount(new LambdaQueryWrapper<OutputArtifactRow>()
                .eq(OutputArtifactRow::getArtifactId, artifact.getId())) > 0
                || artifactCellMapper.selectCount(new LambdaQueryWrapper<OutputArtifactCell>()
                .eq(OutputArtifactCell::getArtifactId, artifact.getId())) > 0) {
            throw new BusinessException(409, "输出结果热表中已存在明细，拒绝重复恢复");
        }
        moveArchiveToHot(artifact.getOwnerUserId(), OUTPUT, artifact.getPayloadBytes(), archive.getStoredBytes());
        List<OutputArtifactRow> rows = objectMapper.convertValue(payload.path("rows"), new TypeReference<>() {});
        for (OutputArtifactRow row : rows) {
            if (!artifact.getId().equals(row.getArtifactId())) throw invalidArchiveTarget();
            row.setId(null);
            artifactRowMapper.insert(row);
        }
        List<OutputArtifactCell> cells = objectMapper.convertValue(payload.path("cells"), new TypeReference<>() {});
        for (OutputArtifactCell cell : cells) {
            if (!artifact.getId().equals(cell.getArtifactId())) throw invalidArchiveTarget();
            cell.setId(null);
            artifactCellMapper.insert(cell);
        }
        if (payloadVersion >= 2) {
            artifact.setContentSpec(requiredText(payload, "contentSpec"));
            artifact.setArtifactData(nullableText(payload, "artifactData"));
        }
        artifact.setArchiveStatus(ACTIVE);
        artifact.setArchivedAt(null);
        artifact.setRetentionUntil(retentionUntil(OUTPUT));
        artifactMapper.updateById(artifact);
    }

    private void restoreReplay(ArchiveRecord archive, JsonNode payload) {
        int payloadVersion = validatePayload(payload, "chunks");
        NodeReplay replay = replayMapper.selectForUpdate(archive.getTargetId());
        if (replay == null || !ARCHIVED.equals(replay.getArchiveStatus())
                || !archive.getOwnerUserId().equals(replay.getOwnerUserId())) {
            throw new BusinessException(409, "归档记录与节点回放状态不一致");
        }
        if (replayChunkMapper.selectCount(new LambdaQueryWrapper<NodeReplayChunk>()
                .eq(NodeReplayChunk::getReplayId, replay.getId())) > 0) {
            throw new BusinessException(409, "节点回放热表中已存在明细，拒绝重复恢复");
        }
        moveArchiveToHot(replay.getOwnerUserId(), REPLAY, replay.getPayloadBytes(), archive.getStoredBytes());
        List<NodeReplayChunk> chunks = objectMapper.convertValue(payload.path("chunks"), new TypeReference<>() {});
        for (NodeReplayChunk chunk : chunks) {
            if (!replay.getId().equals(chunk.getReplayId())) throw invalidArchiveTarget();
            chunk.setId(null);
            replayChunkMapper.insert(chunk);
        }
        if (payloadVersion >= 2) {
            replay.setOutputSummary(nullableText(payload, "outputSummary"));
            replay.setDiffSummary(nullableText(payload, "diffSummary"));
            replay.setExecutionLog(nullableText(payload, "executionLog"));
        }
        replay.setArchiveStatus(ACTIVE);
        replay.setArchivedAt(null);
        replay.setRetentionUntil(retentionUntil(REPLAY));
        replayMapper.updateById(replay);
    }

    private ArchiveRecord writeArchive(String type, Long targetId, String owner, String reason,
                                       String actor, ArchivePayloadCodec.EncodedPayload encoded) {
        ArchiveRecord archive = new ArchiveRecord();
        archive.setTargetType(type);
        archive.setTargetId(targetId);
        archive.setOwnerUserId(owner);
        archive.setState(ARCHIVE_READY);
        archive.setPayloadFormat(encoded.format());
        archive.setOriginalBytes(encoded.originalBytes());
        archive.setStoredBytes(encoded.storedBytes());
        archive.setChecksum(encoded.checksum());
        archive.setChunkCount(encoded.chunks().size());
        archive.setReason(reason);
        archive.setArchivedByUserId(actor);
        archive.setArchivedAt(LocalDateTime.now());
        archiveMapper.insert(archive);
        for (int index = 0; index < encoded.chunks().size(); index++) {
            ArchiveChunk chunk = new ArchiveChunk();
            chunk.setArchiveId(archive.getId());
            chunk.setChunkIndex(index);
            chunk.setPayloadText(encoded.chunks().get(index));
            archiveChunkMapper.insert(chunk);
        }
        return archive;
    }

    private ArchiveRecord activeArchive(String type, Long targetId) {
        ArchiveRecord record = archiveMapper.selectOne(new LambdaQueryWrapper<ArchiveRecord>()
            .eq(ArchiveRecord::getTargetType, type).eq(ArchiveRecord::getTargetId, targetId)
            .eq(ArchiveRecord::getState, ARCHIVE_READY).orderByDesc(ArchiveRecord::getId).last("LIMIT 1"));
        if (record == null) throw new BusinessException(409, "对象已归档但缺少可恢复归档记录");
        return record;
    }

    private StorageUsage lockUsage(String owner) {
        if (owner == null || owner.isBlank()) throw new BusinessException(422, "存储对象缺少所有者");
        StorageUsage usage = usageMapper.selectForUpdate(owner);
        if (usage != null) return usage;
        StorageUsage created = new StorageUsage();
        created.setOwnerUserId(owner);
        created.setHotBytes(0L);
        created.setArchiveBytes(0L);
        created.setOutputHotBytes(0L);
        created.setReplayHotBytes(0L);
        try { usageMapper.insert(created); }
        catch (DuplicateKeyException ignored) { /* Concurrent transaction created it. */ }
        usage = usageMapper.selectForUpdate(owner);
        if (usage == null) throw new BusinessException(500, "无法初始化用户存储容量账本");
        return usage;
    }

    private void moveHotToArchive(String owner, String type, long hotBytes, long archiveBytes,
                                  boolean hotWasAccounted) {
        StorageUsage usage = lockUsage(owner);
        long nextArchive = safeAdd(value(usage.getArchiveBytes()), archiveBytes);
        if (nextArchive > policy().getArchiveQuotaBytesPerUser()) {
            throw new BusinessException(413, "用户归档容量不足，无法安全迁移热数据");
        }
        if (hotWasAccounted) {
            usage.setHotBytes(Math.max(0, value(usage.getHotBytes()) - hotBytes));
            if (OUTPUT.equals(type)) usage.setOutputHotBytes(Math.max(0, value(usage.getOutputHotBytes()) - hotBytes));
            else usage.setReplayHotBytes(Math.max(0, value(usage.getReplayHotBytes()) - hotBytes));
        }
        usage.setArchiveBytes(nextArchive);
        saveUsage(usage);
    }

    private void moveArchiveToHot(String owner, String type, long hotBytes, long archiveBytes) {
        StorageUsage usage = lockUsage(owner);
        long nextHot = safeAdd(value(usage.getHotBytes()), hotBytes);
        if (nextHot > policy().getHotQuotaBytesPerUser()) {
            throw new BusinessException(413, "恢复后将超过用户热存储配额，请先归档其他结果");
        }
        usage.setHotBytes(nextHot);
        usage.setArchiveBytes(Math.max(0, value(usage.getArchiveBytes()) - archiveBytes));
        if (OUTPUT.equals(type)) usage.setOutputHotBytes(safeAdd(value(usage.getOutputHotBytes()), hotBytes));
        else usage.setReplayHotBytes(safeAdd(value(usage.getReplayHotBytes()), hotBytes));
        saveUsage(usage);
    }

    private void accountHistorical(String owner, String type, long bytes) {
        StorageUsage usage = lockUsage(owner);
        usage.setHotBytes(safeAdd(value(usage.getHotBytes()), bytes));
        if (OUTPUT.equals(type)) usage.setOutputHotBytes(safeAdd(value(usage.getOutputHotBytes()), bytes));
        else usage.setReplayHotBytes(safeAdd(value(usage.getReplayHotBytes()), bytes));
        saveUsage(usage);
    }

    private void saveUsage(StorageUsage usage) {
        usage.setUpdatedAt(LocalDateTime.now());
        usageMapper.updateById(usage);
    }

    private long storedOutputBytes(OutputArtifact artifact) {
        long bytes = utf8(artifact.getContentSpec()) + utf8(artifact.getArtifactData());
        List<OutputArtifactRow> rows = artifactRowMapper.selectList(new LambdaQueryWrapper<OutputArtifactRow>()
            .eq(OutputArtifactRow::getArtifactId, artifact.getId()));
        for (OutputArtifactRow row : rows) {
            bytes = safeAdd(bytes, utf8(row.getResultData()) + utf8(row.getSourceData())
                + utf8(row.getEvidenceData()) + utf8(row.getSourceRefs()));
        }
        List<OutputArtifactCell> cells = artifactCellMapper.selectList(
            new LambdaQueryWrapper<OutputArtifactCell>().eq(OutputArtifactCell::getArtifactId, artifact.getId()));
        for (OutputArtifactCell cell : cells) {
            bytes = safeAdd(bytes, utf8(cell.getFieldPath()));
            bytes = safeAdd(bytes, utf8(cell.getValueType()));
            bytes = safeAdd(bytes, utf8(cell.getTextValue()));
            bytes = safeAdd(bytes, utf8(cell.getTextSortValue()));
            bytes = safeAdd(bytes, utf8(cell.getValueHash()));
            if (cell.getNumberValue() != null) bytes = safeAdd(bytes, utf8(cell.getNumberValue().toPlainString()));
            if (cell.getBooleanValue() != null) bytes = safeAdd(bytes, Integer.BYTES);
        }
        return Math.max(1, bytes);
    }

    private long storedReplayBytes(NodeReplay replay) {
        long bytes = utf8(replay.getOutputSummary()) + utf8(replay.getDiffSummary())
            + utf8(replay.getExecutionLog());
        for (NodeReplayChunk chunk : replayChunkMapper.selectList(new LambdaQueryWrapper<NodeReplayChunk>()
                .eq(NodeReplayChunk::getReplayId, replay.getId()))) {
            bytes = safeAdd(bytes, utf8(chunk.getPayloadText()));
        }
        return Math.max(1, bytes);
    }

    private Map<String, Object> runMonitor() {
        List<OrchestrationRun> recent = runMapper.selectList(new LambdaQueryWrapper<OrchestrationRun>()
            .orderByDesc(OrchestrationRun::getCreatedAt).last("LIMIT 500"));
        List<OrchestrationRun> active = runMapper.selectList(new LambdaQueryWrapper<OrchestrationRun>()
            .in(OrchestrationRun::getStatus, RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.COMMITTING)
            .orderByAsc(OrchestrationRun::getCreatedAt).last("LIMIT 501"));
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> statuses = new LinkedHashMap<>();
        long expiredLeases = 0;
        long staleHeartbeats = 0;
        long oldestQueuedSeconds = 0;
        for (OrchestrationRun run : recent) {
            statuses.merge(run.getStatus(), 1L, Long::sum);
        }
        for (OrchestrationRun run : active.stream().limit(500).toList()) {
            if (Set.of(RunStatus.RUNNING, RunStatus.COMMITTING).contains(run.getStatus())) {
                if (run.getLeaseExpiresAt() != null && !run.getLeaseExpiresAt().isAfter(now)) expiredLeases++;
                if (run.getHeartbeatAt() == null || run.getHeartbeatAt().isBefore(now.minusMinutes(2))) staleHeartbeats++;
            }
            if (RunStatus.QUEUED.equals(run.getStatus()) && run.getCreatedAt() != null) {
                oldestQueuedSeconds = Math.max(oldestQueuedSeconds,
                    Math.max(0, Duration.between(run.getCreatedAt(), now).getSeconds()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statuses", statuses);
        result.put("statusWindowSize", recent.size());
        result.put("activeSampleTruncated", active.size() > 500);
        result.put("expiredLeases", expiredLeases);
        result.put("staleHeartbeats", staleHeartbeats);
        result.put("oldestQueuedSeconds", oldestQueuedSeconds);
        result.put("recentRuns", recent.stream().limit(100).toList());
        return result;
    }

    private Map<String, Object> summary(List<StorageUsage> usages) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hotBytes", usages.stream().mapToLong(item -> value(item.getHotBytes())).sum());
        result.put("archiveBytes", usages.stream().mapToLong(item -> value(item.getArchiveBytes())).sum());
        result.put("activeOutputs", artifactMapper.selectCount(new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getArchiveStatus, ACTIVE)));
        result.put("archivedOutputs", artifactMapper.selectCount(new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getArchiveStatus, ARCHIVED)));
        result.put("activeReplays", replayMapper.selectCount(new LambdaQueryWrapper<NodeReplay>()
            .eq(NodeReplay::getArchiveStatus, ACTIVE).eq(NodeReplay::getStatus, RunStatus.SUCCESS)));
        result.put("archivedReplays", replayMapper.selectCount(new LambdaQueryWrapper<NodeReplay>()
            .eq(NodeReplay::getArchiveStatus, ARCHIVED)));
        return result;
    }

    private List<Map<String, Object>> alerts(StoragePolicy policy, List<StorageUsage> usages,
                                              Map<String, Object> runs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StorageUsage usage : usages) {
            int hot = percent(value(usage.getHotBytes()), policy.getHotQuotaBytesPerUser());
            int archived = percent(value(usage.getArchiveBytes()), policy.getArchiveQuotaBytesPerUser());
            if (hot >= policy.getWarningPercent()) result.add(alert("warning", "用户热容量接近上限",
                "用户 " + usage.getOwnerUserId() + " 已使用 " + hot + "%"));
            if (archived >= policy.getWarningPercent()) result.add(alert("warning", "用户归档容量接近上限",
                "用户 " + usage.getOwnerUserId() + " 已使用 " + archived + "%"));
        }
        if (((Number) runs.get("expiredLeases")).longValue() > 0) result.add(alert("critical", "存在过期运行租约",
            runs.get("expiredLeases") + " 个运行需要恢复扫描"));
        if (((Number) runs.get("staleHeartbeats")).longValue() > 0) result.add(alert("warning", "运行心跳滞后",
            runs.get("staleHeartbeats") + " 个运行超过 2 分钟没有心跳"));
        if (((Number) runs.get("oldestQueuedSeconds")).longValue() > 60) result.add(alert("warning", "运行队列积压",
            "最早排队任务已等待 " + runs.get("oldestQueuedSeconds") + " 秒"));
        return List.copyOf(result);
    }

    private Map<String, Object> alert(String severity, String title, String detail) {
        return Map.of("severity", severity, "title", title, "detail", detail);
    }

    private int validatePayload(JsonNode payload, String... arrays) {
        int version = payload == null ? -1 : payload.path("version").asInt(-1);
        if (version < 1 || version > 2) throw invalidArchiveTarget();
        for (String field : arrays) if (!payload.path(field).isArray()) throw invalidArchiveTarget();
        return version;
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) throw invalidArchiveTarget();
        return value.textValue();
    }

    private String nullableText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || (!value.isNull() && !value.isTextual())) throw invalidArchiveTarget();
        return value.isNull() ? null : value.textValue();
    }

    private BusinessException invalidArchiveTarget() {
        return new BusinessException(409, "归档明细与目标对象不匹配");
    }

    private StoragePolicy defaults() {
        StoragePolicy policy = new StoragePolicy();
        policy.setId(1L);
        policy.setOutputRetentionDays(90);
        policy.setReplayRetentionDays(30);
        policy.setHotQuotaBytesPerUser(1_073_741_824L);
        policy.setArchiveQuotaBytesPerUser(5_368_709_120L);
        policy.setWarningPercent(80);
        policy.setAutoArchiveEnabled(1);
        policy.setUpdatedByUserId("SYSTEM_DEFAULT");
        return policy;
    }

    private int integer(Map<String, Object> request, String key, Integer fallback, int min, int max) {
        if (request == null || !request.containsKey(key)) return fallback;
        try {
            int value = Integer.parseInt(String.valueOf(request.get(key)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new BusinessException(422, key + " 必须在 " + min + " 到 " + max + " 之间");
        }
    }

    private long longValue(Map<String, Object> request, String key, Long fallback, long min, long max) {
        if (request == null || !request.containsKey(key)) return fallback;
        try {
            long value = Long.parseLong(String.valueOf(request.get(key)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new BusinessException(422, key + " 超出允许范围");
        }
    }

    private boolean booleanValue(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        if ("true".equalsIgnoreCase(String.valueOf(raw))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(raw))) return false;
        throw new BusinessException(422, "autoArchiveEnabled 必须是布尔值");
    }

    private String boundedReason(String reason) {
        String value = reason == null || reason.isBlank() ? "管理员手动归档" : reason.trim();
        if (value.length() > 500) throw new BusinessException(422, "归档原因不能超过500字符");
        return value;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new BusinessException(422, "存储容量估算失败"); }
    }

    private long utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private long value(Long raw) { return raw == null ? 0 : Math.max(0, raw); }
    private long positive(Long raw, long fallback) { return raw == null || raw <= 0 ? Math.max(1, fallback) : raw; }
    private int boundedLimit(int limit) { return Math.max(1, Math.min(limit, 100)); }
    private LocalDateTime baseTime(LocalDateTime value) { return value == null ? LocalDateTime.now() : value; }
    private int percent(long value, long total) {
        return total <= 0 ? 100 : (int) Math.min(100, Math.round((double) value * 100D / total));
    }
    private long safeAdd(long first, long second) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException error) { throw new BusinessException(413, "存储容量数值溢出"); }
    }
    private long safeMultiply(long value, long factor) {
        try { return Math.multiplyExact(value, factor); }
        catch (ArithmeticException error) { throw new BusinessException(413, "输出结果过大"); }
    }

    public record GovernanceDashboard(StoragePolicy policy, Map<String, Object> summary,
                                      List<StorageUsage> usages, List<OutputArtifact> outputs,
                                      List<NodeReplay> replays, List<ArchiveRecord> archives,
                                      Map<String, Object> runs, List<Map<String, Object>> alerts) {}
}
