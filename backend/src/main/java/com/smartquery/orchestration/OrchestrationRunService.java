package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.Lead;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.NodeRunSnapshot;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.orchestration.execution.LeadDraft;
import com.smartquery.orchestration.execution.EdgeMappingService;
import com.smartquery.orchestration.execution.LineageSupport;
import com.smartquery.orchestration.execution.OperatorExecutionContext;
import com.smartquery.orchestration.execution.OperatorExecutionResult;
import com.smartquery.orchestration.execution.OperatorExecutor;
import com.smartquery.orchestration.execution.OperatorExecutorRegistry;
import com.smartquery.service.TaskEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/** Executes a safe, bounded trial run against immutable flow/operator versions. */
@Slf4j
@Service
public class OrchestrationRunService {
    private final OrchestrationRunMapper runMapper;
    private final NodeRunMapper nodeRunMapper;
    private final VersionCatalogService versionCatalogService;
    private final RuntimeProfileService runtimeProfileService;
    private final OperatorExecutorRegistry executorRegistry;
    private final EdgeMappingService edgeMappingService;
    private final ContentHashService contentHashService;
    private final RunOutputCommitService runOutputCommitService;
    private final RunLeaseService runLeaseService;
    private final RunControlRegistry runControlRegistry;
    private final NodeRunSnapshotService nodeRunSnapshotService;
    private final TaskEventService taskEventService;
    private final ObjectMapper objectMapper;
    private final Executor orchestrationExecutor;
    private final Executor orchestrationNodeExecutor;
    private final ScheduledExecutorService orchestrationWatchdog;
    private final Set<Long> dispatchedRuns = ConcurrentHashMap.newKeySet();

