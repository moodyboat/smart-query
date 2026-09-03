package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Database-fenced ownership for orchestration runs across application instances. */
@Service
@RequiredArgsConstructor
public class RunLeaseService {
    private final OrchestrationRunMapper runMapper;
    private final NodeRunMapper nodeRunMapper;

    @Value("${smart-query.orchestration.lease.duration-seconds:30}")
    private int leaseDurationSeconds = 30;

    @Value("${smart-query.orchestration.lease.recovery-batch-size:50}")
    private int recoveryBatchSize = 50;

    @Value("${smart-query.orchestration.instance-id:}")
    private String configuredInstanceId;

    private String instanceId;

    @PostConstruct
    void initialize() {
        instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
            ? "smart-query-" + UUID.randomUUID()
            : configuredInstanceId.trim();
        leaseDurationSeconds = Math.max(10, leaseDurationSeconds);
        recoveryBatchSize = Math.max(1, Math.min(recoveryBatchSize, 500));
    }

    public String instanceId() {
        if (instanceId == null) initialize();
        return instanceId;
    }

    /** Atomically moves a queued or stale running job under this instance's fencing token. */
    public Optional<LeaseClaim> claim(Long runId) {
        OrchestrationRun current = runId == null ? null : runMapper.selectById(runId);
        if (current == null || RunStatus.TERMINAL.contains(current.getStatus())
                || current.getCancelRequestedAt() != null) return Optional.empty();
        if (!List.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(current.getStatus())) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        if (current.getLeaseExpiresAt() != null && !current.getLeaseExpiresAt().isBefore(now)) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        int attempt = value(current.getAttemptNo()) + 1;
        boolean recovered = RunStatus.RUNNING.equals(current.getStatus());
        int recoveries = value(current.getRecoveryCount()) + (recovered ? 1 : 0);
        UpdateWrapper<OrchestrationRun> update = new UpdateWrapper<>();
        update.set("status", RunStatus.RUNNING)
            .set("lease_owner", instanceId())
            .set("lease_token", token)
            .set("lease_expires_at", now.plusSeconds(leaseDurationSeconds))
            .set("heartbeat_at", now)
            .set("attempt_no", attempt)
            .set("recovery_count", recoveries)
            .set("started_at", current.getStartedAt() == null ? now : current.getStartedAt())
            .eq("id", runId)
            .in("status", RunStatus.QUEUED, RunStatus.RUNNING)
            .isNull("cancel_requested_at")
            .and(scope -> scope.isNull("lease_expires_at").or().lt("lease_expires_at", now));
        if (runMapper.update(null, update) != 1) return Optional.empty();
        return Optional.of(new LeaseClaim(runId, instanceId(), token, attempt, recovered, now));
    }

