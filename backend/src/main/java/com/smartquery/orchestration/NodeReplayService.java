package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.service.RoleService;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.NodeReplay;
import com.smartquery.entity.NodeReplayChunk;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.NodeRunSnapshot;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.NodeReplayChunkMapper;
import com.smartquery.mapper.NodeReplayMapper;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.orchestration.execution.LineageSupport;
import com.smartquery.orchestration.execution.OperatorExecutionContext;
import com.smartquery.orchestration.execution.OperatorExecutionResult;
import com.smartquery.orchestration.execution.OperatorExecutor;
import com.smartquery.orchestration.execution.OperatorExecutorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Replays one historical node against its exact captured contract without committing business output. */
@Slf4j
@Service
public class NodeReplayService {
    private static final String REPLAY_OUTPUT = "REPLAY_OUTPUT";
    private static final Set<String> REPLAYABLE_SOURCE_STATUSES = Set.of(
        RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.TIMED_OUT);
    private static final Set<String> TERMINAL = Set.of(
        RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.CANCELED, RunStatus.TIMED_OUT);

    private final NodeReplayMapper replayMapper;
    private final NodeReplayChunkMapper chunkMapper;
    private final NodeRunSnapshotService snapshotService;
    private final OrchestrationRunMapper runMapper;
    private final NodeRunMapper nodeRunMapper;
    private final FlowVersionMapper flowVersionMapper;
    private final OperatorVersionMapper operatorVersionMapper;
    private final RuntimeProfileService runtimeProfileService;
    private final OperatorExecutorRegistry executorRegistry;
    private final ContentHashService contentHashService;
    private final NodeReplayDiffService diffService;
    private final ReplayOutputCommitService replayOutputCommitService;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;
    private final Executor orchestrationExecutor;
    private final Executor orchestrationNodeExecutor;
    private final ScheduledExecutorService orchestrationWatchdog;
    private final Set<Long> dispatched = ConcurrentHashMap.newKeySet();
    private final Map<Long, Future<?>> activeTasks = new ConcurrentHashMap<>();

    @Value("${smart-query.orchestration.node.replay-detail-record-limit:20}")
    private int detailRecordLimit = 20;

    @Value("${smart-query.orchestration.node.replay-snapshot-max-bytes:16777216}")
    private long maxReplayOutputBytes = 16_777_216L;

    public NodeReplayService(NodeReplayMapper replayMapper, NodeReplayChunkMapper chunkMapper,
                             NodeRunSnapshotService snapshotService,
                             OrchestrationRunMapper runMapper, NodeRunMapper nodeRunMapper,
                             FlowVersionMapper flowVersionMapper,
                             OperatorVersionMapper operatorVersionMapper,
                             RuntimeProfileService runtimeProfileService,
                             OperatorExecutorRegistry executorRegistry,
                             ContentHashService contentHashService,
                             NodeReplayDiffService diffService,
                             ReplayOutputCommitService replayOutputCommitService,
                             ObjectMapper objectMapper,
                             RoleService roleService,
                             @Qualifier("orchestrationExecutor") Executor orchestrationExecutor,
                             @Qualifier("orchestrationNodeExecutor") Executor orchestrationNodeExecutor,
                             @Qualifier("orchestrationWatchdog") ScheduledExecutorService orchestrationWatchdog) {
        this.replayMapper = replayMapper;
        this.chunkMapper = chunkMapper;
        this.snapshotService = snapshotService;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.flowVersionMapper = flowVersionMapper;
        this.operatorVersionMapper = operatorVersionMapper;
        this.runtimeProfileService = runtimeProfileService;
        this.executorRegistry = executorRegistry;
        this.contentHashService = contentHashService;
        this.diffService = diffService;
        this.replayOutputCommitService = replayOutputCommitService;
        this.objectMapper = objectMapper;
        this.roleService = roleService;
        this.orchestrationExecutor = orchestrationExecutor;
        this.orchestrationNodeExecutor = orchestrationNodeExecutor;
        this.orchestrationWatchdog = orchestrationWatchdog;
    }