    public OrchestrationRunService(OrchestrationRunMapper runMapper, NodeRunMapper nodeRunMapper,
                                   VersionCatalogService versionCatalogService,
                                   RuntimeProfileService runtimeProfileService,
                                   OperatorExecutorRegistry executorRegistry,
                                   EdgeMappingService edgeMappingService,
                                   ContentHashService contentHashService,
                                   RunOutputCommitService runOutputCommitService,
                                   RunLeaseService runLeaseService,
                                   RunControlRegistry runControlRegistry,
                                   NodeRunSnapshotService nodeRunSnapshotService,
                                   TaskEventService taskEventService, ObjectMapper objectMapper,
                                   @Qualifier("orchestrationExecutor") Executor orchestrationExecutor,
                                   @Qualifier("orchestrationNodeExecutor") Executor orchestrationNodeExecutor,
                                   @Qualifier("orchestrationWatchdog") ScheduledExecutorService orchestrationWatchdog) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.versionCatalogService = versionCatalogService;
        this.runtimeProfileService = runtimeProfileService;
        this.executorRegistry = executorRegistry;
        this.edgeMappingService = edgeMappingService;
        this.contentHashService = contentHashService;
        this.runOutputCommitService = runOutputCommitService;
        this.runLeaseService = runLeaseService;
        this.runControlRegistry = runControlRegistry;
        this.nodeRunSnapshotService = nodeRunSnapshotService;
        this.taskEventService = taskEventService;
        this.objectMapper = objectMapper;
        this.orchestrationExecutor = orchestrationExecutor;
        this.orchestrationNodeExecutor = orchestrationNodeExecutor;
        this.orchestrationWatchdog = orchestrationWatchdog;
    }

    @Value("${smart-query.orchestration.trial.max-records:1000}")
    private int maxRecords;

    @Value("${smart-query.orchestration.trial.max-input-bytes:2097152}")
    private int maxInputBytes;

    @Value("${smart-query.orchestration.trial.summary-sample-size:5}")
    private int summarySampleSize;

    @Value("${smart-query.orchestration.node.default-timeout-seconds:300}")
    private int defaultNodeTimeoutSeconds = 300;

    public OrchestrationRun submitTrial(Long flowVersionId, Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : new LinkedHashMap<>(input);
        validateInput(safeInput);
        FlowVersion flowVersion = versionCatalogService.requireFlowVersion(flowVersionId);
        FlowPlan plan = flowPlan(flowVersion);
        preflight(plan);

        OrchestrationRun run = new OrchestrationRun();
        run.setFlowVersionId(flowVersionId);
        run.setOwnerUserId(currentUserId());
        run.setActorRole(UserContextHolder.require().role());
        run.setTriggerType("API");
        run.setRunMode("TRIAL");
        run.setStatus(RunStatus.QUEUED);
        run.setAttemptNo(0);
        run.setRecoveryCount(0);
        run.setInputSnapshot(json(safeInput));
        runMapper.insert(run);
        publish(run, "run_queued", Map.of("runId", run.getId(), "status", RunStatus.QUEUED), false);

        dispatch(run.getId());
        return run;
    }

    public OrchestrationRun getRun(Long runId) {
        return requireRun(runId);
    }

    public List<OrchestrationRun> listFlowRuns(Long flowId, int limit) {
        List<Long> versionIds = versionCatalogService.listFlowVersions(flowId).stream()
            .map(FlowVersion::getId)
            .toList();
        if (versionIds.isEmpty()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<OrchestrationRun> query = new LambdaQueryWrapper<OrchestrationRun>()
            .in(OrchestrationRun::getFlowVersionId, versionIds)
            .orderByDesc(OrchestrationRun::getCreatedAt)
            .orderByDesc(OrchestrationRun::getId);
        if (!isAdmin()) query.eq(OrchestrationRun::getOwnerUserId, currentUserId());
        query.last("LIMIT " + safeLimit);
        return runMapper.selectList(query);
    }

    public List<NodeRun> listNodeRuns(Long runId) {
        requireRun(runId);
        return nodeRunMapper.selectList(new LambdaQueryWrapper<NodeRun>()
            .eq(NodeRun::getRunId, runId)
            .orderByAsc(NodeRun::getCreatedAt)
            .orderByAsc(NodeRun::getId));
    }

    public SseEmitter subscribe(Long runId, Long lastEventId) {
        OrchestrationRun run = requireRun(runId);
        return taskEventService.subscribe(TaskEventService.orchestrationTopic(runId),
            run.getOwnerUserId(), lastEventId);
    }

    public OrchestrationRun cancel(Long runId) {
        OrchestrationRun authorized = requireRun(runId);
        RunLeaseService.CancellationResult result = runLeaseService.cancel(runId, currentUserId());
        if (result.run() == null) throw new BusinessException(404, "编排运行不存在: " + runId);
        if (result.transitioned()) {
            runControlRegistry.cancelLocal(runId);
            publish(authorized, "run_canceled", Map.of(
                "runId", runId, "status", RunStatus.CANCELED,
                "requestedBy", currentUserId()), true);
        }
        return result.run();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        dispatchRecoverableRuns();
    }

    @Scheduled(fixedDelayString = "${smart-query.orchestration.lease.scan-interval-ms:5000}")
    public void dispatchRecoverableRuns() {
        try {
            runLeaseService.recoverableRunIds().forEach(this::dispatch);
        } catch (Exception e) {
            log.warn("[ORCHESTRATION] recoverable run scan failed: {}", rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-query.orchestration.lease.heartbeat-interval-ms:5000}")
    public void renewActiveLeases() {
        for (RunControlRegistry.ActiveRun control : runControlRegistry.activeRuns()) {
            try {
                if (!runLeaseService.renew(control.claim())) {
                    control.stop(RunControlRegistry.StopReason.LEASE_LOST);
                }
            } catch (Exception e) {
                log.warn("[ORCHESTRATION] lease renewal failed for run {}: {}",
                    control.claim().runId(), rootMessage(e));
            }
        }
    }

    private void dispatch(Long runId) {
        if (runId == null || !dispatchedRuns.add(runId)) return;
        try {
            orchestrationExecutor.execute(() -> {
                try { execute(runId); }
                finally { dispatchedRuns.remove(runId); }
            });
        } catch (RejectedExecutionException e) {
            dispatchedRuns.remove(runId);
            log.info("[ORCHESTRATION] run {} remains queued because the local executor is full", runId);
        }
    }

    void execute(Long runId) {
        OrchestrationRun initial = runMapper.selectById(runId);
        if (initial == null || RunStatus.TERMINAL.contains(initial.getStatus())) return;
        UserContextHolder.UserContext actor = runActor(initial);
        try (UserContextHolder.Scope ignored = UserContextHolder.open(actor)) {
            java.util.Optional<RunLeaseService.LeaseClaim> claimed = runLeaseService.claim(runId);
            if (claimed.isEmpty()) return;
            RunLeaseService.LeaseClaim claim = claimed.get();
            RunControlRegistry.ActiveRun control = runControlRegistry.register(claim);
            OrchestrationRun run = runMapper.selectById(runId);
            LocalDateTime started = run.getStartedAt() == null ? claim.claimedAt() : run.getStartedAt();
            try {
                FlowVersion flowVersion = versionCatalogService.requireFlowVersion(run.getFlowVersionId());
                FlowPlan plan = flowPlan(flowVersion);
                long elapsedBeforeRecovery = Duration.between(started, LocalDateTime.now()).toMillis();
                java.util.Optional<RunOutputCommitService.CommitResult> existing = claim.recovered()
                    ? runOutputCommitService.finalizeExistingCommit(runId, claim.token(),
                        plan.nodes().size(), elapsedBeforeRecovery)
                    : java.util.Optional.empty();
                if (existing.isPresent()) {
                    publishCompletion(runMapper.selectById(runId), existing.get(), true);
                    return;
                }

                runLeaseService.prepareNodeRuns(claim);
                publish(run, claim.recovered() ? "run_recovered" : "run_started", Map.of(
                    "runId", runId, "status", RunStatus.RUNNING,
                    "attemptNo", claim.attemptNo(), "recovered", claim.recovered()), false);

                List<PendingLead> pendingLeads = java.util.Collections.synchronizedList(new ArrayList<>());
                List<RunOutputCommitService.ArtifactInput> pendingArtifacts =
                    java.util.Collections.synchronizedList(new ArrayList<>());
                Map<String, OperatorExecutionResult> outputs = new ConcurrentHashMap<>();
                Map<String, Object> runInput = withLineage(run.getId(),
                    object(run.getInputSnapshot(), "inputSnapshot"));
                for (List<String> level : plan.validation().executionLevels()) {
                    control.assertActive();
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (String nodeId : level) {
                        Map<String, Object> node = plan.nodes().get(nodeId);
                        int timeoutSeconds = nodeTimeoutSeconds(node);
                        futures.add(submitNode(control, nodeId, timeoutSeconds, () -> {
                            NodeExecutionResult executed = executeNode(run, flowVersion, node,
                                plan.incoming().getOrDefault(nodeId, List.of()), runInput, outputs,
                                control, timeoutSeconds);
                            outputs.put(nodeId, executed.result());
                            if (OperatorTypes.OUTPUT.equals(executed.operatorType())) {
                                executed.result().leads().forEach(draft -> pendingLeads.add(
                                    new PendingLead(draft, executed.nodeRun(), executed.version())));
                            }
                            artifactInput(run, executed).ifPresent(pendingArtifacts::add);
                        }));
                    }
                    try {
                        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                    } catch (CompletionException e) {
                        control.stop(RunControlRegistry.StopReason.ABORTED);
                        throw e;
                    }
                }
                control.assertActive();
                if (!runLeaseService.isOwnedAndActive(claim)) {
                    control.stop(RunControlRegistry.StopReason.LEASE_LOST);
                    control.assertActive();
                }
                List<LeadService.LeadInput> leadInputs = pendingLeads.stream()
                    .map(item -> toLeadInput(item.draft(), run, flowVersion,
                        item.nodeRun(), item.version())).toList();
                long executionTimeMs = Duration.between(started, LocalDateTime.now()).toMillis();
                RunOutputCommitService.CommitResult committed = runOutputCommitService.commitRun(
                    runId, claim.token(), plan.nodes().size(), executionTimeMs,
                    leadInputs, List.copyOf(pendingArtifacts));
                publishCompletion(runMapper.selectById(runId), committed, false);
            } catch (Exception e) {
                if (hasCause(e, RunControlRegistry.RunCanceledException.class)) {
                    log.info("[ORCHESTRATION] run {} canceled", runId);
                } else if (hasCause(e, RunControlRegistry.LeaseLostException.class)) {
                    log.info("[ORCHESTRATION] run {} stopped after losing its lease", runId);
                } else {
                    control.stop(RunControlRegistry.StopReason.ABORTED);
                    failRun(run, claim, rootMessage(e));
                }
            } finally {
                runControlRegistry.unregister(control);
            }
        }
    }

    private CompletableFuture<Void> submitNode(RunControlRegistry.ActiveRun runControl,
                                               String nodeId, int timeoutSeconds, Runnable task) {
        RunControlRegistry.NodeControl nodeControl = runControl.node(nodeId);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            orchestrationNodeExecutor.execute(() -> {
                ScheduledFuture<?> timeout = null;
                try {
                    nodeControl.bindCurrentThread();
                    timeout = orchestrationWatchdog.schedule(() -> {
                        if (completion.isDone()) return;
                        nodeControl.timeout();
                        markNodeTimedOut(runControl.claim(), nodeId, timeoutSeconds);
                        completion.completeExceptionally(
                            new RunControlRegistry.NodeTimeoutException(nodeId));
                    }, timeoutSeconds, TimeUnit.SECONDS);
                    task.run();
                    nodeControl.assertActive();
                    completion.complete(null);
                } catch (Throwable e) {
                    completion.completeExceptionally(e);
                } finally {
                    if (timeout != null) timeout.cancel(false);
                    runControl.removeNode(nodeControl);
                    Thread.interrupted();
                }
            });
        } catch (RejectedExecutionException e) {
            runControl.removeNode(nodeControl);
            completion.completeExceptionally(new BusinessException(503, "节点执行队列已满"));
        }
        return completion;
    }

    private NodeExecutionResult executeNode(OrchestrationRun run, FlowVersion flowVersion,
                                            Map<String, Object> node,
                                            List<Map<String, Object>> predecessorEdges,
                                            Map<String, Object> runInput,
                                            Map<String, OperatorExecutionResult> outputs,
                                            RunControlRegistry.ActiveRun runControl,
                                            int timeoutSeconds) {
        String nodeId = String.valueOf(node.get("id"));
        RunControlRegistry.NodeControl nodeControl = runControl.node(nodeId);
        Long versionId = DagValidationService.toLong(node.get("operatorVersionId"));
        NodeRun nodeRun = findNodeRun(run.getId(), nodeId);
        if (nodeRun == null) {
            nodeRun = new NodeRun();
            nodeRun.setRunId(run.getId());
            nodeRun.setNodeId(nodeId);
            nodeRun.setOperatorVersionId(versionId);
            nodeRun.setStatus(RunStatus.RUNNING);
            nodeRun.setAttemptNo(runControl.claim().attemptNo());
            nodeRun.setLeaseToken(runControl.claim().token());
            nodeRun.setTimeoutSeconds(timeoutSeconds);
            nodeRun.setStartedAt(LocalDateTime.now());
            nodeRunMapper.insert(nodeRun);
        } else {
            UpdateWrapper<NodeRun> start = new UpdateWrapper<>();
            start.set("status", RunStatus.RUNNING)
                .set("operator_version_id", versionId)
                .set("timeout_seconds", timeoutSeconds)
                .set("started_at", LocalDateTime.now())
                .eq("id", nodeRun.getId())
                .eq("lease_token", runControl.claim().token())
                .eq("status", RunStatus.PENDING);
            if (nodeRunMapper.update(null, start) != 1) {
                nodeControl.assertActive();
                throw new RunControlRegistry.LeaseLostException();
            }
            nodeRun = nodeRunMapper.selectById(nodeRun.getId());
        }
        Map<String, OperatorExecutionResult> upstream = new LinkedHashMap<>();
        publish(run, "node_started", Map.of("runId", run.getId(), "nodeRunId", nodeRun.getId(),
            "nodeId", nodeId, "status", RunStatus.RUNNING,
            "attemptNo", runControl.claim().attemptNo(), "timeoutSeconds", timeoutSeconds), false);

        long startedNanos = System.nanoTime();
        try {
            nodeControl.assertActive();
            predecessorEdges.forEach(edge -> {
                String source = String.valueOf(edge.get("source"));
                upstream.put(source, edgeMappingService.apply(edge, outputs.get(source)));
            });
            nodeRun.setInputHash(contentHashService.sha256(inputHashMaterial(runInput, upstream)));
            updateNodeField(nodeRun.getId(), runControl.claim().token(), "input_hash", nodeRun.getInputHash());
            OperatorVersion version = versionCatalogService.requireOperatorVersionVisible(versionId);
            OperatorDefinition definition = versionCatalogService.requireOperatorDefinitionForVersion(versionId);
            RuntimeProfileService.RuntimeBindingView runtime =
                runtimeProfileService.requireRunnable(version, definition.getOperatorType());
            NodeRunSnapshot replaySnapshot = nodeRunSnapshotService.captureInput(
                nodeRun, flowVersion, node, runInput, upstream, version, definition, runtime);
            OperatorExecutor executor = executorRegistry.require(version.getImplementationType());
            OperatorExecutionContext context = new OperatorExecutionContext(run.getId(), nodeRun.getId(),
                nodeId, definition.getOperatorType(), version,
                object(version.getImplementationPayload(), "implementationPayload"),
                map(node.get("config")), runInput, upstream);
            OperatorExecutionResult result = executor.execute(context);
            nodeControl.assertActive();
            LineageSupport.requirePreserved(result.output(), nodeId);
            nodeRunSnapshotService.captureOutput(replaySnapshot, result.output());

            nodeRun.setStatus(RunStatus.SUCCESS);
            nodeRun.setOutputHash(contentHashService.sha256(result.output()));
            nodeRun.setOutputSummary(json(summary(result)));
            nodeRun.setExecutionLog(limit("runtimeProfile=" + runtime.profile().getCode()
                + " imageDigest=" + runtime.binding().getImageDigest() + "\n"
                + edgeMappingLog(predecessorEdges)
                + result.executionLog(), 16000));
            nodeRun.setExecutionTimeMs(elapsedMillis(startedNanos));
            nodeRun.setFinishedAt(LocalDateTime.now());
            UpdateWrapper<NodeRun> success = new UpdateWrapper<>();
            success.set("status", RunStatus.SUCCESS)
                .set("output_hash", nodeRun.getOutputHash())
                .set("output_summary", nodeRun.getOutputSummary())
                .set("execution_log", nodeRun.getExecutionLog())
                .set("execution_time_ms", nodeRun.getExecutionTimeMs())
                .set("finished_at", nodeRun.getFinishedAt())
                .eq("id", nodeRun.getId())
                .eq("lease_token", runControl.claim().token())
                .eq("status", RunStatus.RUNNING);
            if (nodeRunMapper.update(null, success) != 1) {
                nodeControl.assertActive();
                throw new RunControlRegistry.LeaseLostException();
            }
            publish(run, "node_completed", Map.of("runId", run.getId(), "nodeRunId", nodeRun.getId(),
                "nodeId", nodeId, "status", RunStatus.SUCCESS,
                "executionTimeMs", nodeRun.getExecutionTimeMs()), false);
            return new NodeExecutionResult(result, nodeRun, version, definition.getOperatorType());
        } catch (Exception e) {
            String status = nodeControl.timedOut() ? RunStatus.TIMED_OUT
                : runControl.stopReason() == RunControlRegistry.StopReason.CANCELED
                    ? RunStatus.CANCELED : RunStatus.FAILED;
            String message = RunStatus.TIMED_OUT.equals(status)
                ? "节点[" + nodeId + "]超过" + timeoutSeconds + "秒执行上限"
                : rootMessage(e);
            nodeRun.setStatus(status);
            nodeRun.setErrorMessage(limit(message, 4000));
            nodeRun.setExecutionTimeMs(elapsedMillis(startedNanos));
            nodeRun.setFinishedAt(LocalDateTime.now());
            UpdateWrapper<NodeRun> failed = new UpdateWrapper<>();
            failed.set("status", status)
                .set("error_message", nodeRun.getErrorMessage())
                .set("execution_time_ms", nodeRun.getExecutionTimeMs())
                .set("finished_at", nodeRun.getFinishedAt())
                .eq("id", nodeRun.getId())
                .eq("lease_token", runControl.claim().token())
                .eq("status", RunStatus.RUNNING);
            if (nodeRunMapper.update(null, failed) == 1) {
                publish(run, RunStatus.TIMED_OUT.equals(status) ? "node_timed_out" : "node_failed",
                    Map.of("runId", run.getId(), "nodeRunId", nodeRun.getId(),
                        "nodeId", nodeId, "status", status, "error", message), false);
            }
            if (nodeControl.timedOut()) throw new RunControlRegistry.NodeTimeoutException(nodeId);
            if (runControl.stopReason() == RunControlRegistry.StopReason.CANCELED) {
                throw new RunControlRegistry.RunCanceledException();
            }
            throw e instanceof RuntimeException runtime ? runtime : new BusinessException(message);
        }
    }

    private LeadService.LeadInput toLeadInput(LeadDraft draft, OrchestrationRun run,
                                              FlowVersion flowVersion, NodeRun nodeRun,
                                              OperatorVersion version) {
        List<LeadService.EvidenceInput> evidence = draft.evidence() == null ? List.of()
            : draft.evidence().stream().map(item -> new LeadService.EvidenceInput(
                nodeRun.getId(), version.getId(), item.kind(), item.name(), item.field(),
                item.actualValue(), item.condition(), item.contribution(), item.snippet())).toList();
        return new LeadService.LeadInput(draft.leadType(), run.getOwnerUserId(),
            draft.subjectType(), draft.subjectId(), draft.subjectName(), draft.decisionScore(),
            draft.decisionLevel(), draft.decisionThreshold(), draft.decisionResult(),
            flowVersion.getId(), run.getId(), draft.dataSourceId(), draft.sourceTable(),
            draft.primaryKeyColumn(), draft.primaryKeyValue(), draft.sourceSnapshot(),
            draft.attributes(), evidence, draft.occurredAt());
    }

    private FlowPlan flowPlan(FlowVersion flowVersion) {
        List<Map<String, Object>> nodes = list(flowVersion.getNodes(), "nodes");
        List<Map<String, Object>> edges = list(flowVersion.getEdges(), "edges");
        VersionCatalogService.FlowValidationReport validation =
            versionCatalogService.validateFlowDraft(nodes, edges);
        if (!validation.valid()) {
            throw new BusinessException(422, "流程版本DAG无效: " + String.join("；", validation.errors()));
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(String.valueOf(node.get("id")), node));
        Map<String, List<Map<String, Object>>> incoming = new LinkedHashMap<>();
        Map<String, Integer> outgoingCount = new LinkedHashMap<>();
        byId.keySet().forEach(id -> incoming.put(id, new ArrayList<>()));
        byId.keySet().forEach(id -> outgoingCount.put(id, 0));
        edges.forEach(edge -> {
            String source = String.valueOf(edge.get("source"));
            incoming.get(String.valueOf(edge.get("target"))).add(edge);
            outgoingCount.compute(source, (key, count) -> count + 1);
        });
        List<String> terminals = byId.keySet().stream()
            .filter(id -> outgoingCount.get(id) == 0).toList();
        return new FlowPlan(byId, incoming, terminals, validation);
    }

    private void preflight(FlowPlan plan) {
        boolean hasLeadOutput = false;
        for (Map<String, Object> node : plan.nodes().values()) {
            Long versionId = DagValidationService.toLong(node.get("operatorVersionId"));
            OperatorVersion version = versionCatalogService.requireOperatorVersionVisible(versionId);
            OperatorDefinition definition = versionCatalogService.requireOperatorDefinitionForVersion(versionId);
            runtimeProfileService.requireRunnable(version, definition.getOperatorType());
            executorRegistry.require(version.getImplementationType());
            if (OperatorTypes.OUTPUT.equals(definition.getOperatorType())) {
                Map<String, Object> payload = object(version.getImplementationPayload(), "输出算子工件");
                if ("LEAD".equalsIgnoreCase(String.valueOf(payload.get("outputKind")))) {
                    hasLeadOutput = true;
                }
            }
        }
        if (!hasLeadOutput) {
            throw new BusinessException(422, "流程版本缺少LEAD输出算子，请创建新流程版本以自动补齐");
        }
    }

    private Map<String, Object> inputHashMaterial(Map<String, Object> runInput,
                                                   Map<String, OperatorExecutionResult> upstream) {
        if (upstream.isEmpty()) return runInput;
        Map<String, Object> material = new LinkedHashMap<>();
        upstream.forEach((id, result) -> material.put(id, result == null ? null : result.output()));
        return material;
    }

    private String edgeMappingLog(List<Map<String, Object>> edges) {
        if (edges.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (Map<String, Object> edge : edges) {
            Object rawMappings = edge.get("fieldMappings");
            int count = rawMappings instanceof List<?> list ? list.size() : 0;
            result.append("edge=").append(edge.get("source")).append("->")
                .append(edge.get("target")).append(" mappingMode=")
                .append(edge.getOrDefault("mappingMode", SchemaCompatibilityService.MERGE))
                .append(" fields=").append(count).append('\n');
        }
        return result.toString();
    }

    private Map<String, Object> summary(OperatorExecutionResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Object records = result.output().get("records");
        if (records instanceof List<?> list) {
            summary.put("recordCount", list.size());
            summary.put("sample", list.subList(0, Math.min(Math.max(summarySampleSize, 0), list.size())));
        }
        summary.put("leadCount", result.leads().size());
        result.output().forEach((key, value) -> {
            if (!"records".equals(key)) summary.put(key, value);
        });
        return summary;
    }

    private java.util.Optional<RunOutputCommitService.ArtifactInput> artifactInput(
            OrchestrationRun run, NodeExecutionResult executed) {
        Object rawKind = executed.result().output().get("outputKind");
        if (rawKind == null || String.valueOf(rawKind).isBlank()) return java.util.Optional.empty();
        String kind = String.valueOf(rawKind);
        Map<String, Object> artifact = map(executed.result().output().get("artifact"));
        Object contentSpec = artifact.getOrDefault("contentSpec", Map.of());
        Map<String, Object> data = new LinkedHashMap<>(artifact);
        data.put("outputHash", contentHashService.sha256(executed.result().output()));
        data.put("sample", summary(executed.result()).getOrDefault("sample", List.of()));
        return java.util.Optional.of(new RunOutputCommitService.ArtifactInput(
            run.getId(), executed.nodeRun().getId(), run.getOwnerUserId(), kind, "READY",
            contentSpec, data, outputRecords(executed.result().output().get("records"))));
    }

    private List<Map<String, Object>> outputRecords(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) result.add(map(item));
        }
        return result;
    }

    private int nodeTimeoutSeconds(Map<String, Object> node) {
        Object raw = map(node.get("config")).get("nodeTimeoutSeconds");
        if (raw == null) return Math.max(1, Math.min(defaultNodeTimeoutSeconds, 3600));
        Long value = DagValidationService.toLong(raw);
        if (value == null || value < 1 || value > 3600) {
            throw new BusinessException(422, "nodeTimeoutSeconds必须是1到3600之间的整数");
        }
        return value.intValue();
    }

    private NodeRun findNodeRun(Long runId, String nodeId) {
        List<NodeRun> rows = nodeRunMapper.selectList(new LambdaQueryWrapper<NodeRun>()
            .eq(NodeRun::getRunId, runId).eq(NodeRun::getNodeId, nodeId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void updateNodeField(Long nodeRunId, String leaseToken, String field, Object value) {
        UpdateWrapper<NodeRun> update = new UpdateWrapper<>();
        update.set(field, value)
            .eq("id", nodeRunId)
            .eq("lease_token", leaseToken)
            .eq("status", RunStatus.RUNNING);
        if (nodeRunMapper.update(null, update) != 1) {
            throw new RunControlRegistry.LeaseLostException();
        }
    }

    private void markNodeTimedOut(RunLeaseService.LeaseClaim claim, String nodeId, int timeoutSeconds) {
        NodeRun nodeRun = findNodeRun(claim.runId(), nodeId);
        if (nodeRun == null) return;
        LocalDateTime now = LocalDateTime.now();
        String message = "节点[" + nodeId + "]超过" + timeoutSeconds + "秒执行上限";
        UpdateWrapper<NodeRun> update = new UpdateWrapper<>();
        update.set("status", RunStatus.TIMED_OUT)
            .set("error_message", message)
            .set("finished_at", now)
            .eq("id", nodeRun.getId())
            .eq("lease_token", claim.token())
            .eq("status", RunStatus.RUNNING);
        if (nodeRunMapper.update(null, update) == 1) {
            OrchestrationRun run = runMapper.selectById(claim.runId());
            if (run != null) publish(run, "node_timed_out", Map.of(
                "runId", claim.runId(), "nodeRunId", nodeRun.getId(), "nodeId", nodeId,
                "status", RunStatus.TIMED_OUT, "timeoutSeconds", timeoutSeconds,
                "error", message), false);
        }
    }

    private void publishCompletion(OrchestrationRun run,
                                   RunOutputCommitService.CommitResult committed,
                                   boolean recoveredCommit) {
        List<Long> leadIds = committed.leads().stream().map(Lead::getId).toList();
        List<Long> artifactIds = committed.artifacts().stream()
            .map(com.smartquery.entity.OutputArtifact::getId).toList();
        publish(run, "run_completed", Map.of(
            "runId", run.getId(), "status", RunStatus.SUCCESS,
            "leadCount", leadIds.size(), "leadIds", leadIds,
            "artifactCount", artifactIds.size(), "artifactIds", artifactIds,
            "recoveredCommit", recoveredCommit), true);
    }

    private void validateInput(Map<String, Object> input) {
        Object records = input.get("records");
        if (!(records instanceof List<?> list)) throw new BusinessException("试运行输入必须包含records数组");
        if (list.size() > maxRecords) throw new BusinessException(413, "试运行最多允许" + maxRecords + "条记录");
        for (Object record : list) {
            if (!(record instanceof Map<?, ?>)) throw new BusinessException("records中的每一项必须是对象");
            if (((Map<?, ?>) record).keySet().stream().anyMatch(key -> String.valueOf(key).startsWith("__"))) {
                throw new BusinessException("输入字段不能使用平台保留前缀__");
            }
        }
        byte[] bytes;
        try { bytes = objectMapper.writeValueAsBytes(input); }
        catch (Exception e) { throw new BusinessException("试运行输入无法序列化: " + e.getMessage()); }
        if (bytes.length > maxInputBytes) throw new BusinessException(413, "试运行输入超过大小限制");
    }

    private OrchestrationRun requireRun(Long id) {
        OrchestrationRun run = id == null ? null : runMapper.selectById(id);
        if (run == null) throw new BusinessException(404, "编排运行不存在: " + id);
        if (!isAdmin() && !currentUserId().equals(run.getOwnerUserId())) {
            throw new BusinessException(403, "无权访问该编排运行");
        }
        return run;
    }

    private void failRun(OrchestrationRun run, RunLeaseService.LeaseClaim claim, String message) {
        if (runLeaseService.fail(claim, message)) {
            OrchestrationRun failed = runMapper.selectById(run.getId());
            publish(failed, "run_failed", Map.of("runId", run.getId(), "status", RunStatus.FAILED,
                "error", message), true);
            log.warn("[ORCHESTRATION] run {} failed: {}", run.getId(), message);
        }
    }

    private void publish(OrchestrationRun run, String event, Map<String, Object> payload, boolean terminal) {
        taskEventService.publish(TaskEventService.orchestrationTopic(run.getId()),
            run.getOwnerUserId(), event, payload, terminal);
    }

    private List<Map<String, Object>> list(String json, String field) {
        try { return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { throw new BusinessException(422, field + "快照无法解析"); }
    }

    private Map<String, Object> object(String json, String field) {
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new BusinessException(422, field + "快照无法解析"); }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Map<String, Object> withLineage(Long runId, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        Object raw = input.get("records");
        List<Map<String, Object>> records = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) records.add(map(item));
        }
        result.put("records", LineageSupport.enrich(runId, records));
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("运行数据序列化失败: " + e.getMessage()); }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private UserContextHolder.UserContext runActor(OrchestrationRun run) {
        try {
            return new UserContextHolder.UserContext(Long.parseLong(run.getOwnerUserId()),
                "orchestration-run-" + run.getId(),
                run.getActorRole() == null || run.getActorRole().isBlank()
                    ? UserRoles.USER : run.getActorRole());
        } catch (NumberFormatException e) {
            throw new BusinessException(422, "编排运行所有者标识无效: " + run.getOwnerUserId());
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String currentUserId() { return UserContextHolder.require().userId().toString(); }
    private boolean isAdmin() { return UserRoles.ADMIN.equals(UserContextHolder.require().role()); }

    private record FlowPlan(Map<String, Map<String, Object>> nodes,
                            Map<String, List<Map<String, Object>>> incoming,
                            List<String> terminals,
                            VersionCatalogService.FlowValidationReport validation) {}

    private record NodeExecutionResult(OperatorExecutionResult result, NodeRun nodeRun,
                                       OperatorVersion version, String operatorType) {}
    private record PendingLead(LeadDraft draft, NodeRun nodeRun, OperatorVersion version) {}
}