    public boolean renew(LeaseClaim claim) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<OrchestrationRun> update = new UpdateWrapper<>();
        update.set("heartbeat_at", now)
            .set("lease_expires_at", now.plusSeconds(leaseDurationSeconds))
            .eq("id", claim.runId())
            .eq("lease_token", claim.token())
            .eq("status", RunStatus.RUNNING)
            .isNull("cancel_requested_at");
        return runMapper.update(null, update) == 1;
    }

    public boolean isOwnedAndActive(LeaseClaim claim) {
        OrchestrationRun run = runMapper.selectById(claim.runId());
        return run != null && RunStatus.RUNNING.equals(run.getStatus())
            && claim.token().equals(run.getLeaseToken()) && run.getCancelRequestedAt() == null
            && run.getLeaseExpiresAt() != null && run.getLeaseExpiresAt().isAfter(LocalDateTime.now());
    }

    /** Resets logical node rows for a full deterministic retry while fencing stale workers. */
    public void prepareNodeRuns(LeaseClaim claim) {
        List<NodeRun> existing = nodeRunMapper.selectList(new LambdaQueryWrapper<NodeRun>()
            .eq(NodeRun::getRunId, claim.runId()));
        for (NodeRun node : existing) {
            String previous = "[attempt " + value(node.getAttemptNo()) + " ended as "
                + node.getStatus() + "; recovered by attempt " + claim.attemptNo() + "]";
            String audit = node.getExecutionLog() == null || node.getExecutionLog().isBlank()
                ? previous : node.getExecutionLog() + "\n" + previous;
            UpdateWrapper<NodeRun> update = new UpdateWrapper<>();
            update.set("status", RunStatus.PENDING)
                .set("attempt_no", claim.attemptNo())
                .set("lease_token", claim.token())
                .set("input_hash", null)
                .set("output_hash", null)
                .set("output_summary", null)
                .set("execution_log", limit(audit, 16000))
                .set("error_message", null)
                .set("execution_time_ms", null)
                .set("timeout_seconds", null)
                .set("started_at", null)
                .set("finished_at", null)
                .eq("id", node.getId())
                .eq("run_id", claim.runId());
            nodeRunMapper.update(null, update);
        }
    }

    public List<Long> recoverableRunIds() {
        LocalDateTime now = LocalDateTime.now();
        List<OrchestrationRun> runs = runMapper.selectList(new LambdaQueryWrapper<OrchestrationRun>()
            .in(OrchestrationRun::getStatus, RunStatus.QUEUED, RunStatus.RUNNING)
            .isNull(OrchestrationRun::getCancelRequestedAt)
            .and(scope -> scope.isNull(OrchestrationRun::getLeaseExpiresAt)
                .or().lt(OrchestrationRun::getLeaseExpiresAt, now))
            .orderByAsc(OrchestrationRun::getCreatedAt)
            .orderByAsc(OrchestrationRun::getId));
        List<Long> ids = new ArrayList<>();
        for (OrchestrationRun run : runs) {
            if (ids.size() >= recoveryBatchSize) break;
            ids.add(run.getId());
        }
        return List.copyOf(ids);
    }

    public boolean fail(LeaseClaim claim, String message) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<OrchestrationRun> update = new UpdateWrapper<>();
        update.set("status", RunStatus.FAILED)
            .set("error_message", limit(message, 4000))
            .set("finished_at", now)
            .set("lease_owner", null)
            .set("lease_token", null)
            .set("lease_expires_at", null)
            .eq("id", claim.runId())
            .eq("lease_token", claim.token())
            .eq("status", RunStatus.RUNNING);
        return runMapper.update(null, update) == 1;
    }

    /** Cancellation is terminal immediately; the fencing token prevents late output commits. */
    public CancellationResult cancel(Long runId, String actorUserId) {
        OrchestrationRun current = runId == null ? null : runMapper.selectById(runId);
        if (current == null) return new CancellationResult(null, false);
        if (RunStatus.TERMINAL.contains(current.getStatus())) {
            return new CancellationResult(current, false);
        }
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<OrchestrationRun> update = new UpdateWrapper<>();
        update.set("status", RunStatus.CANCELED)
            .set("cancel_requested_at", now)
            .set("cancel_requested_by", actorUserId)
            .set("finished_at", now)
            .set("error_message", "运行已由用户取消")
            .set("lease_owner", null)
            .set("lease_token", null)
            .set("lease_expires_at", null)
            .eq("id", runId)
            .notIn("status", RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.CANCELED);
        boolean transitioned = runMapper.update(null, update) == 1;
        if (transitioned) {
            UpdateWrapper<NodeRun> nodes = new UpdateWrapper<>();
            nodes.set("status", RunStatus.CANCELED)
                .set("error_message", "所属运行已取消")
                .set("finished_at", now)
                .eq("run_id", runId)
                .in("status", RunStatus.PENDING, RunStatus.RUNNING);
            nodeRunMapper.update(null, nodes);
        }
        return new CancellationResult(runMapper.selectById(runId), transitioned);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(value.length() - max);
    }

    public record LeaseClaim(Long runId, String owner, String token, int attemptNo,
                             boolean recovered, LocalDateTime claimedAt) {}

    public record CancellationResult(OrchestrationRun run, boolean transitioned) {}
}