    public NodeReplay create(Long runId, Long nodeRunId) {
        OrchestrationRun run = requireRun(runId);
        if (!Set.of(RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.CANCELED).contains(run.getStatus())) {
            throw new BusinessException(409, "流程运行尚未结束，不能冻结节点回放任务");
        }
        NodeRun nodeRun = requireSourceNode(runId, nodeRunId);
        if (!REPLAYABLE_SOURCE_STATUSES.contains(nodeRun.getStatus())) {
            throw new BusinessException(409, "节点尚未结束，当前状态不可回放: " + nodeRun.getStatus());
        }
        NodeRunSnapshot snapshot = snapshotService.requireByNodeRun(nodeRunId);
        verifySnapshotSource(run, nodeRun, snapshot);

        NodeReplay replay = new NodeReplay();
        replay.setReplayNo("NR-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + "-" + UUID.randomUUID().toString().substring(0, 8));
        replay.setSourceRunId(runId);
        replay.setSourceNodeRunId(nodeRunId);
        replay.setSnapshotId(snapshot.getId());
        replay.setFlowVersionId(snapshot.getFlowVersionId());
        replay.setFlowContentHash(snapshot.getFlowContentHash());
        replay.setNodeId(snapshot.getNodeId());
        replay.setOperatorVersionId(snapshot.getOperatorVersionId());
        replay.setOperatorVersionContentHash(snapshot.getOperatorVersionContentHash());
        replay.setRuntimeProfileId(snapshot.getRuntimeProfileId());
        replay.setRuntimeImageDigest(snapshot.getRuntimeImageDigest());
        replay.setInputHash(snapshot.getInputHash());
        replay.setExpectedOutputHash(snapshot.getOutputHash());
        replay.setStatus(RunStatus.QUEUED);
        replay.setAttemptNo(0);
        replay.setTimeoutSeconds(nodeRun.getTimeoutSeconds() == null ? 300 : nodeRun.getTimeoutSeconds());
        replay.setOwnerUserId(run.getOwnerUserId());
        replay.setActorRole(run.getActorRole() == null || run.getActorRole().isBlank()
            ? roleService.defaultRoleCode() : run.getActorRole());
        replay.setArchiveStatus(StorageGovernanceService.ACTIVE);
        replay.setPayloadBytes(0L);
        replay.setUsageAccounted(0);
        replayMapper.insert(replay);
        dispatch(replay.getId());
        return replay;
    }

    public List<NodeReplay> list(Long runId) {
        requireRun(runId);
        return replayMapper.selectList(new LambdaQueryWrapper<NodeReplay>()
            .eq(NodeReplay::getSourceRunId, runId)
            .orderByDesc(NodeReplay::getCreatedAt)
            .orderByDesc(NodeReplay::getId));
    }

    @Transactional(readOnly = true)
    public ReplayDetail detail(Long replayId) {
        NodeReplay replay = requireReplay(replayId);
        if (StorageGovernanceService.ARCHIVED.equals(replay.getArchiveStatus())) {
            throw new BusinessException(409, "节点回放结果已归档，请由具备运行治理权限的人员恢复后查看");
        }
        NodeRun source = requireSourceNode(replay.getSourceRunId(), replay.getSourceNodeRunId());
        NodeRunSnapshot snapshot = snapshotService.require(replay.getSnapshotId());
        NodeRunSnapshotService.SnapshotMaterial material = snapshotService.material(snapshot);
        Map<String, Object> output = RunStatus.SUCCESS.equals(replay.getStatus())
            ? readReplayOutput(replay) : Map.of();
        Map<String, Object> diff = parseObject(replay.getDiffSummary());
        return new ReplayDetail(replay, source, snapshot, diff,
            sample(material.originalOutput()), sample(output));
    }

    public NodeReplay cancel(Long replayId) {
        NodeReplay replay = requireReplay(replayId);
        if (TERMINAL.contains(replay.getStatus())) return replay;
        UpdateWrapper<NodeReplay> update = new UpdateWrapper<>();
        update.set("status", RunStatus.CANCELED)
            .set("error_message", "节点回放已由用户取消")
            .set("finished_at", LocalDateTime.now())
            .eq("id", replayId)
            .in("status", RunStatus.QUEUED, RunStatus.RUNNING);
        replayMapper.update(null, update);
        Future<?> task = activeTasks.get(replayId);
        if (task != null) task.cancel(true);
        return replayMapper.selectById(replayId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        dispatchRecoverable();
    }

    @Scheduled(fixedDelayString = "${smart-query.orchestration.lease.scan-interval-ms:5000}")
    public void dispatchRecoverable() {
        try {
            replayMapper.selectList(new LambdaQueryWrapper<NodeReplay>()
                .and(query -> query.eq(NodeReplay::getStatus, RunStatus.QUEUED)
                    .or(nested -> nested.eq(NodeReplay::getStatus, RunStatus.RUNNING)
                        .le(NodeReplay::getLeaseExpiresAt, LocalDateTime.now())))
                .orderByAsc(NodeReplay::getCreatedAt)
                .last("LIMIT 100"))
                .forEach(item -> dispatch(item.getId()));
        } catch (Exception error) {
            log.warn("[NODE_REPLAY] recoverable scan failed: {}", rootMessage(error));
        }
    }

    private void dispatch(Long replayId) {
        if (!dispatched.add(replayId)) return;
        try {
            orchestrationExecutor.execute(() -> {
                try { execute(replayId); }
                finally { dispatched.remove(replayId); }
            });
        } catch (RejectedExecutionException error) {
            dispatched.remove(replayId);
            log.warn("[NODE_REPLAY] queue is full for replay {}", replayId);
        }
    }

    private void execute(Long replayId) {
        NodeReplay replay = claim(replayId);
        if (replay == null) return;
        long startedNanos = System.nanoTime();
        try (UserContextHolder.Scope ignored = UserContextHolder.open(actor(replay))) {
            NodeRunSnapshot snapshot = snapshotService.require(replay.getSnapshotId());
            verifyReplayBinding(replay, snapshot);
            NodeRunSnapshotService.SnapshotMaterial material = snapshotService.material(snapshot);
            OperatorVersion capturedVersion = objectMapper.convertValue(
                material.operatorVersion(), OperatorVersion.class);
            verifyImmutableDependencies(replay, snapshot, capturedVersion);
            verifyInputHash(replay, material);

            Map<String, OperatorExecutionResult> upstream = upstream(material.upstreamOutputs());
            OperatorExecutionContext context = new OperatorExecutionContext(
                replay.getSourceRunId(), replay.getSourceNodeRunId(), replay.getNodeId(),
                snapshot.getOperatorType(), capturedVersion,
                parseObject(capturedVersion.getImplementationPayload()), material.nodeConfig(),
                material.runInput(), upstream);
            OperatorExecutor executor = executorRegistry.require(capturedVersion.getImplementationType());
            OperatorExecutionResult result = executeBounded(replay, executor, context);
            LineageSupport.requirePreserved(result.output(), replay.getNodeId());
            String replayOutputJson = json(result.output());
            if (replayOutputJson.getBytes(StandardCharsets.UTF_8).length > maxReplayOutputBytes) {
                throw new BusinessException(413, "节点回放输出超过" + maxReplayOutputBytes + "字节限制");
            }
            String outputHash = contentHashService.sha256(result.output());
            Map<String, Object> diff = "COMPLETED".equals(snapshot.getStatus())
                ? diffService.compare(material.originalOutput(), result.output(), detailRecordLimit)
                : Map.of("baselineAvailable", false, "exactMatch", false,
                    "reason", "原节点未成功，因此没有原输出基线");
            replayOutputCommitService.commit(replay, replayOutputJson, outputHash,
                json(summary(result.output())), boundedDiffJson(diff),
                limit("sideEffectFree=true\n" + result.executionLog(), 16000),
                elapsedMillis(startedNanos));
        } catch (ReplayTimeoutException error) {
            markFailure(replay, RunStatus.TIMED_OUT, error.getMessage(), startedNanos);
        } catch (CancellationException error) {
            if (!RunStatus.CANCELED.equals(status(replayId))) {
                markFailure(replay, RunStatus.FAILED, "节点回放执行被中断", startedNanos);
            }
        } catch (Exception error) {
            if (!RunStatus.CANCELED.equals(status(replayId))) {
                markFailure(replay, RunStatus.FAILED, rootMessage(error), startedNanos);
            }
        } finally {
            activeTasks.remove(replayId);
            Thread.interrupted();
        }
    }

    private NodeReplay claim(Long replayId) {
        NodeReplay current = replayMapper.selectById(replayId);
        if (current == null) return null;
        LocalDateTime now = LocalDateTime.now();
        boolean queued = RunStatus.QUEUED.equals(current.getStatus());
        boolean expired = RunStatus.RUNNING.equals(current.getStatus())
            && current.getLeaseExpiresAt() != null && !current.getLeaseExpiresAt().isAfter(now);
        if (!queued && !expired) return null;
        String token = UUID.randomUUID().toString();
        int attempt = (current.getAttemptNo() == null ? 0 : current.getAttemptNo()) + 1;
        int timeout = Math.max(1, Math.min(current.getTimeoutSeconds() == null ? 300
            : current.getTimeoutSeconds(), 3600));
        UpdateWrapper<NodeReplay> update = new UpdateWrapper<>();
        update.set("status", RunStatus.RUNNING)
            .set("attempt_no", attempt)
            .set("lease_token", token)
            .set("lease_expires_at", now.plusSeconds(timeout + 60L))
            .set("started_at", now)
            .set("finished_at", null)
            .set("error_message", null)
            .eq("id", replayId)
            .eq("attempt_no", current.getAttemptNo());
        if (queued) {
            update.eq("status", RunStatus.QUEUED);
        } else {
            update.eq("status", RunStatus.RUNNING)
                .eq("lease_token", current.getLeaseToken())
                .le("lease_expires_at", now);
        }
        if (replayMapper.update(null, update) != 1) return null;
        return replayMapper.selectById(replayId);
    }

    private OperatorExecutionResult executeBounded(NodeReplay replay, OperatorExecutor executor,
                                                    OperatorExecutionContext context) {
        FutureTask<OperatorExecutionResult> task = new FutureTask<>(() -> executor.execute(context));
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timeout = orchestrationWatchdog.schedule(() -> {
            timedOut.set(true);
            task.cancel(true);
        }, Math.max(1, replay.getTimeoutSeconds()), TimeUnit.SECONDS);
        activeTasks.put(replay.getId(), task);
        try {
            if (!ownedAndRunning(replay)) {
                task.cancel(true);
                throw new CancellationException();
            }
            orchestrationNodeExecutor.execute(task);
            return task.get(Math.max(2, replay.getTimeoutSeconds() + 1L), TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw new ReplayTimeoutException("节点回放超过" + replay.getTimeoutSeconds() + "秒执行上限");
        } catch (CancellationException error) {
            if (timedOut.get()) {
                throw new ReplayTimeoutException("节点回放超过" + replay.getTimeoutSeconds() + "秒执行上限");
            }
            throw error;
        } catch (InterruptedException error) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new BusinessException(500, rootMessage(cause));
        } catch (RejectedExecutionException error) {
            throw new BusinessException(503, "节点回放执行队列已满");
        } finally {
            timeout.cancel(false);
        }
    }

    private void verifyImmutableDependencies(NodeReplay replay, NodeRunSnapshot snapshot,
                                             OperatorVersion capturedVersion) {
        FlowVersion flowVersion = flowVersionMapper.selectById(replay.getFlowVersionId());
        if (flowVersion == null || !Objects.equals(flowVersion.getContentHash(), replay.getFlowContentHash())
                || !Objects.equals(contentHashService.sha256(Map.of(
                    "flowId", flowVersion.getFlowId(),
                    "nodes", parseValue(flowVersion.getNodes()),
                    "edges", parseValue(flowVersion.getEdges()),
                    "parameterMappings", parseValue(flowVersion.getParameterMappings()))),
                    replay.getFlowContentHash())) {
            throw new BusinessException(409, "原流程版本缺失或内容哈希已变化，拒绝回放");
        }
        OperatorVersion currentVersion = operatorVersionMapper.selectById(replay.getOperatorVersionId());
        if (currentVersion == null
                || !Objects.equals(currentVersion.getContentHash(), replay.getOperatorVersionContentHash())
                || !Objects.equals(capturedVersion.getId(), replay.getOperatorVersionId())
                || !Objects.equals(capturedVersion.getContentHash(), replay.getOperatorVersionContentHash())
                || !Objects.equals(capturedVersion.getImplementationType(), snapshot.getImplementationType())) {
            throw new BusinessException(409, "原算子版本缺失或不可变快照校验失败，拒绝回放");
        }
        RuntimeProfileService.RuntimeBindingView runtime =
            runtimeProfileService.requireRunnable(capturedVersion, snapshot.getOperatorType());
        if (!Objects.equals(runtime.profile().getId(), replay.getRuntimeProfileId())
                || !Objects.equals(runtime.binding().getImageDigest(), replay.getRuntimeImageDigest())) {
            throw new BusinessException(409, "原运行时绑定或镜像摘要已变化，拒绝回放");
        }
        if (!Objects.equals(operatorContentHash(currentVersion, snapshot, runtime),
                replay.getOperatorVersionContentHash())
                || !Objects.equals(operatorContentHash(capturedVersion, snapshot, runtime),
                    replay.getOperatorVersionContentHash())) {
            throw new BusinessException(409, "原算子版本内容与登记哈希不一致，拒绝回放");
        }
    }

    private String operatorContentHash(OperatorVersion version, NodeRunSnapshot snapshot,
                                       RuntimeProfileService.RuntimeBindingView runtime) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("operatorId", version.getOperatorId());
        material.put("operatorType", snapshot.getOperatorType());
        material.put("inputSchema", parseValue(version.getInputSchema()));
        material.put("outputSchema", parseValue(version.getOutputSchema()));
        material.put("parameterSchema", parseValue(version.getParameterSchema()));
        material.put("implementationType", version.getImplementationType());
        material.put("implementationPayload", parseValue(version.getImplementationPayload()));
        material.put("capabilityRequirements", parseValue(version.getCapabilityRequirements()));
        material.put("runtimeProfileId", runtime.profile().getId());
        material.put("runtimeImageDigest", runtime.binding().getImageDigest());
        return contentHashService.sha256(material);
    }

    private void verifyInputHash(NodeReplay replay, NodeRunSnapshotService.SnapshotMaterial material) {
        Object hashMaterial = material.upstreamOutputs().isEmpty()
            ? material.runInput() : material.upstreamOutputs();
        String actual = contentHashService.sha256(hashMaterial);
        if (!Objects.equals(actual, replay.getInputHash())) {
            throw new BusinessException(409, "节点输入快照哈希不一致，拒绝回放");
        }
    }

    private void verifySnapshotSource(OrchestrationRun run, NodeRun nodeRun, NodeRunSnapshot snapshot) {
        if (!Objects.equals(snapshot.getRunId(), run.getId())
                || !Objects.equals(snapshot.getNodeRunId(), nodeRun.getId())
                || !Objects.equals(snapshot.getFlowVersionId(), run.getFlowVersionId())
                || !Objects.equals(snapshot.getNodeId(), nodeRun.getNodeId())
                || !Objects.equals(snapshot.getOperatorVersionId(), nodeRun.getOperatorVersionId())) {
            throw new BusinessException(409, "节点回放快照与原运行不一致");
        }
        if (!Set.of("INPUT_CAPTURED", "COMPLETED").contains(snapshot.getStatus())) {
            throw new BusinessException(409, "节点回放快照状态不可用: " + snapshot.getStatus());
        }
    }

    private void verifyReplayBinding(NodeReplay replay, NodeRunSnapshot snapshot) {
        if (!Objects.equals(snapshot.getId(), replay.getSnapshotId())
                || !Objects.equals(snapshot.getRunId(), replay.getSourceRunId())
                || !Objects.equals(snapshot.getNodeRunId(), replay.getSourceNodeRunId())
                || !Objects.equals(snapshot.getFlowContentHash(), replay.getFlowContentHash())
                || !Objects.equals(snapshot.getOperatorVersionContentHash(), replay.getOperatorVersionContentHash())
                || !Objects.equals(snapshot.getRuntimeProfileId(), replay.getRuntimeProfileId())
                || !Objects.equals(snapshot.getRuntimeImageDigest(), replay.getRuntimeImageDigest())
                || !Objects.equals(snapshot.getInputHash(), replay.getInputHash())
                || !Objects.equals(snapshot.getOutputHash(), replay.getExpectedOutputHash())) {
            throw new BusinessException(409, "回放任务与节点快照绑定不一致");
        }
    }

    private void markFailure(NodeReplay replay, String status, String message, long startedNanos) {
        UpdateWrapper<NodeReplay> update = ownedUpdate(replay);
        update.set("status", status)
            .set("error_message", limit(message, 4000))
            .set("execution_time_ms", elapsedMillis(startedNanos))
            .set("finished_at", LocalDateTime.now())
            .set("lease_expires_at", null);
        replayMapper.update(null, update);
    }

    private UpdateWrapper<NodeReplay> ownedUpdate(NodeReplay replay) {
        UpdateWrapper<NodeReplay> update = new UpdateWrapper<>();
        return update.eq("id", replay.getId())
            .eq("status", RunStatus.RUNNING)
            .eq("attempt_no", replay.getAttemptNo())
            .eq("lease_token", replay.getLeaseToken());
    }

    private boolean ownedAndRunning(NodeReplay replay) {
        NodeReplay current = replayMapper.selectById(replay.getId());
        return current != null && RunStatus.RUNNING.equals(current.getStatus())
            && Objects.equals(current.getAttemptNo(), replay.getAttemptNo())
            && Objects.equals(current.getLeaseToken(), replay.getLeaseToken());
    }

    private String status(Long replayId) {
        NodeReplay current = replayMapper.selectById(replayId);
        return current == null ? null : current.getStatus();
    }

    private Map<String, OperatorExecutionResult> upstream(Map<String, Object> snapshots) {
        Map<String, OperatorExecutionResult> result = new LinkedHashMap<>();
        snapshots.forEach((nodeId, output) -> result.put(nodeId,
            new OperatorExecutionResult(asMap(output), List.of(), "replay snapshot")));
        return result;
    }

    private Map<String, Object> readReplayOutput(NodeReplay replay) {
        List<NodeReplayChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<NodeReplayChunk>()
            .eq(NodeReplayChunk::getReplayId, replay.getId())
            .eq(NodeReplayChunk::getAttemptNo, replay.getAttemptNo())
            .eq(NodeReplayChunk::getPayloadKind, REPLAY_OUTPUT)
            .orderByAsc(NodeReplayChunk::getChunkIndex));
        if (chunks.isEmpty()) return Map.of();
        StringBuilder encoded = new StringBuilder();
        chunks.forEach(chunk -> encoded.append(chunk.getPayloadText()));
        try {
            String json = new String(Base64.getDecoder().decode(encoded.toString()), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception error) {
            throw new BusinessException(409, "节点回放输出快照损坏");
        }
    }

    private Map<String, Object> summary(Map<String, Object> output) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object records = output.get("records");
        if (records instanceof List<?> list) result.put("recordCount", list.size());
        int included = 0;
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            if ("records".equals(entry.getKey())) continue;
            if (included >= 20) break;
            result.put(entry.getKey(), compactValue(entry.getValue()));
            included++;
        }
        int available = output.size() - (output.containsKey("records") ? 1 : 0);
        if (available > included) {
            result.put("__truncatedFields", available - included);
        }
        return result;
    }

    private List<Map<String, Object>> sample(Map<String, Object> output) {
        Object raw = output.get("records");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (result.size() >= Math.max(0, detailRecordLimit)) break;
            if (item instanceof Map<?, ?>) result.add(compactRecord(asMap(item)));
        }
        return result;
    }

    private Map<String, Object> compactRecord(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        int included = 0;
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (included++ >= 20) break;
            result.put(entry.getKey(), compactValue(entry.getValue()));
        }
        if (record.size() > result.size()) result.put("__truncatedFields", record.size() - result.size());
        return result;
    }

    private Object compactValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof CharSequence text) {
            String result = text.toString();
            return result.length() <= 240 ? result : result.substring(0, 240) + "…";
        }
        if (value instanceof Map<?, ?> map) {
            return Map.of("_type", "object", "size", map.size(),
                "hash", contentHashService.sha256(value).substring(0, 12));
        }
        if (value instanceof List<?> list) {
            return Map.of("_type", "array", "size", list.size(),
                "hash", contentHashService.sha256(value).substring(0, 12));
        }
        String result = String.valueOf(value);
        return result.length() <= 240 ? result : result.substring(0, 240) + "…";
    }

    private String boundedDiffJson(Map<String, Object> diff) {
        String encoded = json(diff);
        if (encoded.getBytes(StandardCharsets.UTF_8).length <= 60_000) return encoded;
        Map<String, Object> compact = new LinkedHashMap<>(diff);
        Object rawSamples = compact.get("samples");
        if (rawSamples instanceof List<?> samples) {
            compact.put("samples", samples.subList(0, Math.min(5, samples.size())));
        }
        compact.put("detailTruncated", true);
        encoded = json(compact);
        if (encoded.getBytes(StandardCharsets.UTF_8).length <= 60_000) return encoded;
        compact.put("samples", List.of());
        compact.put("metricChanges", Map.of("detailTruncated", true));
        return json(compact);
    }

    private OrchestrationRun requireRun(Long runId) {
        OrchestrationRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) throw new BusinessException(404, "编排运行不存在: " + runId);
        authorize(run.getOwnerUserId());
        return run;
    }

    private NodeReplay requireReplay(Long replayId) {
        NodeReplay replay = replayId == null ? null : replayMapper.selectById(replayId);
        if (replay == null) throw new BusinessException(404, "节点回放不存在: " + replayId);
        authorize(replay.getOwnerUserId());
        return replay;
    }

    private NodeRun requireSourceNode(Long runId, Long nodeRunId) {
        NodeRun node = nodeRunId == null ? null : nodeRunMapper.selectById(nodeRunId);
        if (node == null || !Objects.equals(node.getRunId(), runId)) {
            throw new BusinessException(404, "原节点运行不存在: " + nodeRunId);
        }
        return node;
    }

    private void authorize(String ownerUserId) {
        UserContextHolder.UserContext user = UserContextHolder.require();
        if (!roleService.hasPermission(user.role(), PermissionCodes.RESOURCE_ACCESS_ALL)
                && !Objects.equals(user.userId().toString(), ownerUserId)) {
            throw new BusinessException(403, "无权访问该节点回放");
        }
    }

    private UserContextHolder.UserContext actor(NodeReplay replay) {
        try {
            return new UserContextHolder.UserContext(Long.parseLong(replay.getOwnerUserId()),
                "node-replay-" + replay.getId(),
                replay.getActorRole() == null ? roleService.defaultRoleCode() : replay.getActorRole());
        } catch (NumberFormatException error) {
            throw new BusinessException(422, "节点回放所有者标识无效");
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(409, "节点回放JSON数据损坏"); }
    }

    private Object parseValue(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Object.class); }
        catch (Exception error) { throw new BusinessException(409, "不可变版本JSON内容损坏"); }
    }

    private Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new BusinessException(422, "节点回放结果无法序列化"); }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    public record ReplayDetail(NodeReplay replay, NodeRun sourceNodeRun,
                               NodeRunSnapshot snapshot, Map<String, Object> diff,
                               List<Map<String, Object>> originalSample,
                               List<Map<String, Object>> replaySample) {}

    private static final class ReplayTimeoutException extends RuntimeException {
        private ReplayTimeoutException(String message) { super(message); }
    }
}
